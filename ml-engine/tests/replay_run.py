import time
import requests
import pandas as pd

CSV_PATH = r"C:\path\to\RUN_001.csv"   # Update this path
API = "http://localhost:8000/tick"
SESSION = "replay-001"
RUNNER_LEVEL = 2       # 1=Beginner, 2=Intermediate, 3=Advanced
REALTIME = True        # True = wait ~1s per row, False = fast replay

# Map your CSV columns to API fields
COLS = {
    "timestamp": "timestamp",
    "heart_rate": "heart_rate",
    "enhanced_speed": "enhanced_speed",  # or "speed_kmh"/"speed_mps" if needed
    "cadence": "cadence",
    "power": "power",
}

df = pd.read_csv(CSV_PATH)

def get(row, name, default=None):
    col = COLS.get(name, name)
    return float(row[col]) if col in df.columns and pd.notna(row[col]) else default

t0 = None
for i, row in df.iterrows():
    ts = get(row, "timestamp", None)
    if ts is None:
        ts = time.time()
    elif REALTIME:
        if t0 is None:
            t0 = ts
            start = time.time()
        else:
            elapsed_real = time.time() - start
            elapsed_trace = ts - t0
            delay = elapsed_trace - elapsed_real
            if delay > 0:
                time.sleep(min(delay, 1.0))

    sample = {
        "timestamp": ts,
        "heart_rate": get(row, "heart_rate", 0),
        "enhanced_speed": get(row, "enhanced_speed", None),
        "cadence": get(row, "cadence", 0),
        "power": get(row, "power", 0),
    }

    payload = {
        "session_id": SESSION,
        "runner_level": RUNNER_LEVEL,
        "samples": [sample]
    }
    r = requests.post(API, json=payload, timeout=10)
    r.raise_for_status()
    out = r.json()
    res = out["result"]
    print(f"[{i:04d}] HR:{sample['heart_rate']:.0f} | v:{(sample['enhanced_speed'] or 0):.2f} "
          f"| predHR:{res['pred_hr']} predV:{res['pred_speed']} | {res['recommendation']}")
