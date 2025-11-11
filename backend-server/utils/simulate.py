# simulate.py
import pandas as pd
import joblib
import sys
import json

from train_with_pattern_features import (
    preprocess_data,
    create_run_based_split,
    get_recommended_speed,
    create_advanced_features_with_patterns
)

run_id = sys.argv[1]

# Load models
hr_model_data = joblib.load("models/hr_model_pattern_enhanced.pkl")
speed_model_data = joblib.load("models/speed_model_pattern_enhanced.pkl")
hr_model = hr_model_data['model']
hr_scaler = hr_model_data['scaler']
hr_features = hr_model_data['features']
speed_model = speed_model_data['model']
speed_scaler = speed_model_data['scaler']
speed_features = speed_model_data['features']

# Load and process data
df = pd.read_csv("master_dataset.csv")
df = preprocess_data(df)
df = create_run_based_split(df)
df['recommended_speed'] = df.apply(get_recommended_speed, axis=1)
df = create_advanced_features_with_patterns(df)
run_df = df[df['run_id'] == run_id].reset_index(drop=True)

if run_df.empty:
    print(json.dumps({"error": "run_id not found"}))
    sys.exit()

predictions = []
for _, row in run_df.iterrows():
    if row[hr_features].isnull().any() or row[speed_features].isnull().any():
        continue
    X_hr = row[hr_features].values.reshape(1, -1)
    X_speed = row[speed_features].values.reshape(1, -1)

    predicted_hr = hr_model.predict(hr_scaler.transform(X_hr))[0]
    predicted_speed = speed_model.predict(speed_scaler.transform(X_speed))[0]
    actual_speed = row['enhanced_speed']

    ratio = actual_speed / (predicted_speed + 1e-3)
    if 0.97 <= ratio <= 1.03:
        recommendation = "Maintain current pace"
    elif ratio < 0.97:
        recommendation = "Speed up"
    else:
        recommendation = "Slow down"

    predictions.append({
        "time_elapsed": int(row["time_elapsed"]),
        "actual_hr": float(row["heart_rate"]),
        "predicted_hr": float(predicted_hr),
        "actual_speed": float(actual_speed),
        "predicted_speed": float(predicted_speed),
        "recommendation": recommendation
    })

print(json.dumps({"run_id": run_id, "predictions": predictions}))
