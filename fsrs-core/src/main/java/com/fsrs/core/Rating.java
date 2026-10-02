package com.fsrs.core;

/**
 * The grade a learner gives to a card. The numeric values are part of the FSRS
 * specification and are used directly inside the model formulas.
 */
public enum Rating {

    /** Complete blackout: the card was forgotten. */
    AGAIN(1),

    /** Recalled, but with serious difficulty. */
    HARD(2),

    /** Recalled after hesitation. This is the neutral rating. */
    GOOD(3),

    /** Recalled effortlessly. */
    EASY(4);

    private final int value;

    Rating(int value) {
        this.value = value;
    }

    /** 1..4, as used by the FSRS formulas. */
    public int value() {
        return value;
    }

    /** True when the learner successfully recalled the card. */
    public boolean isRecall() {
        return this != AGAIN;
    }

    public static Rating of(int value) {
        for (Rating r : values()) {
            if (r.value == value) {
                return r;
            }
        }
        throw new IllegalArgumentException("rating must be 1..4 but was " + value);
    }
}
