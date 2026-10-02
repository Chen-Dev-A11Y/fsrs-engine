package com.fsrs.core;

import java.util.Arrays;

/**
 * The 21 model weights of FSRS-6.
 *
 * <p>Index convention follows the official specification: {@code w0 .. w20}, so
 * {@code w(i)} returns the weight named {@code w_i} in the formulas. Keeping the
 * raw array visible is deliberate - it makes it easy to check the code against
 * the published formulas, and it is exactly what the optimizer fits.
 *
 * <p>Weight layout:
 * <pre>
 * w0..w3   initial stability          S0(G) = w[G-1]
 * w4..w5   initial difficulty         D0(G) = w4 - e^(w5*(G-1)) + 1
 * w6..w7   difficulty update          damping + mean reversion
 * w8..w10  stability after recall
 * w11..w14 stability after forgetting (post-lapse)
 * w15..w16 hard / easy multipliers
 * w17..w19 FSRS-6 same-day review stability
 * w20      trainable decay of the forgetting curve
 * </pre>
 */
public final class FsrsParams {

    /** FSRS-6 uses 21 parameters. */
    public static final int SIZE = 21;

    /**
     * Official FSRS-6 default weights.
     *
     * <p>Note: the {@code java-fsrs} README ships a slightly different default set.
     * Both are recorded here on purpose; the differential test pins the exact set
     * used on each side so that formula differences can never hide behind a
     * difference in defaults.
     */
    public static final double[] DEFAULT_FSRS_6 = {
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194,
            0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629,
            1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542
    };

    /** Default set published in the java-fsrs README, kept for differential testing. */
    public static final double[] JAVA_FSRS_DEFAULTS = {
            0.2172, 1.1771, 3.2602, 16.1507, 7.0114, 0.57, 2.0966,
            0.0069, 1.5261, 0.112, 1.0178, 1.849, 0.1133, 0.3127,
            2.2934, 0.2191, 3.0004, 0.7536, 0.3332, 0.1437, 0.2
    };

    private final double[] w;

    private FsrsParams(double[] w) {
        this.w = w;
    }

    /** Validates and defensively copies the given weights. */
    public static FsrsParams of(double... weights) {
        if (weights.length != SIZE) {
            throw new IllegalArgumentException("FSRS-6 requires exactly " + SIZE
                    + " parameters but got " + weights.length);
        }
        for (int i = 0; i < weights.length; i++) {
            if (!Double.isFinite(weights[i])) {
                throw new IllegalArgumentException("parameter w" + i + " is not a finite number: " + weights[i]);
            }
        }
        return new FsrsParams(weights.clone());
    }

    /** The official FSRS-6 defaults. */
    public static FsrsParams defaults() {
        return new FsrsParams(DEFAULT_FSRS_6.clone());
    }

    /** {@code w_i} in the specification, 0-based. */
    public double w(int i) {
        if (i < 0 || i >= SIZE) {
            throw new IndexOutOfBoundsException("w index must be 0.." + (SIZE - 1) + " but was " + i);
        }
        return w[i];
    }

    /** Defensive copy of all weights, ready for persistence or optimization. */
    public double[] toArray() {
        return w.clone();
    }

    @Override
    public String toString() {
        return "FsrsParams" + Arrays.toString(w);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FsrsParams other && Arrays.equals(w, other.w);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(w);
    }
}
