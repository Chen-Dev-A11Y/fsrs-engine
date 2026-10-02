package com.fsrs.server.scheduler;

import com.fsrs.core.FsrsConfig;
import com.fsrs.core.FsrsParams;
import com.fsrs.core.FsrsScheduler;
import com.fsrs.server.domain.SchedulerProfile;
import com.fsrs.server.mapper.SchedulerProfileMapper;
import org.springframework.stereotype.Service;

/**
 * Builds the algorithm object for a user from their stored profile.
 *
 * <p>{@code fsrs-core} is deliberately stateless and exception-driven: it validates its own
 * inputs and knows nothing about users. This class is the only bridge between the two, so
 * a malformed stored profile - a truncated weight list after a bad manual edit - can be
 * handled in one place instead of surfacing as a 500 from inside the model.
 *
 * <p>Fuzzing is on for real scheduling but off for the interval preview: a preview that
 * returns 11 days while the committed interval becomes 10 destroys trust in the UI, and
 * the whole point of {@code predictedIntervals} is that the learner can compare grades.
 */
@Service
public class SchedulerFactory {

    private final SchedulerProfileMapper profileMapper;

    public SchedulerFactory(SchedulerProfileMapper profileMapper) {
        this.profileMapper = profileMapper;
    }

    /** The scheduler used for real scheduling decisions (fuzzing enabled). */
    public FsrsScheduler forUser(long userId) {
        SchedulerProfile profile = profileMapper.findByUserId(userId);
        return new FsrsScheduler(params(profile), config(profile, true));
    }

    /** The scheduler used for previews and charts: same weights, no random jitter. */
    public FsrsScheduler deterministicForUser(long userId) {
        SchedulerProfile profile = profileMapper.findByUserId(userId);
        return new FsrsScheduler(params(profile), config(profile, false));
    }

    /**
     * Weights of the user's profile, or the FSRS-6 defaults when the row is missing or
     * unusable. Falling back instead of throwing keeps a newly registered user - who has
     * no profile row yet - working with zero setup.
     */
    public FsrsParams params(SchedulerProfile profile) {
        if (profile == null || profile.getWeights() == null) {
            return FsrsParams.defaults();
        }
        String[] parts = profile.getWeights().split(",");
        if (parts.length != FsrsParams.SIZE) {
            return FsrsParams.defaults();
        }
        double[] weights = new double[FsrsParams.SIZE];
        try {
            for (int i = 0; i < parts.length; i++) {
                weights[i] = Double.parseDouble(parts[i].trim());
            }
        } catch (NumberFormatException ex) {
            return FsrsParams.defaults();
        }
        return FsrsParams.of(weights);
    }

    /** Serializes weights back into the stored comma-separated form. */
    public String serialize(double[] weights) {
        StringBuilder sb = new StringBuilder(weights.length * 8);
        for (int i = 0; i < weights.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(weights[i]);
        }
        return sb.toString();
    }

    private FsrsConfig config(SchedulerProfile profile, boolean fuzzing) {
        double retention = profile == null || profile.getDesiredRetention() == null
                ? 0.9 : profile.getDesiredRetention();
        double maximum = profile == null || profile.getMaximumIntervalDays() == null
                ? 36500.0 : profile.getMaximumIntervalDays();
        return new FsrsConfig(retention, maximum, fuzzing);
    }
}
