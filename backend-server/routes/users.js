const express = require('express');
const bcrypt = require('bcrypt');
const router = express.Router();
const User = require('../models/User');

// GET /api/users/:id -> get profile
router.get('/:id', async (req, res) => {
  try {
    const id = Number(req.params.id);
    console.log("GET /api/users/:id => looking for:", id);

    if (!Number.isFinite(id)) {
      return res.status(400).json({ success: false, message: 'Invalid id' });
    }

    const user = await User.findOne({ id })
      .select('id fullName age phone city street gender level availability email runsCount partnersCount');

    if (!user) {
      return res.status(404).json({ success: false, message: 'User not found' });
    }

    res.json(user);
  } catch (e) {
    console.error('GET /users/:id error', e);
    res.status(500).json({ success: false, message: 'Server error' });
  }
});



// PUT /api/users/:id -> update profile
router.put('/:id', async (req, res) => {
  try {
    const id = Number(req.params.id);
    console.log("PUT /api/users/:id => updating:", id, "with data:", req.body);

    if (!Number.isFinite(id)) {
      return res.status(400).json({ success: false, message: 'Invalid id' });
    }

    const allowed = [
      'fullName', 'age', 'phone', 'city', 'street', 'gender', 'level', 'availability', 'password'
    ];
    const update = {};
    for (const k of allowed) {
      if (k in req.body) update[k] = req.body[k];
    }

    // Validation
    if (update.age != null) {
      const a = Number(update.age);
      if (!Number.isFinite(a) || a <= 0 || a > 120) {
        return res.status(400).json({ success: false, message: 'Invalid age' });
      }
      update.age = a;
    }
    if (update.level != null && ![0, 1, 2].includes(Number(update.level))) {
      return res.status(400).json({ success: false, message: 'Invalid level' });
    }
    if (update.availability && !Array.isArray(update.availability)) {
      return res.status(400).json({ success: false, message: 'availability must be an array of strings' });
    }

    // Password hash if provided
    if (update.password) {
      if (update.password.length < 6) {
        return res.status(400).json({ success: false, message: 'Password must be at least 6 characters' });
      }
      update.password = await bcrypt.hash(update.password, 10);
    }

    const user = await User.findOneAndUpdate(
      { id },
      { $set: update },
      { new: true, runValidators: true }
    ).select('id fullName age phone city street gender level availability email runsCount partnersCount');

    if (!user) {
      return res.status(404).json({ success: false, message: 'User not found' });
    }

    res.json(user);
  } catch (e) {
    console.error('PUT /users/:id error', e);
    res.status(500).json({ success: false, message: 'Server error' });
  }


});

// PUT /api/users/:id/runsCount  -> increment by delta (default 1)
router.put('/:id/runsCount', async (req, res) => {
  try {
    const id = Number(req.params.id);
    const delta = Number(req.body?.delta ?? 1);

    if (!id) return res.status(400).json({ error: 'Invalid user id' });

    const updated = await User.findOneAndUpdate(
      { id },
      { $inc: { runsCount: delta } },
      { new: true }
    ).lean();

    if (!updated) return res.status(404).json({ error: 'User not found' });

    // return only what you need
    res.json({ id: updated.id, runsCount: updated.runsCount });
  } catch (err) {
    console.error('increment runsCount error:', err);
    res.status(500).json({ error: 'Server error' });
  }
});


module.exports = router;
