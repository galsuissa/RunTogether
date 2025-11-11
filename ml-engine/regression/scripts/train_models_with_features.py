#train_models_with_features.py
import os
from pathlib import Path
import json
import joblib
import numpy as np
import pandas as pd
from sklearn.linear_model import Ridge
from sklearn.metrics import mean_squared_error, r2_score
from sklearn.preprocessing import StandardScaler
import matplotlib.pyplot as plt

#Resolve project root so imports/paths work from anywhere
ROOT_DIR = Path(__file__).resolve().parents[1]  # .../RunTogether
DATA_DIR = ROOT_DIR / "data"
MODELS_DIR = ROOT_DIR / "models"

import sys
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

# Use the SAME feature function as inference (now leak-free variants exist)
from utils.features import add_features



# Configuration
# Predict speed at t+LEAD_STEPS (samples). Set to 0 to predict same-t speed.
SPEED_LEAD_STEPS = 5  # e.g., 5 seconds ahead if rows are 1 Hz; set 0 to disable lead.

HR_ALPHA = 1.0
SPEED_ALPHA = 1.0
RANDOM_STATE = 42



# Utilities
def print_metrics(true, pred, label: str):
    rmse = np.sqrt(mean_squared_error(true, pred))
    r2 = r2_score(true, pred)
    print(f"{label} - RMSE: {rmse:.3f}, R²: {r2:.4f}")


def plot_predictions(y_true, y_pred, label, save_path):
    """
    Scatter plot: Actual vs Predicted with y=x reference line.
    Saves to save_path and also shows the figure.
    """
    fig = plt.figure(figsize=(6, 6))
    plt.scatter(y_true, y_pred, alpha=0.6, edgecolor='k', linewidths=0.3)
    # reference y=x
    lo = float(min(np.min(y_true), np.min(y_pred)))
    hi = float(max(np.max(y_true), np.max(y_pred)))
    plt.plot([lo, hi], [lo, hi], linestyle='--')
    plt.xlabel(f"Actual {label}")
    plt.ylabel(f"Predicted {label}")
    plt.title(f"{label} — Prediction vs Actual")
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.savefig(save_path, dpi=150)
    plt.show()
    plt.close(fig)


def make_speed_lead(df: pd.DataFrame, lead_steps: int) -> pd.DataFrame:
    """
    If lead_steps > 0, create a lead target per run_id: enhanced_speed at t+lead_steps.
    Drop the last lead_steps rows of each run where the target becomes NaN.
    """
    if lead_steps <= 0:
        df["target_speed"] = df["enhanced_speed"]
        return df

    if "run_id" in df.columns:
        df["target_speed"] = (
            df.groupby("run_id")["enhanced_speed"]
              .shift(-lead_steps)
        )
        # drop trailing rows per run where lead target is none
        df = df.groupby("run_id", group_keys=False).apply(lambda g: g.iloc[:-lead_steps] if len(g) > lead_steps else g.iloc[0:0])
    else:
        df["target_speed"] = df["enhanced_speed"].shift(-lead_steps)
        df = df.iloc[:-lead_steps] if len(df) > lead_steps else df.iloc[0:0]

    return df


