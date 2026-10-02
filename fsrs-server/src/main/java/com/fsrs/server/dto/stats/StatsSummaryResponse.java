package com.fsrs.server.dto.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Dashboard summary.
 *
 * <p>The workload numbers ({@code dueToday}, {@code newToday}, {@code forecast}) exclude both
 * deleted and archived cards, while {@code totalCards}, {@code totalReviews} and the retention
 * figures are history and keep counting them. That split is the point of archiving: the
 * calendar and the progress bar reflect what the user is actually studying, and the record of
 * what they already studied does not shrink.
 *
 * @param totalCards     cards in the account, archived ones included (they are still owned)
 * @param totalReviews   review rows ever written
 * @param dueToday       review cards due right now, excluding archived and deleted cards
 * @param newToday       new cards introduced during the current local day
 * @param retention7d    share of successful recalls over the last 7 local days
 * @param retention30d   share of successful recalls over the last 30 local days
 * @param streakDays     consecutive local days ending today with at least one review
 * @param archivedDecks  live decks that are archived; drives the "N decks archived" badge
 * @param archivedCards  live cards whose deck is archived; drives the "M cards archived" badge
 * @param forecast       expected load for the next 30 local days, excluding archived cards
 */
public record StatsSummaryResponse(
        @JsonProperty("totalCards") long totalCards,
        @JsonProperty("totalReviews") long totalReviews,
        @JsonProperty("dueToday") long dueToday,
        @JsonProperty("newToday") long newToday,
        @JsonProperty("retention7d") double retention7d,
        @JsonProperty("retention30d") double retention30d,
        @JsonProperty("streakDays") long streakDays,
        @JsonProperty("archivedDecks") long archivedDecks,
        @JsonProperty("archivedCards") long archivedCards,
        @JsonProperty("forecast") List<ForecastEntry> forecast) {

    /** @param count cards due on that local day */
    public record ForecastEntry(
            @JsonProperty("date") String date,
            @JsonProperty("count") long count) {
    }
}
