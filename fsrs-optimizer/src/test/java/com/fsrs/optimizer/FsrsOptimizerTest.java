package com.fsrs.optimizer;

import com.fsrs.core.FsrsParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tests that decide whether the optimizer is worth shipping at all: does the loss go
 * down, is the answer reproducible, and does degenerate input fail safe rather than loudly.
 *
 * <p>The histories come from {@link SyntheticDataGenerator} rather than from a fixture,
 * because the only honest way to test a fitter is against data whose generating parameters
 * are known.
 */
class FsrsOptimizerTest {

    /**
     * A simulated learner who is genuinely different from the stock defaults - a faster
     * starter, a steeper stability curve and a slower forgetting curve. Real users sit this
     * far from the defaults; that is the entire reason a personalization feature exists.
     *
     * <p>A truth that differs only in one or two weights cannot produce a large improvement,
     * and that is a property of the objective rather than of the optimizer: the defaults are
     * already a good fit for a learner who is close to them. {@link #fitLowersLogLoss} prints
     * the loss at the true weights as the theoretical floor, so the reported improvement is
     * always read against what was actually attainable.
     */
    private static double[] personalizedWeights() {
        double[] truth = FsrsParams.DEFAULT_FSRS_6.clone();
        truth[2] = 3.4;      // Good on the first review keeps the card 3.4 days, not 2.3
        truth[4] = 7.2;      // this learner's hardest cards are harder
        truth[5] = 1.15;     // ...and difficulty spreads faster across grades
        truth[8] = FsrsParams.DEFAULT_FSRS_6[8] * 1.2;
        truth[9] = 0.25;     // stability saturates faster as it grows
        truth[11] = 2.4;     // after a lapse the card comes back stronger
        truth[20] = 0.2;     // forgetting curve decays more slowly
        return truth;
    }

    /** A deliberately gross mis-fit, to show the ordering of the four points is not a coin flip. */
    private static double[] grosslyWrongWeights() {
        double[] wrong = FsrsParams.DEFAULT_FSRS_6.clone();
        wrong[20] = 0.5;
        wrong[8] = FsrsParams.DEFAULT_FSRS_6[8] * 0.5;
        return wrong;
    }

    @Test
    @DisplayName("the fit lowers the log loss, and lands near the true weights' loss")
    void fitLowersLogLoss() {
        double[] truth = personalizedWeights();
        List<CardHistory> histories = new SyntheticDataGenerator().generate(truth, 300, 24, 2024L);
        FsrsOptimizer optimizer = new FsrsOptimizer();
        LogLoss objective = new LogLoss(histories, 2);

        OptimizationResult result = optimizer.optimize(histories, FsrsParams.DEFAULT_FSRS_6.clone());

        double oracle = objective.loss(truth);
        double wrong = objective.loss(grosslyWrongWeights());

        System.out.printf(
                "recovery: reviewsUsed=%d iterations=%d converged=%b%n"
                        + "  true weights (floor) = %.6f%n"
                        + "  optimized            = %.6f%n"
                        + "  default (initial)    = %.6f%n"
                        + "  grossly wrong        = %.6f%n"
                        + "  improvement vs initial = %.2f%%   fitted w8=%.4f (true %.4f)  w20=%.4f (true %.4f)%n",
                result.reviewsUsed(), result.iterations(), result.converged(),
                oracle, result.finalLogLoss(), result.initialLogLoss(), wrong,
                result.improvementRatio() * 100.0,
                result.weights()[8], truth[8], result.weights()[20], truth[20]);

        assertThat(result.reviewsUsed()).isGreaterThan(0);
        assertThat(result.finalLogLoss())
                .as("final log loss must be strictly below the initial one")
                .isLessThan(result.initialLogLoss());
        assertThat(result.finalLogLoss())
                .as("and below a grossly wrong parameter set")
                .isLessThan(wrong);
        assertThat(result.finalLogLoss())
                .as("and within 2% of the loss at the true weights, i.e. it really found them")
                .isLessThan(oracle * 1.02);
        assertThat(result.weights()).hasSize(FsrsParams.SIZE);
    }

    @Test
    @DisplayName("a grossly mis-specified start is pulled back to the true weights' loss")
    void fitRecoversFromAMisSpecifiedStart() {
        double[] truth = personalizedWeights();
        double[] wrong = grosslyWrongWeights();
        List<CardHistory> histories = new SyntheticDataGenerator().generate(truth, 400, 24, 99L);
        LogLoss objective = new LogLoss(histories, 2);

        OptimizationResult result = new FsrsOptimizer().optimize(histories, wrong);

        System.out.printf(
                "recovery-from-wrong: start=%.6f final=%.6f floor(true)=%.6f improvement=%.2f%% iterations=%d%n",
                result.initialLogLoss(), result.finalLogLoss(), objective.loss(truth),
                result.improvementRatio() * 100.0, result.iterations());

        assertThat(result.initialLogLoss()).isGreaterThan(0.5);
        assertThat(result.finalLogLoss())
                .as("a badly wrong start must be pulled most of the way back to the truth")
                .isLessThan(result.initialLogLoss() * 0.90);
        assertThat(result.finalLogLoss())
                .isLessThan(objective.loss(truth) * 1.02);
    }

    @Test
    @DisplayName("the same input and seed produce bit-identical weights")
    void fitIsDeterministic() {
        List<CardHistory> histories = new SyntheticDataGenerator().generate(personalizedWeights(), 120, 16, 7L);
        FsrsOptimizer optimizer = new FsrsOptimizer();
        OptimizerConfig config = OptimizerConfig.defaults().withMaxIterations(40);

        double[] first = optimizer.optimize(histories, FsrsParams.DEFAULT_FSRS_6.clone(), config).weights();
        double[] second = optimizer.optimize(histories, FsrsParams.DEFAULT_FSRS_6.clone(), config).weights();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("empty and unusable histories return a no-op result instead of throwing")
    void degenerateHistoriesAreNotAnError() {
        FsrsOptimizer optimizer = new FsrsOptimizer();
        List<CardHistory> unusable = new ArrayList<>();
        unusable.add(CardHistory.empty());
        unusable.add(CardHistory.of(new double[]{0.0}, new int[]{3}));

        OptimizationResult empty = optimizer.optimize(List.of(), FsrsParams.DEFAULT_FSRS_6.clone());
        OptimizationResult tooShort = optimizer.optimize(unusable, FsrsParams.DEFAULT_FSRS_6.clone());

        assertThat(empty.reviewsUsed()).isZero();
        assertThat(empty.iterations()).isZero();
        assertThat(empty.finalLogLoss()).isZero();
        assertThat(empty.weights()).hasSize(FsrsParams.SIZE);
        assertThat(tooShort.reviewsUsed()).isZero();
        assertThat(tooShort.finalLogLoss()).isZero();
        assertThat(tooShort.weights()).hasSize(FsrsParams.SIZE);
    }

    @Test
    @DisplayName("malformed input is rejected at the boundary, not silently mis-scored")
    void malformedInputIsRejected() {
        assertThatThrownBy(() -> new CardHistory(List.of(0.0, 1.0), List.of(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same size");
        assertThatThrownBy(() -> new CardHistory(List.of(0.0), List.of(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1..4");
        assertThatThrownBy(() -> new CardHistory(List.of(-1.0), List.of(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite and >= 0");
        assertThatThrownBy(() -> new FsrsOptimizer().optimize(null, FsrsParams.DEFAULT_FSRS_6.clone()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FsrsOptimizer().optimize(List.of(), new double[5]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 21");
    }
}
