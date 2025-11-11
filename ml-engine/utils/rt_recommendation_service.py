# rt_recommendation_service.py
"""
FastAPI service that wraps the real-time recommendations engine.

Run:
  pip install -r requirements.txt
  python -m uvicorn utils.rt_recommendation_service:app --host 0.0.0.0 --port 8000 --reload

Notes:
- Expects (as saved by your training script):
    models/heart_rate_model.pkl
    models/speed_model.pkl
    models/scaler_hr.pkl
    models/scaler_speed.pkl
    models/model_metadata.json   (has hr_feature_cols, speed_feature_cols)
- Uses: utils/features.py  (add_features(df))
"""

from __future__ import annotations
from typing import Optional, List, Dict, Any
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
import os, sys, threading, time, json
from pathlib import Path

import numpy as np
import pandas as pd
import joblib

# ===================== Project Root & Paths =====================
RUN_TOGETHER_ROOT = os.environ.get("RUN_TOGETHER_ROOT")
if RUN_TOGETHER_ROOT:
    ROOT_DIR = Path(RUN_TOGETHER_ROOT).resolve()
else:
    ROOT_DIR = Path(__file__).resolve().parents[1]

if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

MODELS_DIR = ROOT_DIR / "models"

# --- Import your features builder ---
from utils.features import add_features  # noqa: E402

# ===================== Safe loaders =====================
def _safe_load(path: Path):
    try:
        return joblib.load(path)
    except Exception:
        return None

HEART_RATE_MODEL = _safe_load(MODELS_DIR / "heart_rate_model.pkl")
SPEED_MODEL      = _safe_load(MODELS_DIR / "speed_model.pkl")
SCALER_HR        = _safe_load(MODELS_DIR / "scaler_hr.pkl")
SCALER_SPEED     = _safe_load(MODELS_DIR / "scaler_speed.pkl")

META_PATH = MODELS_DIR / "model_metadata.json"
if not META_PATH.exists():
    raise RuntimeError(f"Missing metadata file: {META_PATH}")
with open(META_PATH, "r", encoding="utf-8") as f:
    _meta = json.load(f)

HR_FEATURES:    List[str] = _meta["hr_feature_cols"]
SPEED_FEATURES: List[str] = _meta["speed_feature_cols"]
SPEED_LEAD_STEPS: int = _meta.get("SPEED_LEAD_STEPS", 0)

# ===================== Utility: align feature columns =====================
def align_features(df_row: pd.DataFrame, expected_cols: List[str]) -> pd.DataFrame:
    df_row = df_row.copy()
    for c in expected_cols:
        if c not in df_row.columns:
            df_row[c] = 0.0
    return df_row[expected_cols]

# ===================== API Schemas =====================
class Sample(BaseModel):
    timestamp: Optional[float] = Field(None, description="Unix seconds; server fills if missing")
    heart_rate: Optional[float] = None
    # Client may send ONE of the below; server will normalize to m/s for the engine:
    speed_kmh: Optional[float] = None
    speed_mps: Optional[float] = None
    enhanced_speed: Optional[float] = None  # interpreted as m/s if provided
    cadence: Optional[float] = None
    power: Optional[float] = None
    distance_km: Optional[float] = None
    elevation_m: Optional[float] = None

class TickRequest(BaseModel):
    session_id: str
    runner_level: int = Field(2, ge=1, le=3, description="1=Beginner,2=Intermediate,3=Advanced")
    samples: List[Sample]

class Recommendation(BaseModel):
    pred_hr: Optional[float]
    pred_speed: Optional[float]   # m/s
    recommendation: str

class TickResponse(BaseModel):
    session_id: str
    display_recommendation: bool
    result: Recommendation
    server_time: float

