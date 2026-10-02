package com.fsrs.optimizer;

/**
 * Central-difference approximation of {@code dLoss/dw_i}.
 *
 * <pre>
 * dLoss/dw_i ~ ( loss(w + h*e_i) - loss(w - h*e_i) ) / (2h)
 * </pre>
 *
 * <h2>Why finite differences instead of hand-written derivatives</h2>
 * FSRS-6 is a chain of {@code pow}, {@code exp} and {@code min} across up to 21 parameters,
 * and the derivative would have to be propagated backwards through every review of every
 * card - a hand-written adjoint is exactly the kind of code where a subtle sign error
 * survives every test that only checks "the loss went down". Central differences are
 * {@code O(h^2)} accurate, cost two replays per coordinate, and cannot disagree with the
 * objective by construction: they <em>are</em> the objective, sampled.
 *
 * <p>Perturbations are evaluated without projecting them back into
 * {@link ParameterBounds}. Near a boundary that makes the estimate one-sided rather than
 * wrong, whereas projecting would report a zero slope for a coordinate that is still
 * perfectly movable in the opposite direction.
 */
public final class FiniteDiffGradient {

    private final LogLoss objective;
    private final double step;

    /**
     * @param objective the (possibly subsampled) objective to differentiate
     * @param step      perturbation {@code h}; see {@link OptimizerConfig#finiteDiffStep()}
     */
    public FiniteDiffGradient(LogLoss objective, double step) {
        if (objective == null) {
            throw new IllegalArgumentException("objective must not be null");
        }
        if (!Double.isFinite(step) || step <= 0.0) {
            throw new IllegalArgumentException("step must be finite and > 0 but was " + step);
        }
        this.objective = objective;
        this.step = step;
    }

    /**
     * @return a fresh array of length {@link com.fsrs.core.FsrsParams#SIZE}. A coordinate
     *         whose replay produces a non-finite loss is reported as {@code 0.0}: refusing
     *         to move in an undefined region is safer than propagating an infinity into
     *         every other coordinate's step
     */
    public double[] gradient(double[] weights) {
        ParameterBounds.requireValid(weights);
        int count = weights.length;
        double[] gradient = new double[count];
        double[] probe = weights.clone();
        for (int i = 0; i < count; i++) {
            double original = weights[i];
            probe[i] = original + step;
            double up = objective.loss(probe);
            probe[i] = original - step;
            double down = objective.loss(probe);
            probe[i] = original;
            gradient[i] = Double.isFinite(up) && Double.isFinite(down)
                    ? (up - down) / (2.0 * step)
                    : 0.0;
        }
        return gradient;
    }
}
