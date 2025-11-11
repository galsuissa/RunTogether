#features.py
import pandas as pd
import numpy as np


def _rolling_slope(x: pd.Series) -> float:
    idx = np.arange(len(x))
    # polyfit can fail on NaNs; assume x has no NaNs when called
    return np.polyfit(idx, x, 1)[0]


def add_features(df: pd.DataFrame, hr_max: int = 190) -> pd.DataFrame:
    """
    Enrich a running session DataFrame with both classic and *past-only* (causal) features.

    Design goals:
    - Preserve existing column names used elsewhere (backward compatibility).
    - Add *_lagK / *_past variants for leak-free training and inference.
    - Compute per-run to avoid window/shift bleeding across runs.

    Assumptions:
    - Rows are in chronological order within each run.
    - If 'run_id' is missing, the whole DataFrame is treated as a single run.
    """
    df = df.copy()

    # --- Basic safety: ensure required columns exist ---
    required = ['heart_rate', 'enhanced_speed', 'cadence', 'power']
    for col in required:
        if col not in df.columns:
            df[col] = np.nan

    # --- Grouping helper (per run) ---
    has_run = 'run_id' in df.columns
    gkey = 'run_id' if has_run else None

    def gseries(col: str) -> pd.core.groupby.generic.SeriesGroupBy | pd.Series:
        return df.groupby(gkey)[col] if has_run else df[col]

    # --- Ensure chronological order per run if timestamp exists ---
    if 'timestamp' in df.columns:
        if has_run:
            df = df.sort_values(['run_id', 'timestamp']).reset_index(drop=True)
        else:
            df = df.sort_values('timestamp').reset_index(drop=True)

    #Base deltas (same-t; keep for backward compatibility)
    df['speed_change']   = gseries('enhanced_speed').diff().fillna(0) if has_run else df['enhanced_speed'].diff().fillna(0)
    df['hr_change']      = gseries('heart_rate').diff().fillna(0)     if has_run else df['heart_rate'].diff().fillna(0)
    df['cadence_change'] = gseries('cadence').diff().fillna(0)        if has_run else df['cadence'].diff().fillna(0)
    df['power_change']   = gseries('power').diff().fillna(0)          if has_run else df['power'].diff().fillna(0)

    # Classic short window stats (same-t; legacy names)
    # NOTE: these include the current sample -> may cause leakage if used to predict same-t target.
    if has_run:
        df['speed_ma_5'] = gseries('enhanced_speed').rolling(5, min_periods=1).mean().reset_index(level=0, drop=True)
        df['speed_std_5'] = gseries('enhanced_speed').rolling(5, min_periods=1).std().reset_index(level=0, drop=True)
        df['hr_ma_5'] = gseries('heart_rate').rolling(5, min_periods=1).mean().reset_index(level=0, drop=True)
    else:
        df['speed_ma_5'] = df['enhanced_speed'].rolling(5, min_periods=1).mean()
        df['speed_std_5'] = df['enhanced_speed'].rolling(5, min_periods=1).std()
        df['hr_ma_5'] = df['heart_rate'].rolling(5, min_periods=1).mean()

    #Past-only (causal) lags
    df['speed_lag_1']   = (gseries('enhanced_speed').shift(1) if has_run else df['enhanced_speed'].shift(1))
    df['speed_lag_5']   = (gseries('enhanced_speed').shift(5) if has_run else df['enhanced_speed'].shift(5))
    df['hr_lag_1']      = (gseries('heart_rate').shift(1)     if has_run else df['heart_rate'].shift(1))
    df['hr_lag_5']      = (gseries('heart_rate').shift(5)     if has_run else df['heart_rate'].shift(5))
    df['cadence_lag_1'] = (gseries('cadence').shift(1)        if has_run else df['cadence'].shift(1))
    df['power_lag_1']   = (gseries('power').shift(1)          if has_run else df['power'].shift(1))

    #Past-only moving averages (window ends at t-1)
    if has_run:
        s_shift = gseries('enhanced_speed').shift(1)
        h_shift = gseries('heart_rate').shift(1)
        df['speed_ma_5_past'] = s_shift.rolling(5, min_periods=5).mean().reset_index(level=0, drop=True)
        df['hr_ma_5_past']    = h_shift.rolling(5, min_periods=5).mean().reset_index(level=0, drop=True)
    else:
        df['speed_ma_5_past'] = df['enhanced_speed'].shift(1).rolling(5, min_periods=5).mean()
        df['hr_ma_5_past']    = df['heart_rate'].shift(1).rolling(5, min_periods=5).mean()

    #Past-only trend features (rolling slope over last 30 samples)
    if has_run:
        s_shift = gseries('enhanced_speed').shift(1)
        h_shift = gseries('heart_rate').shift(1)
        speed_trend = s_shift.groupby(df['run_id']).rolling(30, min_periods=30).apply(_rolling_slope, raw=False)
        hr_trend    = h_shift.groupby(df['run_id']).rolling(30, min_periods=30).apply(_rolling_slope, raw=False)
        df['speed_trend_30_past'] = speed_trend.reset_index(level=0, drop=True)
        df['hr_trend_30_past']    = hr_trend.reset_index(level=0, drop=True)
    else:
        df['speed_trend_30_past'] = df['enhanced_speed'].shift(1).rolling(30, min_periods=30).apply(_rolling_slope, raw=False)
        df['hr_trend_30_past']    = df['heart_rate'].shift(1).rolling(30, min_periods=30).apply(_rolling_slope, raw=False)

    #Legacy trend names (same-t) — keep for compatibility
    if has_run:
        speed_trend_now = gseries('enhanced_speed').rolling(30, min_periods=30).apply(_rolling_slope, raw=False)
        hr_trend_now    = gseries('heart_rate').rolling(30, min_periods=30).apply(_rolling_slope, raw=False)
        df['speed_trend_30'] = speed_trend_now.reset_index(level=0, drop=True)
        df['hr_trend_30']    = hr_trend_now.reset_index(level=0, drop=True)
    else:
        df['speed_trend_30'] = df['enhanced_speed'].rolling(30, min_periods=30).apply(_rolling_slope, raw=False)
        df['hr_trend_30']    = df['heart_rate'].rolling(30, min_periods=30).apply(_rolling_slope, raw=False)

    # Fatigue / variability
    # Legacy (same-t) indices:
    if has_run:
        recent_hr = gseries('heart_rate').rolling(30, min_periods=1).mean().reset_index(level=0, drop=True)
        longer_hr = gseries('heart_rate').rolling(60, min_periods=1).mean().reset_index(level=0, drop=True)
        df['fatigue_index'] = recent_hr / (longer_hr + 1e-6)

        df['speed_variation'] = gseries('enhanced_speed').rolling(30, min_periods=1).std().reset_index(level=0, drop=True).fillna(0)
    else:
        recent_hr = df['heart_rate'].rolling(30, min_periods=1).mean()
        longer_hr = df['heart_rate'].rolling(60, min_periods=1).mean()
        df['fatigue_index'] = recent_hr / (longer_hr + 1e-6)
        df['speed_variation'] = df['enhanced_speed'].rolling(30, min_periods=1).std().fillna(0)

    # Past-only (causal) fatigue index:
    if has_run:
        recent_hr_p = gseries('heart_rate').shift(1).rolling(30, min_periods=30).mean()
        longer_hr_p = gseries('heart_rate').shift(1).rolling(60, min_periods=60).mean()
        df['fatigue_index_past'] = (recent_hr_p / (longer_hr_p + 1e-6)).reset_index(level=0, drop=True)
    else:
        recent_hr_p = df['heart_rate'].shift(1).rolling(30, min_periods=30).mean()
        longer_hr_p = df['heart_rate'].shift(1).rolling(60, min_periods=60).mean()
        df['fatigue_index_past'] = recent_hr_p / (longer_hr_p + 1e-6)

    #Cumulative load (past-only & legacy)
    if has_run:
        df['cumulative_power_past'] = gseries('power').shift(1).cumsum().reset_index(level=0, drop=True)
        df['cumulative_hr_past']    = gseries('heart_rate').shift(1).cumsum().reset_index(level=0, drop=True)
        df['cumulative_power']      = gseries('power').cumsum().reset_index(level=0, drop=True)
        df['cumulative_hr']         = gseries('heart_rate').cumsum().reset_index(level=0, drop=True)
    else:
        df['cumulative_power_past'] = df['power'].shift(1).cumsum()
        df['cumulative_hr_past']    = df['heart_rate'].shift(1).cumsum()
        df['cumulative_power']      = df['power'].cumsum()
        df['cumulative_hr']         = df['heart_rate'].cumsum()

    #Progress ratio (0..1 within run)
    if has_run:
        pos = df.groupby('run_id').cumcount()
        cnt = df.groupby('run_id')['enhanced_speed'].transform('size')
        df['progress_ratio'] = np.where(cnt > 1, pos / (cnt - 1), 0.0)
    else:
        n = len(df)
        df['progress_ratio'] = (np.arange(n) / (n - 1)) if n > 1 else 0.0

    #Convenience previous-sample aliases (legacy names)
    df['speed_prev']   = df['speed_lag_1']
    df['hr_prev']      = df['hr_lag_1']
    df['cadence_prev'] = df['cadence_lag_1']
    df['power_prev']   = df['power_lag_1']

    #Final cleanup
    df.replace([np.inf, -np.inf], np.nan, inplace=True)
    # Keep historical behavior: fill remaining NaNs with 0 (first few rows mostly)
    df.fillna(0, inplace=True)

    return df