# ===================== Session State =====================
class SessionState:
    def __init__(self, session_id: str, runner_level: int = 2):
        self.session_id = session_id
        self.runner_level = runner_level
        self.history_df = pd.DataFrame(columns=[
            "timestamp", "heart_rate", "enhanced_speed", "cadence", "power",
            "distance_km", "elevation_m"
        ]).astype({
            "timestamp": "float64",
            "heart_rate": "float64",
            "enhanced_speed": "float64",  # m/s
            "cadence": "float64",
            "power": "float64",
            "distance_km": "float64",
            "elevation_m": "float64",
        })
        self.last_ts = time.time()

    def add_samples(self, samples: List[Dict[str, Any]]):
        if not samples:
            return
        df_add = pd.DataFrame(samples)
        self.history_df = pd.concat([self.history_df, df_add], ignore_index=True)
        t_now = samples[-1]["timestamp"]
        self.history_df = self.history_df[self.history_df["timestamp"] >= (t_now - 600)]
        self.history_df.reset_index(drop=True, inplace=True)
        self.last_ts = time.time()

SESSIONS: Dict[str, SessionState] = {}
SESSIONS_LOCK = threading.Lock()
SESSION_TTL = 60 * 30
CLEANUP_PERIOD = 60

# ===================== Recommendation Logic =====================
def recommend(current_data: Dict[str, Any], history_df: pd.DataFrame, runner_level: int = 2) -> Dict[str, Any]:
    if runner_level == 1:
        hr_slow_threshold, fatigue_threshold, hr_diff_threshold, speed_diff_threshold = 0.80, 1.15, 8, 0.25
    elif runner_level == 3:
        hr_slow_threshold, fatigue_threshold, hr_diff_threshold, speed_diff_threshold = 0.90, 1.25, 12, 0.35
    else:
        hr_slow_threshold, fatigue_threshold, hr_diff_threshold, speed_diff_threshold = 0.85, 1.20, 10, 0.30

    current_hr = float(current_data.get("heart_rate", 0.0) or 0.0)
    current_speed = float(current_data.get("enhanced_speed", 0.0) or 0.0)  # m/s
    percent_hr_max = current_hr / 190.0

    if len(history_df) < 30:
        if percent_hr_max > hr_slow_threshold:
            rec = "You're starting too fast – slow down to warm up properly"
        elif percent_hr_max < 0.5 and current_speed > (7/3.6):  # 7 km/h in m/s
            rec = "Consider slowing down a bit for a smoother warm-up"
        elif percent_hr_max < 0.4 and current_speed < (4/3.6):  # 4 km/h in m/s
            rec = "Try to gradually increase your pace"
        else:
            rec = "Warm-up phase – maintain steady pace"
        return {"pred_hr": None, "pred_speed": None, "recommendation": rec}

    hist_feat = add_features(history_df.copy())
    latest = hist_feat.iloc[-1:].copy()

    X_hr = align_features(latest, HR_FEATURES)
    X_spd = align_features(latest, SPEED_FEATURES)

    if any(x is None for x in [HEART_RATE_MODEL, SPEED_MODEL, SCALER_HR, SCALER_SPEED]):
        raise HTTPException(status_code=500, detail="Models or scalers not loaded. See /health.")

    try:
        X_hr_scaled = SCALER_HR.transform(X_hr)
        X_spd_scaled = SCALER_SPEED.transform(X_spd)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Scaler transform failed: {e}")

    try:
        pred_hr = float(HEART_RATE_MODEL.predict(X_hr_scaled)[0])
        pred_speed = float(SPEED_MODEL.predict(X_spd_scaled)[0])  # m/s
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Model prediction failed: {e}")

    hr_trend = float(latest.get("hr_trend_30", pd.Series([0.0])).iloc[0])
    fatigue_index = float(latest.get("fatigue_index", pd.Series([1.0])).iloc[0])
    speed_var = float(latest.get("speed_variation", pd.Series([0.0])).iloc[0])

    if fatigue_index > (1.20 if runner_level == 2 else (1.15 if runner_level == 1 else 1.25)) and percent_hr_max > (hr_slow_threshold - 0.05):
        rec = "Fatigue building up – slow down or shorten your stride"
    elif hr_trend > 10 and current_hr > pred_hr:
        rec = "Rising heart rate – consider reducing pace"
    elif speed_var > 0.5:
        rec = "Try to stabilize your speed for better endurance"
    else:
        if percent_hr_max < 0.5 and current_speed < pred_speed:
            rec = "Increase pace slightly"
        elif percent_hr_max > hr_slow_threshold:
            rec = "Slow down to reduce heart rate"
        elif abs(current_hr - pred_hr) > (10 if runner_level == 2 else (8 if runner_level == 1 else 12)):
            rec = "Reduce intensity – heart rate is too high" if current_hr > pred_hr else "Increase intensity – heart rate is too low"
        elif abs(current_speed - pred_speed) > (0.30 if runner_level == 2 else (0.25 if runner_level == 1 else 0.35)):
            rec = "You're running too fast – slow down a bit" if current_speed > pred_speed else "Try to reach your target pace"
        else:
            rec = "Maintain current pace"

    return {"pred_hr": round(pred_hr, 1), "pred_speed": round(pred_speed, 2), "recommendation": rec}

