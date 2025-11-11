// routes/match.js
const express = require('express');
const router = express.Router();
const User = require('../models/User');
const { findBestMatches } = require('../utils/matchUtils');

/**
 * POST /api/match
 * Body: { userId: Number }
 * Query (optional): ?minScore=60 (default 60)
 * Response: { matches: [...] }
 */
router.post('/', async (req, res) => {
  try {
    const userId = Number(req.body.userId);
    if (!userId) {
      return res.status(400).json({ error: 'userId is required' });
    }

    // Load current user and candidate pool as plain objects
    const currentUser = await User.findOne({ id: userId }).lean();
    if (!currentUser) {
      return res.status(404).json({ error: 'User not found' });
    }

    const allUsers = await User.find({ id: { $ne: userId } }).lean();

    // Allow overriding minScore from query (defaults to 60)
    const minScore = Number(req.query.minScore || 60);

    // Compute matches via utils
    const matches = findBestMatches(currentUser, allUsers, minScore);

    return res.json({ matches });
  } catch (err) {
    console.error('Matching Error:', err);
    return res.status(500).json({ error: 'Server error' });
  }
});

module.exports = router;
