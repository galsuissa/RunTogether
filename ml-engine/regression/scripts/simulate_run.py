# simulate_run.py
import sys
import time
from pathlib import Path
import pandas as pd

# Project root on sys.path
ROOT_DIR = Path(__file__).resolve().parents[1]  # .../RunTogether
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

#Your recommender: recommend(current_data, history_df) ----
from scripts.recommendation_engine import recommend

REQUIRED_COLS = ["heart_rate", "enhanced_speed", "cadence", "power"]
SLEEP_SEC = 1.0  # always sleep 1 real second per row


def _prepare_run_df(csv_path: Path) -> pd.DataFrame:
    df = pd.read_csv(csv_path)

    #Ensure required measurement columns exist
    for col in REQUIRED_COLS:
        if col not in df.columns:
            df[col] = 0.0

    #Create monotonic time column in seconds (not used for pacing; we always sleep 1s)
    if "time_elapsed" in df.columns:
        df["time_elapsed"] = pd.to_numeric(df["time_elapsed"], errors="coerce").fillna(0.0)
    elif "timestamp" in df.columns:
        ts = pd.to_datetime(df["timestamp"], errors="coerce")
        base = ts.iloc[0] if pd.notnull(ts.iloc[0]) else pd.Timestamp.now()
        df["time_elapsed"] = (ts - base).dt.total_seconds().fillna(0.0)
    else:
        df["time_elapsed"] = range(len(df))

    #Sort by time and add run_id if missing
    df = df.sort_values("time_elapsed").reset_index(drop=True)
    if "run_id" not in df.columns:
        df["run_id"] = 1

    return df


def simulate_realtime(df: pd.DataFrame):
    """Print one line per second: [idx] HR | Speed | Pred HR | Pred Speed | Recommendation"""
    print("\nStarting real-time simulation (1 line per second)...\n")
    try:
        for i in range(len(df)):
            history_df = df.iloc[: i + 1].copy()
            current = history_df.iloc[-1].to_dict()

            #Call your recommender
            res = recommend(current, history_df)

            #Extract values
            hr = float(current.get("heart_rate", 0.0) or 0.0)
            spd = float(current.get("enhanced_speed", 0.0) or 0.0)
            pred_hr = res.get("pred_hr", None)
            pred_spd = res.get("pred_speed", None)
            rec = res.get("recommendation", "")

            #Pre-format predicted values (avoid nested f-strings)
            pred_hr_str = "-" if pred_hr is None else f"{float(pred_hr):.1f}"
            pred_spd_str = "-" if pred_spd is None else f"{float(pred_spd):.2f}"

            #Pretty line exactly like your example
            line = (
                f"[{i:04}] HR: {hr:.0f} | Speed: {spd:.2f} | "
                f"Pred HR: {pred_hr_str} | Pred Speed: {pred_spd_str} | "
                f"Recommendation: {rec}"
            )
            print(line, flush=True)

            #Always wait one real second
            time.sleep(SLEEP_SEC)
    except KeyboardInterrupt:
        print("\nInterrupted. Exiting.")


if __name__ == "__main__":
    default = str((ROOT_DIR / "data" / "val.csv").resolve())
    try:
        path = input(f"Enter path to run CSV (default: {default}): ").strip() or default
    except EOFError:
        path = default

    csv_path = Path(path)
    if not csv_path.exists():
        print(f"File not found: {csv_path}", file=sys.stderr)
        sys.exit(2)

    df = _prepare_run_df(csv_path)
    simulate_realtime(df)
