// routes/login.js
const express = require("express");
const bcrypt = require("bcrypt"); // הוספת bcrypt
const router = express.Router();
const User = require("../models/User");

// POST /api/login
router.post("/", async (req, res) => {
  const { email, password } = req.body;

  try {
    const user = await User.findOne({ email });
    
    if (!user) {
      return res.status(404).json({ success: false, message: "משתמש לא קיים במערכת" });
    }

    // השוואת סיסמה שהוזנה מול הסיסמה המוצפנת שנשמרה
    const isMatch = await bcrypt.compare(password, user.password);
    if (!isMatch) {
      return res.status(401).json({ success: false, message: "סיסמה שגויה" });
    }

    res.status(200).json({
      success: true,
      message: "התחברת בהצלחה",
      userId: user.id,
    });

  } catch (err) {
    console.error("שגיאה בכניסה:", err);
    res.status(500).json({ success: false, message: "שגיאה בשרת" });
  }
});

module.exports = router;
