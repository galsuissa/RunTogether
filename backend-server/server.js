const express = require("express");
const mongoose = require("mongoose");
const cors = require("cors");
require("dotenv").config();

const app = express();
const PORT = process.env.PORT || 3000;

// Middlewares
app.use(cors());
app.use(express.json());

// Connect MongoDB
mongoose.connect(process.env.MONGO_URI)
  .then(() => console.log("MongoDB connected"))
  .catch(err => console.log("Mongo Error:", err));

// Routes
app.use("/api/register", require("./routes/register"));
app.use("/api/login", require("./routes/login"));
app.use("/api/match", require("./routes/match"));
app.use("/api/history", require("./routes/history"));
app.use("/api/users", require("./routes/users"));
app.use('/api/invitations', require('./routes/invitations'));
app.use("/api/simulation", require("./routes/simulation"));



// Start server
app.listen(PORT, () => console.log(`Server running on port ${PORT}`));
