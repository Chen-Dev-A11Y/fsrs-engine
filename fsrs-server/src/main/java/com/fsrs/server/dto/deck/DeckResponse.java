package com.fsrs.server.dto.deck;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A deck with its live counters.
 *
 * <p>Two independent flags describe its lifecycle, and neither implies the other:
 * {@code archived} decks are still returned by {@code GET /api/decks} but are outside the study
 * queue and the workload counters, while deleted decks are not returned at all (so a deleted
 * deck has no representation here).
 *
 * @param cardCount total cards in the deck, archived ones included: they are still the user's
 *                  cards, they are just not being studied
 * @param dueCount  cards due right now, excluding suspended, archived and deleted ones - a
 *                  workload number, so an archived deck reports 0
 * @param newCount  cards that have never been reviewed, same exclusions as {@code dueCount}
 * @param archived  whether the deck is archived; a real {@code boolean}, reflecting the presence
 *                  of {@code archived_at} in the database
 */
public record DeckResponse(
        @JsonProperty("id") long id,
        @JsonProperty("name") String name,
        @JsonProperty("cardCount") long cardCount,
        @JsonProperty("dueCount") long dueCount,
        @JsonProperty("newCount") long newCount,
        @JsonProperty("archived") boolean archived) {
}
