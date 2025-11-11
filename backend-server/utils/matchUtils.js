// utils/matchUtils.js

/** Case-insensitive city match */
function sameCity(a, b) {
  const na = (a || '').toString().trim().toLowerCase();
  const nb = (b || '').toString().trim().toLowerCase();
  return na === nb;
}

/** Return normalized availability set; allowed tokens: "בוקר","צהריים","ערב" */
function toSlotSet(arr) {
  if (!Array.isArray(arr)) return new Set();
  return new Set(
    arr
      .map(s => (s || '').toString().trim())
      .filter(s => s === 'בוקר' || s === 'צהריים' || s === 'ערב')
  );
}

/**
 * Availability F1 between user and candidate:
 *  precision = |A∩B| / |B|
 *  recall    = |A∩B| / |A|
 *  F1        = 2 * P * R / (P + R)  (0..1), defined as 0 if no overlap or empty sets.
 * A = user slots, B = candidate slots
 */
function availabilityF1(userSlots = [], otherSlots = []) {
  const A = toSlotSet(userSlots);
  const B = toSlotSet(otherSlots);
  if (A.size === 0 || B.size === 0) return 0;

  let inter = 0;
  for (const v of A) if (B.has(v)) inter++;

  if (inter === 0) return 0;

  const precision = inter / B.size;
  const recall    = inter / A.size;
  return (2 * precision * recall) / (precision + recall);
}

/** Level similarity with strong penalty for adjacent levels and hard block for gap>=2 */
function levelSimilarity(l1, l2) {
  const a = Number(l1 ?? 0), b = Number(l2 ?? 0);
  const diff = Math.abs(a - b);
  if (diff >= 2) return -1;   // hard block (e.g., beginner vs professional)
  if (diff === 1) return 0.40; // adjacent allowed but strongly penalized
  return 1.00;                 // same level
}

/** Default weights (must sum ~1); tuned for 'Balanced' profile */
const DEFAULT_WEIGHTS = {
  time:  0.45, // availability (F1)
  level: 0.50, // running level similarity
  city:  0.05  // same city (already a hard filter; kept small)
};

/** Normalize weights safely so they always sum to 1 */
function normalizeWeights(w = {}) {
  const t = Number(w.time ?? DEFAULT_WEIGHTS.time);
  const l = Number(w.level ?? DEFAULT_WEIGHTS.level);
  const c = Number(w.city ?? DEFAULT_WEIGHTS.city);
  const sum = t + l + c || 1;
  return { time: t / sum, level: l / sum, city: c / sum };
}

/**
 * Calculates a 0..100 match score with:
 *  - Hard filter: same city
 *  - Hard filter: availability must overlap (F1>0)
 *  - Hard filter: level gap < 2
 * Score = W_TIME*f1 + W_LEVEL*levelSim + W_CITY*1
 */
function calculateMatch(user1, user2, weights) {
  // --- city hard filter ---
  if (!sameCity(user1.city, user2.city)) return null;

  // --- availability F1 ---
  const f1 = availabilityF1(user1.availability, user2.availability); // 0..1
  if (f1 <= 0) return null; // must have at least one overlapping slot

  // --- level similarity (hard block for gap>=2) ---
  const lvlSim = levelSimilarity(user1.level, user2.level);
  if (lvlSim < 0) return null;

  // --- weights (normalized) ---
  const { time: W_TIME, level: W_LEVEL, city: W_CITY } = normalizeWeights(weights);
  const citySim = 1; // already ensured same city

  const score01 = (W_TIME * f1) + (W_LEVEL * lvlSim) + (W_CITY * citySim);
  return Math.round(score01 * 10000) / 100; // percent with 2 decimals
}

/** Returns sorted matches (desc) above minScore percent */
function findBestMatches(currentUser, allUsers, minScore = 70, weights = DEFAULT_WEIGHTS) {
  const matches = [];

  for (const user of allUsers || []) {
    if (user.id === currentUser.id) continue;

    const score = calculateMatch(currentUser, user, weights);
    if (score !== null && score >= Number(minScore)) {
      matches.push({
        id: user.id,
        name: user.fullName || user.name || '',
        city: user.city || '',
        phone: user.phone || '',
        level: user.level ?? null,
        availability: Array.isArray(user.availability) ? user.availability : [],
        score
      });
    }
  }

  matches.sort((a, b) => b.score - a.score);
  return matches;
}

module.exports = { calculateMatch, findBestMatches, DEFAULT_WEIGHTS };
