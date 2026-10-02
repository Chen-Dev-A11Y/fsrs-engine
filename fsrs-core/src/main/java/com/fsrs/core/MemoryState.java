package com.fsrs.core;

/**
 * The two variables that fully describe a learner's memory of one item in the
 * DSR model.
 *
 * <ul>
 *   <li><b>S</b> - stability: the interval in days at which retrievability drops to 90%.</li>
 *   <li><b>D</b> - difficulty: how hard this item is for this learner, in [1, 10].</li>
 * </ul>
 *
 * Retrievability (R) is not stored: it is derived from S and the elapsed time.
 * A brand new card has no memory state yet, represented by {@code null} rather
 * than by a sentinel value.
 */
public record MemoryState(double stability, double difficulty) {

    public static final double MIN_DIFFICULTY = 1.0;
    public static final double MAX_DIFFICULTY = 10.0;

    public MemoryState {
        if (!Double.isFinite(stability) || stability <= 0.0) {
            throw new IllegalArgumentException("stability must be finite and > 0 but was " + stability);
        }
        if (!Double.isFinite(difficulty)
                || difficulty < MIN_DIFFICULTY
                || difficulty > MAX_DIFFICULTY) {
            throw new IllegalArgumentException("difficulty must be within [1, 10] but was " + difficulty);
        }
    }

    public static double clampDifficulty(double difficulty) {
        if (difficulty < MIN_DIFFICULTY) {
            return MIN_DIFFICULTY;
        }
        return Math.min(difficulty, MAX_DIFFICULTY);
    }
}
