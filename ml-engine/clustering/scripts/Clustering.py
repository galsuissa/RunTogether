# -*- coding: utf-8 -*-
"""
Cluster runners by per-run summary features.

What this script does:
  • Loads the per-file summaries from filtered_summary_runners.csv
  • Standardizes numeric features
  • Finds an "optimal" K (2..10) using the Silhouette Score
  • Fits KMeans with that K
  • Re-maps cluster labels so that higher average_speed ⇒ higher cluster index
  • Saves the labeled table to clustered_runners_auto_k.csv
"""

import sys
import os
import pandas as pd
from sklearn.preprocessing import StandardScaler
from sklearn.cluster import KMeans
from sklearn.metrics import silhouette_score
import matplotlib.pyplot as plt

# ---- Load data ----
file_path = "filtered_summary_runners.csv"
if not os.path.exists(file_path):
    print(f"⚠️ הקובץ לא נמצא: {file_path}")
    sys.exit(1)

df = pd.read_csv(file_path)

# Numeric columns to use for clustering
features = [
    "total_time",
    "average_heart_rate",
    "average_speed",
    "average_cadence",
    "total_distance",
]

# Validate that all required columns exist before proceeding
missing = [c for c in features if c not in df.columns]
if missing:
    print("⚠️ חסרות עמודות נדרשות בקובץ:", ", ".join(missing))
    sys.exit(1)

# Ensure numeric dtypes (coerce invalid values to NaN) and drop rows with NaNs
for col in features:
    df[col] = pd.to_numeric(df[col], errors="coerce")

clean = df.dropna(subset=features).copy()
if clean.empty:
    print("⚠️ אין רשומות תקינות לאחר ניקוי נתונים.")
    sys.exit(1)

# ---- Standardize features (mean=0, std=1) ----
scaler = StandardScaler()
scaled_data = scaler.fit_transform(clean[features])

# ---- Find optimal K via Silhouette Score (K in [2..10]) ----
silhouette_scores = []
K_range = range(2, 11)

for k in K_range:
    # n_init='auto' avoids deprecation warnings on newer sklearn versions
    km = KMeans(n_clusters=k, random_state=42, n_init="auto")
    labels = km.fit_predict(scaled_data)
    score = silhouette_score(scaled_data, labels)
    silhouette_scores.append(score)

# ---- Plot Silhouette vs. K ----
plt.figure(figsize=(8, 5))
plt.plot(list(K_range), silhouette_scores, marker="o")
plt.title("Choosing Optimal K using Silhouette Score")
plt.xlabel("Number of Clusters (K)")
plt.ylabel("Silhouette Score")
plt.grid(True)
plt.tight_layout()
plt.show()

# ---- Choose K with the highest Silhouette ----
optimal_k = list(K_range)[silhouette_scores.index(max(silhouette_scores))]
print(f"\nה-K שנבחר לפי Silhouette: {optimal_k}")

# ---- Fit KMeans with the chosen K and assign clusters ----
kmeans = KMeans(n_clusters=optimal_k, random_state=42, n_init="auto")
clean["Cluster"] = kmeans.fit_predict(scaled_data)

# ---- Re-map cluster indices so that cluster 0 is slowest and increases with average_speed ----
cluster_speed_mean = clean.groupby("Cluster")["average_speed"].mean().sort_values().index
cluster_mapping = {old: new for new, old in enumerate(cluster_speed_mean)}
clean["Cluster"] = clean["Cluster"].map(cluster_mapping)

# Merge back to original df (keep rows that were dropped due to NaNs, if any)
df = df.merge(clean[["Cluster"] + features], on=features, how="left")

# ---- Save result ----
out_path = "clustered_runners_auto_k.csv"
df.to_csv(out_path, index=False)
print("\nהטבלה נשמרה בשם clustered_runners_auto_k.csv")
