package com.fsrs.server.dto.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One cell of the review heatmap.
 *
 * <p>{@code date} is the user's local calendar day, and the four counters are the rating
 * breakdown for that day. Shipping them together means the frontend can colour a cell or show
 * a tooltip without a second request per day.
 */
public record HeatmapCell(
        @JsonProperty("date") String date,
        @JsonProperty("count") int count,
        @JsonProperty("again") int again,
        @JsonProperty("hard") int hard,
        @JsonProperty("good") int good,
        @JsonProperty("easy") int easy) {
}
