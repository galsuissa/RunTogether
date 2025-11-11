const express = require("express");
const router = express.Router();
const RunningHistory = require("../models/RunningHistory");

router.get("/:userId", async (req, res) => {
  const userId = Number(req.params.userId);  // המרה למספר

  if (isNaN(userId)) {
    return res.status(400).json({ message: "ID אינו תקין" });
  }

  try {
    const runs = await RunningHistory.find({ id: userId }); // חיפוש לפי id (לא _id)
    res.json(runs);
  } catch (error) {
    console.error("❌ שגיאה בשליפת ריצות:", error);
    res.status(500).json({ message: "שגיאה בשרת" });
  }
});

router.post('/', async (req, res) => {
  try {
    console.log('history POST body:', req.body);  // בדיקה

    const {
      id,
      average_heart_rate,
      total_time_minutes,
      average_speed_kmh,
      total_distance_km,
      run_date,                      // ← חייב להגיע מהקליינט
    } = req.body;

    if (!id || !run_date) {
      return res.status(400).json({ error: 'id and run_date are required' });
    }

    const doc = await RunningHistory.create({
      id,
      average_heart_rate,
      total_time_minutes,
      average_speed_kmh,
      total_distance_km,
      run_date,                      // ← נשמר בשם הזה בדיוק
    });

    res.json(doc);
  } catch (err) {
    console.error('history POST error:', err);
    res.status(500).json({ error: 'Server error' });
  }
});

// Get all runs for a user (you already call this in ProfileActivity)
router.get('/:userId', async (req, res) => {
  try {
    const userId = Number(req.params.userId);
    const runs = await RunHistory.find({ id: userId }).sort({ createdAt: -1 }).lean();
    res.json(runs);
  } catch (e) {
    console.error('Get runs error:', e);
    res.status(500).json({ error: 'Server error' });
  }
});

module.exports = router;
