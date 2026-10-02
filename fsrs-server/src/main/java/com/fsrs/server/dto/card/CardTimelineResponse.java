package com.fsrs.server.dto.card;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Review history of one card, shaped as an interval-evolution chart.
 *
 * <p>{@code reviews} is ordered oldest first. {@code difficultyAfter} and
 * {@code stabilityAfter} are the values of the <em>next</em> row's "before" fields unless
 * this was the card's last review, in which case they come from the card itself - which is
 * what makes the last point of the chart agree with {@code GET /api/cards}.
 */
public record CardTimelineResponse(
        @JsonProperty("card") CardView card,
        @JsonProperty("reviews") List<TimelineEntry> reviews) {

    /**
     * @param reviewedAt         when the grade was given, epoch millis UTC
     * @param rating             1..4
     * @param stabilityBefore    S entering the review; null on a card's first review
     * @param stabilityAfter     S leaving the review
     * @param difficultyBefore   D entering the review; null on a card's first review
     * @param difficultyAfter    D leaving the review
     * @param retrievability     R predicted at the moment of the review
     * @param intervalDays       days the card was scheduled for by this review
     */
    public record TimelineEntry(
            @JsonProperty("reviewedAt") long reviewedAt,
            @JsonProperty("rating") int rating,
            @JsonProperty("stabilityBefore") Double stabilityBefore,
            @JsonProperty("stabilityAfter") Double stabilityAfter,
            @JsonProperty("difficultyBefore") Double difficultyBefore,
            @JsonProperty("difficultyAfter") Double difficultyAfter,
            @JsonProperty("retrievability") Double retrievability,
            @JsonProperty("intervalDays") double intervalDays) {
    }
}
