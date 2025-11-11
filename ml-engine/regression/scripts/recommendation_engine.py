#recommendation_engine.py
import numpy as np
import pandas as pd
import joblib
import sys
from pathlib import Path
import json

# Resolve project root so imports/paths work from anywhere
ROOT_DIR = Path(__file__).resolve().parents[1]
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

MODELS_DIR = ROOT_DIR / "models"

# Shared feature engineering
from utils.features import add_features


# Load models, scalers, and (optionally) metadata
heart_rate_model = joblib.load(MODELS_DIR / "heart_rate_model.pkl")
speed_model = joblib.load(MODELS_DIR / "speed_model.pkl")

# Use separate scalers (as saved by the new training script)
hr_scaler = joblib.load(MODELS_DIR / "scaler_hr.pkl")
speed_scaler = joblib.load(MODELS_DIR / "scaler_speed.pkl")

# Default (legacy) joint feature list — used if metadata is missing
_default_feature_cols = [
    # base
    "enhanced_speed", "cadence", "power",
    # deltas
    "speed_change", "hr_change", "cadence_change", "power_change",
    # rolling (5)
    "speed_ma_5", "hr_ma_5", "speed_std_5",
    # patterns (~30/60)
    "hr_trend_30", "speed_trend_30", "fatigue_index", "speed_variation",
    # cumulative & context
    "cumulative_power", "cumulative_hr", "progress_ratio",
]

# Try to read per-task feature lists from metadata (preferred)
meta_path = MODELS_DIR / "model_metadata.json"
if meta_path.exists():
    with open(meta_path, "r", encoding="utf-8") as f:
        _meta = json.load(f)
    hr_feature_cols = _meta.get("hr_feature_cols", _default_feature_cols)
    speed_feature_cols = _meta.get("speed_feature_cols", _default_feature_cols)
    SPEED_LEAD_STEPS = int(_meta.get("SPEED_LEAD_STEPS", 0))
else:
    hr_feature_cols = _default_feature_cols
    speed_feature_cols = _default_feature_cols
    SPEED_LEAD_STEPS = 0  # unknown -> treat as same-t


def recommend(current_data, history_df):
    """
    Generate predicted HR/speed and a real-time recommendation.

    Two-stage flow:
      1) Warm-up (<30 samples): conservative rules using current HR/speed only.
      2) Full mode: add_features -> scale with task-specific scaler -> predict HR & Speed,
         then apply pattern-aware checks before gap-based fallback rules.

    Returns: dict(pred_hr, pred_speed, recommendation)
    """
    current_hr = float(current_data.get("heart_rate", 0.0) or 0.0)
    current_speed = float(current_data.get("enhanced_speed", 0.0) or 0.0)
    percent_hr_max = current_hr / 190.0  # TODO: personalize if hr_max available

    #Stage 1: Warm-up (<30 rows) ----
    if len(history_df) < 30:
        if percent_hr_max > 0.85:
            rec = "You're starting too fast – slow down to warm up properly"
        elif percent_hr_max < 0.50 and current_speed > 7.0:
            rec = "Consider slowing down a bit for a smoother warm-up"
        elif percent_hr_max < 0.40 and current_speed < 4.0:
            rec = "Try to gradually increase your pace"
        else:
            rec = "Warm-up phase – maintain steady pace"
        return {"pred_hr": None, "pred_speed": None, "recommendation": rec}

    #Stage 2: Features + predictions ----
    hist_feat = add_features(history_df.copy())
    latest = hist_feat.iloc[[-1]].copy()

    #Ensure all required columns exist; fill missing with 0.0 (robust to feature changes)
    for col in hr_feature_cols:
        if col not in latest.columns:
            latest[col] = 0.0
    for col in speed_feature_cols:
        if col not in latest.columns:
            latest[col] = 0.0

    #Transform separately per task
    X_hr = hr_scaler.transform(latest[hr_feature_cols])
    X_spd = speed_scaler.transform(latest[speed_feature_cols])

    pred_hr = float(heart_rate_model.predict(X_hr)[0])
    pred_speed = float(speed_model.predict(X_spd)[0])

    #Stage 3: Pattern-aware overrides (use past-only features if available) ----
    # Prefer *_past features; fallback to legacy same-t names if needed.
    def _get(name_primary, name_fallback, default=0.0):
        if name_primary in latest.columns:
            return float(latest[name_primary].iloc[0])
        if name_fallback in latest.columns:
            return float(latest[name_fallback].iloc[0])
        return float(default)

    hr_trend = _get("hr_trend_30_past", "hr_trend_30", 0.0)
    fatigue_index = _get("fatigue_index_past", "fatigue_index", 1.0)
    speed_var = _get("speed_variation", "speed_variation", 0.0)

    #Conservative safety checks first
    if fatigue_index > 1.20 and percent_hr_max > 0.80:
        rec = "Fatigue building up – slow down or shorten your stride"
    elif hr_trend > 0.05 and current_hr > pred_hr:
        rec = "Rising heart rate – consider reducing pace"
    elif speed_var > 0.35:
        rec = "Try to stabilize your speed for better endurance"
    else:
        #Stage 4: Instant rules from prediction gaps ----
        if percent_hr_max < 0.50 and current_speed < pred_speed:
            rec = "Increase pace slightly"
        elif percent_hr_max > 0.85:
            rec = "Slow down to reduce heart rate"
        elif abs(current_hr - pred_hr) > 10.0:
            rec = "Reduce intensity – heart rate is too high" if current_hr > pred_hr \
                  else "Increase intensity – heart rate is too low"
        elif abs(current_speed - pred_speed) > 0.30:
            rec = "You're running too fast – slow down a bit" if current_speed > pred_speed \
                  else "Try to reach your target pace"
        else:
            rec = "Maintain current pace"

    return {
        "pred_hr": round(pred_hr, 1),
        "pred_speed": round(pred_speed, 2),
        "recommendation": rec
    }
