import pandas as pd
import numpy as np

def compute_dynamic_features(df):
    df = df.copy()
    df['speed_change'] = df['enhanced_speed'].diff().fillna(0)
    df['hr_change'] = df['heart_rate'].diff().fillna(0)
    df['power_change'] = df['power'].diff().fillna(0)
    df['cadence_change'] = df['cadence'].diff().fillna(0)
    df['hr_ma_5'] = df['heart_rate'].rolling(window=5, min_periods=1).mean()
    df['fatigue_index'] = df['heart_rate'] / (df['power'] + 1)
    df['cumulative_load'] = df['power'].cumsum()
    df['trend_hr'] = df['heart_rate'].rolling(window=10, min_periods=1).apply(lambda x: np.polyfit(range(len(x)), x, 1)[0])
    df['intensity'] = df['enhanced_speed'] / df['enhanced_speed'].max()
    df['progress'] = np.linspace(0, 1, len(df))
    return df


def preprocess_run(df):
    df = df.replace([np.inf, -np.inf], np.nan).dropna(subset=['heart_rate', 'enhanced_speed'])
    df = compute_dynamic_features(df)
    return df