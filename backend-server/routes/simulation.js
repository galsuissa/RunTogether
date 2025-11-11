const express = require("express");
const router = express.Router();
const { exec } = require("child_process");

router.post("/", (req, res) => {
  const { run_id } = req.body;

  if (!run_id) {
    return res.status(400).json({ error: "run_id is required" });
  }

  const command = `python3 simulate.py ${run_id}`;

  exec(command, (error, stdout, stderr) => {
    if (error) {
      console.error(`Execution error: ${error}`);
      return res.status(500).json({ error: "Simulation failed" });
    }

    try {
      const result = JSON.parse(stdout);
      res.json(result);
    } catch (parseError) {
      console.error("JSON Parse Error:", parseError);
      res.status(500).json({ error: "Failed to parse simulation output" });
    }
  });
});

module.exports = router;
