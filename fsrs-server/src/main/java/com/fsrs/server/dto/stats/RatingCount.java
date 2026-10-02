package com.fsrs.server.dto.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Retention numerator and denominator for one window.
 *
 * <p>"Retention" here is the share of reviews that ended in a successful recall
 * ({@code rating >= 3}). Failures are {@code Again}/{@code Hard} on a card that already
 * had a memory state; counting a first-ever Easy as a success would inflate the number,
 * so callers filter by {@code state_before <> 0} where it matters.
 */
public record RatingCount(@JsonProperty("total") int total, @JsonProperty("recalled") int recalled) {

    public double retention() {
        return total == 0 ? 0.0 : (double) recalled / (double) total;
    }
}
