// models/User.js
const mongoose = require("mongoose");

const userSchema = new mongoose.Schema({
  id: { type: Number, unique: true, index: true }, // numeric user id used by the app
  fullName: String,
  age: Number,
  phone: String,
  city: String,
  street: String,
  gender: String,
  level: Number,            // 0=מתחיל, 1=מתקדם, 2=מקצועי
  email: { type: String, unique: true },
  password: String,       
  availability: [String],
  runsCount:     { type: Number, default: 0 },
  partnersCount: { type: Number, default: 0 },
}, { timestamps: true });

module.exports = mongoose.model("User", userSchema);
