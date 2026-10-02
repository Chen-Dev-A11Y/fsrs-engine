package com.fsrs.server.dto.deck;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code DELETE /api/decks/{id}}.
 *
 * <p>The two counts describe a soft delete: {@code cardsDeleted} cards were marked removed
 * (together with the deck), and {@code reviewsPreserved} review rows were deliberately left
 * alone because they are the optimizer's training data. Both are 0 with
 * {@code alreadyGone = true} when there was nothing left to remove.
 *
 * @param cardsDeleted     live cards marked deleted together with the deck
 * @param reviewsPreserved review rows kept for the optimizer
 */
public record DeleteDeckResponse(
        @JsonProperty("ok") boolean ok,
        @JsonProperty("alreadyGone") boolean alreadyGone,
        @JsonProperty("cardsDeleted") long cardsDeleted,
        @JsonProperty("reviewsPreserved") long reviewsPreserved) {

    public static DeleteDeckResponse ofDeleted(long cardsDeleted, long reviewsPreserved) {
        return new DeleteDeckResponse(true, false, cardsDeleted, reviewsPreserved);
    }

    public static DeleteDeckResponse ofAlreadyGone() {
        return new DeleteDeckResponse(true, true, 0L, 0L);
    }
}
