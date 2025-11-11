const express = require("express");
const bcrypt = require("bcrypt"); // הוספת bcrypt
const router = express.Router();
const User = require("../models/User");

// POST /api/register
router.post("/", async (req, res) => {
  const { fullName, age, phone, city, street, gender, level, email, password, availability } = req.body;

  console.log("📥 נתונים שהתקבלו בהרשמה:", req.body);

  try {
    // בדיקה אם המשתמש כבר קיים
    const existingUser = await User.findOne({ email });
    if (existingUser) {
      console.log("⚠️ משתמש כבר קיים:", email);
      return res.status(400).json({ success: false, message: "משתמש כבר קיים" });
    }

    // שליפת המשתמש עם ה-id הגבוה ביותר
    const lastUser = await User.findOne().sort({ id: -1 });
    const nextId = lastUser ? lastUser.id + 1 : 0;

    // הצפנת הסיסמה
    const hashedPassword = await bcrypt.hash(password, 10); // 10 זה מספר הסיבובים (salt rounds)

    const newUser = new User({
      id: nextId,
      fullName,
      age,
      phone,
      city,
      street,
      gender,
      level,
      email,
      password: hashedPassword, // שמירה מוצפנת
      availability
    });

    await newUser.save();

    console.log("✅ נרשם בהצלחה:", email);
    res.status(201).json({ success: true, message: "נרשמת בהצלחה", id: nextId });

  } catch (err) {
    console.error("❌ שגיאה בהרשמה:", err);
    res.status(500).json({ success: false, message: "שגיאה בשרת", error: err.message });
  }
});

module.exports = router;
