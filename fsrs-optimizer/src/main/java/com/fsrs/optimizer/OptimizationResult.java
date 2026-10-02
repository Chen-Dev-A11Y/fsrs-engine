package com.fsrs.optimizer;

import com.fsrs.core.FsrsParams;

import java.util.Arrays;

/**
 * What one fit produced.
 *
 * <p>The two loss numbers are deliberately part of the result: the whole value of an
 * optimizer is the <em>pair</em> of numbers, since a fitted set that does not beat the
 * set it started from is worse than useless.
 *
 * @param weights         the fitted {@code w0..w20}; always a defensive copy
 * @param initialLogLoss  the regularized objective at the caller's {@code initialWeights}
 * @param finalLogLoss    the same objective at {@link #weights()}
 * @param iterations      descent iterations actually performed (stopped early or hit the cap)
 * @param converged       {@code true} when the loop stopped because a step no longer paid
 *                        off, {@code false} when it ran out of {@code maxIterations}
 * @param reviewsUsed     review events that carried a prediction and therefore entered
 *                        the loss. A card's first review has no previous state to predict
 *                        from, so it seeds the memory state instead of contributing a
 *                        sample: {@code reviewsUsed} is one less than the number of
 *                        reviews replayed, summed over the cards that were kept
 */
public record OptimizationResult(
        double[] weights,
        double initialLogLoss,
        double finalLogLoss,
        int iterations,
        boolean converged,
        int reviewsUsed) {

    public OptimizationResult {
        if (weights == null || weights.length != FsrsParams.SIZE) {
            throw new IllegalArgumentException("weights must contain exactly " + FsrsParams.SIZE
                    + " values but got " + (weights == null ? "null" : weights.length));
        }
        weights = weights.clone();
    }

    /** Defensive copy; the array stays owned by the result. */
    @Override
    public double[] weights() {
        return weights.clone();
    }

    /** Absolute drop in the regularized objective. */
    public double improvement() {
        return initialLogLoss - finalLogLoss;
    }

    /** Relative drop, e.g. {@code 0.12} for a 12% improvement. */
    public double improvementRatio() {
        return initialLogLoss == 0.0 ? 0.0 : improvement() / initialLogLoss;
    }

    @Override
    public String toString() {
        return "OptimizationResult[initialLogLoss=" + initialLogLoss
                + ", finalLogLoss=" + finalLogLoss
                + ", iterations=" + iterations
                + ", converged=" + converged
                + ", reviewsUsed=" + reviewsUsed
                + ", weights=" + Arrays.toString(weights) + "]";
    }
}
