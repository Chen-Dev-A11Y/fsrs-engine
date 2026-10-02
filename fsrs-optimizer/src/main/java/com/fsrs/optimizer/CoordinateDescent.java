package com.fsrs.optimizer;

import com.fsrs.core.FsrsParams;

/**
 * The descent driver: project, take a finite-difference gradient step, keep the step only
 * if it pays off, and stop as soon as it stops paying off.
 *
 * <h2>Why the step is normalized rather than "raw gradient times rate"</h2>
 * Two properties of this objective make a raw learning rate unusable:
 * <ul>
 *   <li>It is a <em>mean</em> over however many reviews the caller has, so every derivative
 *       carries a factor {@code 1/N}. A rate tuned on a 200-review history is 500x too small
 *       on a 100 000-review one.</li>
 *   <li>The 21 coordinates differ in magnitude by four orders: {@code w4} is a difficulty
 *       near 6, {@code w7} a mixing weight near 0.001. The same absolute nudge is a rounding
 *       error for one and a total rewrite for the other.</li>
 * </ul>
 * So the gradient is measured in units of each weight's own current magnitude - every FSRS
 * weight is strictly positive, which is what makes that metric legal - and then normalized to
 * unit length. The learning rate becomes a <em>relative distance</em>: {@code 0.25} means "no
 * coordinate may move more than about 25% of where it is now", independent of row count and
 * of units. Backtracking then halves it until the step actually lowers the loss.
 *
 * <h2>The three stopping conditions</h2>
 * <ol>
 *   <li>an iteration improves the objective by less than
 *       {@link OptimizerConfig#convergenceTol()} - reported as {@code converged = true};</li>
 *   <li>no learning rate down to {@link #MIN_LEARNING_RATE} produces a better point, or the
 *       gradient is exactly flat - also {@code converged = true}, because the iterates have
 *       nowhere left to go;</li>
 *   <li>{@link OptimizerConfig#maxIterations()} reached - reported as {@code converged = false},
 *       so a caller can tell "it finished" from "it ran out of budget".</li>
 * </ol>
 *
 * <h2>Two objectives on purpose</h2>
 * {@code objective} is the full history and is what every reported number comes from.
 * {@code gradientObjective} may be a deterministic subsample of it, and is what the 42
 * replays per iteration are spent on. Keeping the two apart means the fit can be cheap
 * without the reported loss ever becoming an estimate.
 */
public final class CoordinateDescent {

    /**
     * Relative step length where backtracking starts: a coordinate may move up to roughly a
     * quarter of its own magnitude per iteration. Large enough to cross the interesting part
     * of a weight in a few iterations, small enough that most first attempts are accepted.
     */
    private static final double INITIAL_LEARNING_RATE = 0.25;

    /** Ceiling for the bold-driver growth below; beyond this the steps purely oscillate. */
    private static final double MAX_LEARNING_RATE = 1.0;

    /** Grow the rate by this factor after an accepted step, so a long shallow valley still gets crossed. */
    private static final double LEARNING_RATE_GROWTH = 1.5;

    /** Below this the step is pure floating-point noise; the fit has converged for practical purposes. */
    private static final double MIN_LEARNING_RATE = 1e-12;

    /** Backtracking budget per iteration; 40 halvings span 1e-12 of the initial rate. */
    private static final int MAX_BACKTRACK_ATTEMPTS = 40;

    private final LogLoss objective;
    private final LogLoss gradientObjective;
    private final OptimizerConfig config;
    private final FiniteDiffGradient gradient;

    /**
     * @param objective         full-history objective; every accepted point is scored on it
     * @param gradientObjective objective the gradient and the step acceptance use
     * @param config            stopping rules and the finite-difference step
     */
    public CoordinateDescent(LogLoss objective, LogLoss gradientObjective, OptimizerConfig config) {
        if (objective == null || gradientObjective == null || config == null) {
            throw new IllegalArgumentException("objective, gradientObjective and config must not be null");
        }
        this.objective = objective;
        this.gradientObjective = gradientObjective;
        this.config = config;
        this.gradient = new FiniteDiffGradient(gradientObjective, config.finiteDiffStep());
    }

