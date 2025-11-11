# merge_run_files.py
import os
import glob
import pandas as pd
import numpy as np


def merge_and_split_runs(data_folder_path: str) -> pd.DataFrame:

    run_files = sorted(glob.glob(os.path.join(data_folder_path, "RUN_*.csv")))
    if len(run_files) != 53:
        print(f"Warning: Expected 53 runs but found {len(run_files)} files.")

    all_runs = []

    for i, file_path in enumerate(run_files):
        filename = os.path.basename(file_path)
        print(f"Reading file {i+1}/{len(run_files)}: {filename}")

        df = pd.read_csv(file_path)

        # Keep the session identity across concatenation
        df["run_id"] = i + 1
        df["file_name"] = filename

        # Build a relative time axis when timestamps exist
        if "timestamp" in df.columns:
            df["timestamp"] = pd.to_datetime(df["timestamp"], errors="coerce")
            df = df.sort_values("timestamp")
            start_time = df["timestamp"].iloc[0]
            df["time_elapsed"] = (df["timestamp"] - start_time).dt.total_seconds()

        # Lightweight, causal features for later modeling
        df = create_derived_features(df)

        # Drop common “Unnamed: *” columns leaked from CSV indices
        df = df.loc[:, ~df.columns.str.contains(r"^Unnamed")]

        all_runs.append(df)

    # Stack sessions
    master_df = pd.concat(all_runs, ignore_index=True)

    # Final pass to handle NaNs/infinities and enforce simple value constraints
    master_df = clean_master_dataset(master_df)

    # Persist the combined dataset
    master_csv_path = os.path.join(data_folder_path, "master_dataset.csv")
    master_df.to_csv(master_csv_path, index=False)
    print(f"Master dataset saved to: {master_csv_path}")

    # Split by run_id so sessions don’t leak across splits
    train_runs = master_df[master_df["run_id"] <= 39]
    val_runs = master_df[(master_df["run_id"] >= 40) & (master_df["run_id"] <= 46)]
    test_runs = master_df[master_df["run_id"] >= 47]

    # Write split files
    train_runs.to_csv(os.path.join(data_folder_path, "train.csv"), index=False)
    val_runs.to_csv(os.path.join(data_folder_path, "val.csv"), index=False)
    test_runs.to_csv(os.path.join(data_folder_path, "test.csv"), index=False)

    print(f"Train set: {train_runs['run_id'].nunique()} runs, {len(train_runs)} rows")
    print(f"Validation set: {val_runs['run_id'].nunique()} runs, {len(val_runs)} rows")
    print(f"Test set: {test_runs['run_id'].nunique()} runs, {len(test_runs)} rows")

    return master_df


def create_derived_features(df: pd.DataFrame) -> pd.DataFrame:
    """
    Add basic deltas and short rolling statistics.
    Each feature uses forward-safe operations (diff/rolling) that don’t peek ahead.
    """
    # Speed
    if "enhanced_speed" in df.columns:
        df["enhanced_speed"] = df["enhanced_speed"].fillna(0)
        df["speed_change"] = df["enhanced_speed"].diff().fillna(0)
        df["speed_std_5"] = df["enhanced_speed"].rolling(window=5, min_periods=1).std().fillna(0)
        df["speed_ma_5"] = df["enhanced_speed"].rolling(window=5, min_periods=1).mean().fillna(df["enhanced_speed"])

    # Heart rate
    if "heart_rate" in df.columns:
        # Median is robust to short gaps/outliers at session start
        df["heart_rate"] = df["heart_rate"].fillna(df["heart_rate"].median())
        df["hr_change"] = df["heart_rate"].diff().fillna(0)
        df["hr_ma_5"] = df["heart_rate"].rolling(window=5, min_periods=1).mean().fillna(df["heart_rate"])

    # Distance
    if "distance" in df.columns:
        df["distance_change"] = df["distance"].diff().fillna(0)

    # Cadence
    if "cadence" in df.columns:
        df["cadence"] = df["cadence"].fillna(0)
        df["cadence_change"] = df["cadence"].diff().fillna(0)

    # Power
    if "power" in df.columns:
        df["power"] = df["power"].fillna(0)
        df["power_change"] = df["power"].diff().fillna(0)

    return df


def clean_master_dataset(df: pd.DataFrame) -> pd.DataFrame:
    """
    Replace infinities, impute missing values, and clamp fields that must be non-negative.
    """
    numeric_columns = df.select_dtypes(include=[np.number]).columns
    df[numeric_columns] = df[numeric_columns].replace([np.inf, -np.inf], np.nan)

    for col in numeric_columns:
        if col in ["heart_rate"]:
            df[col] = df[col].fillna(df[col].median())
        elif col in ["enhanced_speed", "cadence", "power", "calories"]:
            df[col] = df[col].fillna(0)
        else:
            df[col] = df[col].fillna(df[col].mean())

    # Enforce basic physical constraints
    non_negative_cols = ["enhanced_speed", "cadence", "power", "calories", "distance"]
    for col in non_negative_cols:
        if col in df.columns:
            df[col] = df[col].clip(lower=0)

    return df


if __name__ == "__main__":
    data_folder = "../data"
    merge_and_split_runs(data_folder)
