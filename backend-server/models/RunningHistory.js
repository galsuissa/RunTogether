const mongoose = require('mongoose');

const runningHistorySchema = new mongoose.Schema({
  id: { type: Number, required: true, index: true }, // user id
  average_heart_rate: { type: Number, default: 0 },
  total_time_minutes: { type: Number, default: 0 },
  average_speed_kmh: { type: Number, default: 0 },
  total_distance_km: { type: Number, default: 0 },
  run_date: { type: String, required: true }, // לדוגמה: "09.06.2025"
}, { collection: 'running_history', timestamps: true });

module.exports = mongoose.models.RunningHistory
  || mongoose.model('RunningHistory', runningHistorySchema);