    /**
     * @param weights the result of the descent: the best point seen when measured on the
     *                <em>full</em> objective, never worse than the projected starting point
     * @param loss    the full-objective loss at {@code weights}
     */
    public record Outcome(double[] weights, double loss, int iterations, boolean converged) {
    }

    /**
     * Runs the descent from {@code start}, which is projected into
     * {@link ParameterBounds} first.
     *
     * @throws IllegalStateException when even the projected starting point has a non-finite loss,
     *                               which would make every comparison below meaningless
     */
    public Outcome minimize(double[] start) {
        ParameterBounds.requireValid(start);
        double[] weights = ParameterBounds.project(start);
        double current = gradientObjective.loss(weights);
        if (!Double.isFinite(current)) {
            throw new IllegalStateException("the starting weights produce a non-finite loss: " + current);
        }

        double[] best = weights.clone();
        double bestLoss = objective.loss(weights);
        if (!Double.isFinite(bestLoss)) {
            bestLoss = current;
        }
        double learningRate = INITIAL_LEARNING_RATE;
        int iterations = 0;
        boolean converged = false;

        for (int iteration = 1; iteration <= config.maxIterations(); iteration++) {
            iterations = iteration;
            double[] direction = stepDirection(weights, gradient.gradient(weights));
            if (direction == null) {
                converged = true;
                break;
            }
            double previous = current;
            boolean improved = false;

            for (int attempt = 0; attempt < MAX_BACKTRACK_ATTEMPTS && learningRate >= MIN_LEARNING_RATE; attempt++) {
                double[] candidate = new double[FsrsParams.SIZE];
                for (int i = 0; i < candidate.length; i++) {
                    candidate[i] = ParameterBounds.project(i, weights[i] + learningRate * direction[i]);
                }
                double candidateLoss = gradientObjective.loss(candidate);
                if (candidateLoss < current) {
                    weights = candidate;
                    current = candidateLoss;
                    improved = true;
                    break;
                }
                learningRate *= 0.5;
            }

            if (!improved) {
                converged = true;
                break;
            }

            double fullLoss = objective.loss(weights);
            if (Double.isFinite(fullLoss) && fullLoss < bestLoss) {
                bestLoss = fullLoss;
                best = weights.clone();
            }

            if (previous - current < config.convergenceTol()) {
                converged = true;
                break;
            }
            learningRate = Math.min(learningRate * LEARNING_RATE_GROWTH, MAX_LEARNING_RATE);
        }
        return new Outcome(best, bestLoss, iterations, converged);
    }

    /**
     * Turns a gradient into a descent direction of unit length in the metric where each
     * coordinate is measured in units of its own current magnitude.
     *
     * <p>Every FSRS-6 weight is strictly positive - the model has no meaningful zero or
     * negative parameter - so {@code |w_i|} is a legal unit for {@code w_i}, and the
     * difference between "0.001" and "0.01" is treated as the same size of move whether the
     * weight is {@code w7} or {@code w4}.
     *
     * @return {@code null} when the gradient is exactly flat or not finite, i.e. nothing to do
     */
    private static double[] stepDirection(double[] weights, double[] gradient) {
        int count = gradient.length;
        double[] direction = new double[count];
        double norm = 0.0;
        for (int i = 0; i < count; i++) {
            double scale = Math.max(Math.abs(weights[i]), ParameterBounds.MIN_WEIGHT);
            double component = Double.isFinite(gradient[i]) ? gradient[i] * scale : 0.0;
            direction[i] = component;
            norm += component * component;
        }
        norm = Math.sqrt(norm);
        if (!(norm > 0.0) || !Double.isFinite(norm)) {
            return null;
        }
        for (int i = 0; i < count; i++) {
            double scale = Math.max(Math.abs(weights[i]), ParameterBounds.MIN_WEIGHT);
            direction[i] = -(direction[i] / norm) * scale;
        }
        return direction;
    }
}
