package com.fsrs.optimizer;

import com.fsrs.core.FsrsParams;

import java.util.Arrays;

/**
 * The box each fitted weight is projected back into.
 *
 * <h2>Why bound anything at all</h2>
 * The loss is a mean over a finite sample, so it is only ever known to within sampling
 * noise. A gradient-free coordinate step can happily walk a weight to {@code 1e-9} or
 * {@code 1e9} on the way to a numerically degenerate model that happens to score
 * fractionally better on that sample. The published model also has hard semantics attached
 * to some weights, and those semantics are worth more than a third decimal place of loss.
 *
 * <h2>Two different kinds of bound</h2>
 * <ul>
 *   <li><b>Numerical</b> - every weight is strictly positive in FSRS-6, so the floor is
 *       {@link #MIN_WEIGHT}. Zero or negative values make {@code S^-w9} or
 *       {@code 0.9^(-1/w20)} undefined or sign-flipped, and a single {@code NaN} inside the
 *       replay would silently poison every sample that follows it.</li>
 *   <li><b>Semantic</b> - bounds that encode what the parameter <em>means</em>, so the
 *       result stays a readable FSRS model rather than a curve-fit. These are the ones
 *       worth defending in review:
 *       <ul>
 *         <li>{@code w9, w12, w13, w19 <= 1}: these are exponents of the form
 *             {@code S^-w9}. Below 1, stability gains shrink as stability grows (the
 *             spacing effect); above 1 the model would predict that an already-easy card
 *             gets <em>easier</em> faster, which inverts the phenomenon the whole model
 *             exists to capture.</li>
 *         <li>{@code w15 <= 1}: {@code w15} multiplies the stability gain of a Hard review.
 *             Hard must never be worth more than Good, or the schedule would learn to
 *             reward hesitation.</li>
 *         <li>{@code w16 >= 1}: {@code w16} multiplies the gain of an Easy review. Easy
 *             must never be worth less than Good.</li>
 *         <li>{@code w7 <= 1}: a convex mixing weight towards {@code D0(Easy)}; beyond 1 it
 *             stops being a mean reversion and starts over-shooting difficulty in the
 *             opposite direction.</li>
 *         <li>{@code w4 in [1, 15]} and {@code w20 in [0.05, 1]}: the two ranges called out
 *             in the project plan. {@code w4} is the difficulty of a first Easy answer and
 *             is itself clamped into {@code [1, 10]} by the model; {@code w20} is the
 *             forgetting-curve decay, where {@code 0.05} is already a near-step function
 *             and {@code 1.0} a nearly flat one.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>The default FSRS-6 weights and the {@code java-fsrs} defaults both sit strictly inside
 * this box, so projection never moves a caller's starting point unless that point was
 * already unusable.
 */
public final class ParameterBounds {

    /** Numerical floor shared by every weight: FSRS-6 has no zero or negative parameters. */
    public static final double MIN_WEIGHT = 1e-4;

    private static final double[] LOWER = {
            MIN_WEIGHT, MIN_WEIGHT, MIN_WEIGHT, MIN_WEIGHT,   // w0..w3  initial stability (days)
            1.0,                                              // w4      initial difficulty base
            MIN_WEIGHT,                                       // w5      initial difficulty exponent
            MIN_WEIGHT,                                       // w6      difficulty step
            MIN_WEIGHT,                                       // w7      mean reversion towards D0(Easy)
            MIN_WEIGHT,                                       // w8      log gain after a recall
            MIN_WEIGHT,                                       // w9      stability exponent after a recall
            MIN_WEIGHT,                                       // w10     retrievability gain after a recall
            MIN_WEIGHT,                                       // w11     base stability after a lapse
            MIN_WEIGHT,                                       // w12     difficulty exponent after a lapse
            MIN_WEIGHT,                                       // w13     stability exponent after a lapse
            MIN_WEIGHT,                                       // w14     retrievability gain after a lapse
            MIN_WEIGHT,                                       // w15     Hard multiplier
            1.0,                                              // w16     Easy multiplier
            MIN_WEIGHT,                                       // w17     same-day log gain
            MIN_WEIGHT,                                       // w18     same-day offset
            MIN_WEIGHT,                                       // w19     same-day stability exponent
            0.05                                              // w20     forgetting-curve decay
    };

    private static final double[] UPPER = {
            100.0, 100.0, 100.0, 100.0,                       // w0..w3  a first interval beyond ~3 months
            15.0,                                             // w4
            5.0,                                              // w5
            5.0,                                              // w6
            1.0,                                              // w7
            8.0,                                              // w8      e^8 ~ 3000x growth per recall
            1.0,                                              // w9
            5.0,                                              // w10
            30.0,                                             // w11
            1.0,                                              // w12
            1.0,                                              // w13
            5.0,                                              // w14
            1.0,                                              // w15
            10.0,                                             // w16
            5.0,                                              // w17
            1.0,                                              // w18
            1.0,                                              // w19
            1.0                                               // w20
    };

    static {
        if (LOWER.length != FsrsParams.SIZE || UPPER.length != FsrsParams.SIZE) {
            throw new IllegalStateException("parameter bounds must cover all " + FsrsParams.SIZE + " weights");
        }
    }

    private ParameterBounds() {
    }

    /** Smallest allowed value of {@code w_index}. */
    public static double lower(int index) {
        return LOWER[index];
    }

    /** Largest allowed value of {@code w_index}. */
    public static double upper(int index) {
        return UPPER[index];
    }

    /** Projects a single weight onto its interval. */
    public static double project(int index, double value) {
        if (Double.isNaN(value)) {
            return LOWER[index];
        }
        if (value < LOWER[index]) {
            return LOWER[index];
        }
        return Math.min(value, UPPER[index]);
    }

    /** Projects a whole weight vector, returning a new array. */
    public static double[] project(double[] weights) {
        requireValid(weights);
        double[] projected = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            projected[i] = project(i, weights[i]);
        }
        return projected;
    }

    /**
     * Rejects anything the replay could not survive: a wrong arity or a non-finite value.
     *
     * @throws IllegalArgumentException when {@code weights} is null, has the wrong length,
     *                                  or contains {@code NaN}/infinity
     */
    public static void requireValid(double[] weights) {
        if (weights == null) {
            throw new IllegalArgumentException("weights must not be null");
        }
        if (weights.length != FsrsParams.SIZE) {
            throw new IllegalArgumentException("FSRS-6 requires exactly " + FsrsParams.SIZE
                    + " weights but got " + weights.length);
        }
        for (int i = 0; i < weights.length; i++) {
            if (!Double.isFinite(weights[i])) {
                throw new IllegalArgumentException("w" + i + " is not a finite number: " + weights[i]);
            }
        }
    }

    /** {@code LOWER} and {@code UPPER} as a printable pair, used by tests and reports. */
    public static String describe() {
        return "lower=" + Arrays.toString(LOWER) + " upper=" + Arrays.toString(UPPER);
    }
}