def main():
    #Load data
    train_path = DATA_DIR / "train.csv"
    val_path = DATA_DIR / "val.csv"
    train_df = pd.read_csv(train_path)
    val_df = pd.read_csv(val_path)

    #Feature engineering
    train_df = add_features(train_df)
    val_df = add_features(val_df)

    #Optional: define lead target for speed
    train_df = make_speed_lead(train_df, SPEED_LEAD_STEPS)
    val_df = make_speed_lead(val_df, SPEED_LEAD_STEPS)

    #Leak-free feature columns per target
    # Only use *_lag*, *_past*, exogenous same-t (cadence/power/progress_ratio), and past-only cumulative.
    hr_feature_cols = [
        # exogenous (same-t OK)
        "cadence", "power", "progress_ratio",
        # past-only cumulative
        "cumulative_power_past", "cumulative_hr_past",
        # lags
        "hr_lag_1", "hr_lag_5", "speed_lag_1", "speed_lag_5",
        # past MAs
        "hr_ma_5_past", "speed_ma_5_past",
        # past trends / fatigue
        "hr_trend_30_past", "speed_trend_30_past", "fatigue_index_past",
    ]

    speed_feature_cols = [
        # exogenous (same-t OK)
        "cadence", "power", "progress_ratio",
        # past-only cumulative
        "cumulative_power_past", "cumulative_hr_past",
        # lags
        "speed_lag_1", "speed_lag_5", "hr_lag_1", "hr_lag_5",
        # past MAs
        "speed_ma_5_past", "hr_ma_5_past",
        # past trends / fatigue
        "speed_trend_30_past", "hr_trend_30_past", "fatigue_index_past",
    ]

    #Targets
    HR_TARGET = "heart_rate"
    SPEED_TARGET = "target_speed"  # created by make_speed_lead(); equals enhanced_speed if lead=0

    #Drop rows with missing values in features or targets
    train_df = train_df.dropna(subset=hr_feature_cols + [HR_TARGET])
    val_df = val_df.dropna(subset=hr_feature_cols + [HR_TARGET])

    train_df = train_df.dropna(subset=speed_feature_cols + [SPEED_TARGET])
    val_df = val_df.dropna(subset=speed_feature_cols + [SPEED_TARGET])

    #Train/Val matrices
    X_train_hr = train_df[hr_feature_cols]
    y_train_hr = train_df[HR_TARGET]
    X_val_hr = val_df[hr_feature_cols]
    y_val_hr = val_df[HR_TARGET]

    X_train_speed = train_df[speed_feature_cols]
    y_train_speed = train_df[SPEED_TARGET]
    X_val_speed = val_df[speed_feature_cols]
    y_val_speed = val_df[SPEED_TARGET]

    #Scaling (per target feature set)
    hr_scaler = StandardScaler().fit(X_train_hr)
    X_train_hr_scaled = hr_scaler.transform(X_train_hr)
    X_val_hr_scaled = hr_scaler.transform(X_val_hr)

    speed_scaler = StandardScaler().fit(X_train_speed)
    X_train_speed_scaled = speed_scaler.transform(X_train_speed)
    X_val_speed_scaled = speed_scaler.transform(X_val_speed)

    # Models
    hr_model = Ridge(alpha=HR_ALPHA, random_state=RANDOM_STATE)
    hr_model.fit(X_train_hr_scaled, y_train_hr)
    y_val_hr_pred = hr_model.predict(X_val_hr_scaled)

    speed_model = Ridge(alpha=SPEED_ALPHA, random_state=RANDOM_STATE)
    speed_model.fit(X_train_speed_scaled, y_train_speed)
    y_val_speed_pred = speed_model.predict(X_val_speed_scaled)

    # Evaluation ----
    print("Validation Performance:")
    print_metrics(y_val_hr, y_val_hr_pred, "Heart Rate (BPM)")

    speed_label = "Speed (m/s)" if SPEED_LEAD_STEPS == 0 else f"Speed (m/s) @ t+{SPEED_LEAD_STEPS}"
    print_metrics(y_val_speed, y_val_speed_pred, speed_label)

    #Save models, scalers & metadata ----
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    joblib.dump(hr_model, MODELS_DIR / "heart_rate_model.pkl")
    joblib.dump(speed_model, MODELS_DIR / "speed_model.pkl")
    joblib.dump(hr_scaler, MODELS_DIR / "scaler_hr.pkl")
    joblib.dump(speed_scaler, MODELS_DIR / "scaler_speed.pkl")

    metadata = {
        "SPEED_LEAD_STEPS": SPEED_LEAD_STEPS,
        "hr_feature_cols": hr_feature_cols,
        "speed_feature_cols": speed_feature_cols,
        "hr_alpha": HR_ALPHA,
        "speed_alpha": SPEED_ALPHA,
    }
    with open(MODELS_DIR / "model_metadata.json", "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)

    print(f"\nModels and scalers saved to: {MODELS_DIR.resolve()}")

    # ---- Visualization: save & show prediction-quality plots ----
    plot_predictions(
        y_true=y_val_hr,
        y_pred=y_val_hr_pred,
        label="Heart Rate (BPM)",
        save_path=MODELS_DIR / "hr_pred_vs_actual.png"
    )
    plot_predictions(
        y_true=y_val_speed,
        y_pred=y_val_speed_pred,
        label=speed_label,
        save_path=MODELS_DIR / "speed_pred_vs_actual.png"
    )


if __name__ == "__main__":
    main()
