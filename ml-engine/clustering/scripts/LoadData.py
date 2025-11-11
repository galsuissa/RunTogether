# -*- coding: utf-8 -*-
"""
Summarize runner files: per-file averages for heart rate, speed, cadence, and total distance.
"""

import os
from pathlib import Path
import pandas as pd

# Root folder containing the CSV files (adjust if needed)
DATA_DIR = Path(os.getcwd()) / "athlete_hr_predict" / "fit_file_csv"

# Columns that must exist to compute the summary
REQUIRED_COLS = {"heart_rate", "enhanced_speed", "cadence", "distance"}

def calculate_runner_statistics(df: pd.DataFrame, file_name: str) -> pd.Series:
    # Ensure numeric types (coerce invalid values to NaN so .mean()/.max() work)
    for col in ["heart_rate", "enhanced_speed", "cadence", "distance"]:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    summary = {
        "file_name": file_name,
        "total_time": int(len(df)),  # seconds, if 1 row per second
        "average_heart_rate": df["heart_rate"].mean(),
        "average_speed": df["enhanced_speed"].mean(),
        "average_cadence": df["cadence"].mean(),
        "total_distance": df["distance"].max(),  # cumulative distance (same units as source)
    }
    return pd.Series(summary)

def main():
    summaries = []

    if not DATA_DIR.exists():
        print("⚠️ התיקייה לא נמצאה:", str(DATA_DIR))
        return

    for file in os.listdir(DATA_DIR):
        if not file.endswith(".csv"):
            continue

        file_path = DATA_DIR / file

        try:
            df = pd.read_csv(file_path)
        except Exception as e:
            print(f"⚠️ שגיאה בטעינת הקובץ {file}: {e}")
            continue

        # Validate required columns before computing stats
        missing = REQUIRED_COLS.difference(df.columns)
        if missing:
            print(f"⚠️ הקובץ {file} חסר עמודות נדרשות: {', '.join(missing)} — מדלג")
            continue

        summary = calculate_runner_statistics(df, file)
        summaries.append(summary)

    # Build a DataFrame from all per-file summaries
    summary_df = pd.DataFrame(summaries)

    # Keep only relevant columns (order preserved)
    relevant_columns = [
        "file_name", "total_time", "average_heart_rate",
        "average_speed", "average_cadence", "total_distance",
    ]
    filtered_df = summary_df[relevant_columns] if not summary_df.empty else summary_df

    # Console preview
    print("\nטבלת הסיכום לאחר הסרת עמודות לא רלוונטיות:")
    print(filtered_df.head())

    # Save to CSV in the current working directory
    out_path = Path("filtered_summary_runners.csv")
    filtered_df.to_csv(out_path, index=False)
    print(f"\nהקובץ נשמר בשם {out_path.name}")
