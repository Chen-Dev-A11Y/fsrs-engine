package com.fsrs.core;

/**
 * Lifecycle state of a card. The numeric codes are persisted in the database,
 * so they must never be reordered.
 */
public enum CardState {

    /** Never reviewed. */
    NEW(0),

    /** Being learned through short learning steps (minutes). */
    LEARNING(1),

    /** Scheduled by the FSRS memory model (days). */
    REVIEW(2),

    /** A REVIEW card that was lapsed and is being re-learned. */
    RELEARNING(3);

    private final int code;

    CardState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static CardState of(int code) {
        for (CardState s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown card state code: " + code);
    }
}
