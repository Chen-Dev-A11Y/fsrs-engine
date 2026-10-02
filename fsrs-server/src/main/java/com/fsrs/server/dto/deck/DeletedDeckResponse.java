package com.fsrs.server.dto.deck;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One row of {@code GET /api/decks/deleted} - the recycle bin for decks.
 *
 * <p>{@code cardCount} is the number of cards that would come back with the deck, and
 * {@code reviewsPreserved} the review rows kept for those cards. Both are reported because a
 * deck delete is a cascade: the user is about to restore several things at once, and the row
 * should say how many.
 *
 * @param deletedAt when the deck was deleted, epoch millis; also the sort key
 */
public record DeletedDeckResponse(
        @JsonProperty("id") long id,
        @JsonProperty("name") String name,
        @JsonProperty("cardCount") long cardCount,
        @JsonProperty("deletedAt") Long deletedAt,
        @JsonProperty("reviewsPreserved") long reviewsPreserved) {
}
