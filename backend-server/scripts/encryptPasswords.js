require("dotenv").config(); // כדי לטעון את ה-MONGO_URI מה-.env
const mongoose = require("mongoose");
const bcrypt = require("bcrypt");
const User = require("../models/User");

async function encryptExistingPasswords() {
  try {
    await mongoose.connect(process.env.MONGO_URI);
    console.log("✅ Connected to MongoDB");

    const users = await User.find({});
    console.log(`📦 Found ${users.length} users`);

    for (const user of users) {
      if (user.password.startsWith("$2b$")) {
        console.log(`ℹ️ Skipping already hashed password for ${user.email}`);
        continue;
      }
      const hashed = await bcrypt.hash(user.password, 10);
      user.password = hashed;
      await user.save();
      console.log(`🔒 Updated password for ${user.email}`);
    }

    console.log("✅ All passwords processed");
  } catch (err) {
    console.error("❌ Error:", err);
  } finally {
    mongoose.disconnect();
  }
}

encryptExistingPasswords();
