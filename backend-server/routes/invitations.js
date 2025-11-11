// routes/invitations.js
const express = require('express');
const router = express.Router();
const User = require('../models/User');
const RunInvitation = require('../models/RunInvitation');


// Create invitation or return existing pending
router.post('/', async (req, res) => {
  console.log('Received POST body:', req.body);
  const { senderId, receiverId, timestamp } = req.body;

  if (!senderId || !receiverId) {
    return res.status(400).json({ error: 'Missing senderId or receiverId' });
  }
  const inviteTimestamp = timestamp ? new Date(timestamp) : new Date();

  try {
    // 1) Return existing pending if already present
    let existing = await RunInvitation.findOne({
      senderId,
      receiverId,
      status: 'pending'
    });

    if (existing) {
      return res.json({ success: true, invitation: existing, existed: true });
    }

    // 2) Create new pending invitation
    const invitation = await RunInvitation.create({
      senderId,
      receiverId,
      timestamp: inviteTimestamp,
      status: 'pending'
    });

    console.log('Invitation saved:', invitation);
    return res.json({ success: true, invitation, existed: false });
  } catch (err) {
    // If unique index violated (race), fetch and return the existing one
    if (err.code === 11000) {
      const dupe = await RunInvitation.findOne({
        senderId,
        receiverId,
        status: 'pending'
      });
      if (dupe) {
        return res.json({ success: true, invitation: dupe, existed: true });
      }
    }
    console.error('Error saving invitation:', err);
    return res.status(500).json({ error: 'Server error' });
  }
});

// Get all pending invitations by sender
router.get('/pending', async (req, res) => {
  try {
    const senderId = Number(req.query.senderId);
    if (!senderId) {
      return res.status(400).json({ error: 'senderId is required' });
    }

    const docs = await RunInvitation
      .find({ senderId, status: 'pending' }, { receiverId: 1, timestamp: 1 })
      .sort({ createdAt: -1 })
      .lean();

    const out = docs.map(d => ({
      receiverId: d.receiverId,
      timestamp: d.timestamp
    }));

    return res.json(out);
  } catch (err) {
    console.error('Pending fetch error:', err);
    return res.status(500).json({ error: 'Server error' });
  }
});

// Get invitations I received
router.get('/:receiverId', async (req, res) => {
  try {
    const receiverId = Number(req.params.receiverId);
    if (!receiverId) {
      return res.status(400).json({ error: 'Invalid receiverId' });
    }

    const invitations = await RunInvitation.find({ receiverId, status: 'pending' })
      .sort({ createdAt: -1 })
      .lean();

    // עוטפים באובייקט עם מפתח invitations כדי לשמור על פורמט אחיד
    return res.json({ invitations });
  } catch (err) {
    console.error('Error fetching invitations:', err);
    return res.status(500).json({ error: 'Server error' });
  }
});

// PUT /api/invitations/status/:id
router.put('/status/:id', async (req, res) => {
  try {
    const invitationId = req.params.id;
    const { status } = req.body;

    if (!status) {
      return res.status(400).json({ error: 'Missing status' });
    }

    const invitation = await RunInvitation.findById(invitationId);
    if (!invitation) {
      return res.status(404).json({ error: 'Invitation not found' });
    }

    const prevStatus = invitation.status;

    // If no change -> return as-is (idempotency)
    if (prevStatus === status) {
      return res.json({ success: true, invitation, updated: false });
    }

    // Update status
    invitation.status = status;
    await invitation.save();

    // On first transition to 'accepted' -> increment partnersCount for both users
    if (status === 'accepted' && prevStatus !== 'accepted') {
      // Atomically increment partnersCount for sender and receiver
      await Promise.all([
        User.updateOne({ id: invitation.senderId },   { $inc: { partnersCount: 1 } }),
        User.updateOne({ id: invitation.receiverId }, { $inc: { partnersCount: 1 } }),
      ]);
    }

    return res.json({ success: true, invitation, updated: true });
  } catch (err) {
    console.error('Error updating invitation status:', err);
    return res.status(500).json({ error: 'Server error' });
  }
});


// GET /api/invitations/partners-count/:userId
// Returns the number of unique partners this user has accepted runs with (as sender or receiver).
router.get('/partners-count/:userId', async (req, res) => {
  try {
    const userId = Number(req.params.userId);
    if (!Number.isFinite(userId)) {
      return res.status(400).json({ error: 'Invalid userId' });
    }

    // Aggregate unique partner ids from accepted invitations
    const agg = await RunInvitation.aggregate([
      {
        $match: {
          status: 'accepted', // make sure your app updates to this exact string
          $or: [{ senderId: userId }, { receiverId: userId }]
        }
      },
      {
        // Compute partnerId = the "other side" of the invitation
        $project: {
          partnerId: {
            $cond: [{ $eq: ['$senderId', userId] }, '$receiverId', '$senderId']
          }
        }
      },
      { $group: { _id: null, partners: { $addToSet: '$partnerId' } } },
      { $project: { _id: 0, count: { $size: '$partners' }, partnerIds: '$partners' } }
    ]);

    const result = agg[0] || { count: 0, partnerIds: [] };
    return res.json(result); // { count: <number>, partnerIds: [ ... ] }
  } catch (err) {
    console.error('partners-count error:', err);
    return res.status(500).json({ error: 'Server error' });
  }
});






module.exports = router;