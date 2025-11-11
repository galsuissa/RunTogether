// models/RunInvitation.js
const mongoose = require('mongoose');

const runInvitationSchema = new mongoose.Schema(
  {
    senderId: { type: Number, required: true, index: true },
    receiverId: { type: Number, required: true, index: true },
    timestamp: { type: Date, required: true }, // when the run is scheduled
    status: {
      type: String,
      enum: ['pending', 'accepted', 'declined', 'cancelled', 'expired'],
      default: 'pending',
      index: true
    }
  },
  { timestamps: true, collection: 'run_invitations' }
);

// Avoid duplicate active invitations for the same pair
runInvitationSchema.index(
  { senderId: 1, receiverId: 1, status: 1 },
  { unique: true, partialFilterExpression: { status: 'pending' } }
);

// Helpful secondary index for pending queries by sender
runInvitationSchema.index({ senderId: 1, status: 1, createdAt: -1 });

module.exports =
  mongoose.models.RunInvitation ||
  mongoose.model('RunInvitation', runInvitationSchema);