# ===================== FastAPI App =====================
app = FastAPI(title="Run Together Real-Time Recommendations", version="1.3.0")

@app.get("/health")
def health():
    status_ok = all([
        HEART_RATE_MODEL is not None,
        SPEED_MODEL is not None,
        SCALER_HR is not None,
        SCALER_SPEED is not None,
        bool(HR_FEATURES),
        bool(SPEED_FEATURES),
    ])
    return {
        "status": "ok" if status_ok else "degraded",
        "root_dir": str(ROOT_DIR),
        "models_dir": str(MODELS_DIR),
        "hr_model": HEART_RATE_MODEL is not None,
        "speed_model": SPEED_MODEL is not None,
        "scaler_hr": SCALER_HR is not None,
        "scaler_speed": SCALER_SPEED is not None,
        "hr_features_n": len(HR_FEATURES),
        "speed_features_n": len(SPEED_FEATURES),
        "speed_lead_steps": SPEED_LEAD_STEPS,
        "server_time": time.time(),
    }

@app.post("/tick", response_model=TickResponse)
def tick(req: TickRequest):
    if not req.samples:
        raise HTTPException(status_code=400, detail="No samples provided")

    # Get/create session
    with SESSIONS_LOCK:
        state = SESSIONS.get(req.session_id)
        if state is None:
            state = SessionState(req.session_id, runner_level=req.runner_level)
            SESSIONS[req.session_id] = state
        else:
            state.runner_level = req.runner_level

    # Normalize and append samples (ALWAYS feed m/s to the engine)
    norm_samples: List[Dict[str, Any]] = []
    for s in req.samples:
        ts = float(s.timestamp) if s.timestamp is not None else time.time()
        if s.enhanced_speed is not None:
            sp_mps = float(s.enhanced_speed)        # assume client provided m/s
        elif s.speed_mps is not None:
            sp_mps = float(s.speed_mps)
        elif s.speed_kmh is not None:
            sp_mps = float(s.speed_kmh) / 3.6
        else:
            sp_mps = np.nan

        norm_samples.append({
            "timestamp": ts,
            "heart_rate": float(s.heart_rate) if s.heart_rate is not None else np.nan,
            "enhanced_speed": sp_mps,  # m/s
            "cadence": float(s.cadence) if s.cadence is not None else np.nan,
            "power": float(s.power) if s.power is not None else np.nan,
            "distance_km": float(s.distance_km) if s.distance_km is not None else np.nan,
            "elevation_m": float(s.elevation_m) if s.elevation_m is not None else np.nan,
        })

    with SESSIONS_LOCK:
        state.add_samples(norm_samples)

    current_data = state.history_df.iloc[-1].to_dict()

    try:
        result = recommend(current_data=current_data, history_df=state.history_df, runner_level=state.runner_level)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Engine error: {e}")

    display = int(current_data["timestamp"]) % 5 == 0

    return TickResponse(
        session_id=req.session_id,
        display_recommendation=display,
        result=Recommendation(**result),
        server_time=time.time(),
    )

# ===================== Cleanup Thread =====================
def _cleanup_loop():
    while True:
        time.sleep(CLEANUP_PERIOD)
        now = time.time()
        with SESSIONS_LOCK:
            stale = [sid for sid, st in SESSIONS.items() if now - st.last_ts > SESSION_TTL]
            for sid in stale:
                SESSIONS.pop(sid, None)

threading.Thread(target=_cleanup_loop, daemon=True).start()
