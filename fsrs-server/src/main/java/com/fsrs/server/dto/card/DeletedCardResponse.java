package com.fsrs.server.dto.card;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One row of {@code GET /api/cards/deleted} - the recycle bin for cards.
 *
 * <p>Exists because the alternative proved unreliable: the frontend kept the deleted list in
 * {@code localStorage}, so a card deleted from another browser, or by a {@code curl} call, was
 * invisible in the UI and could never be restored. The rows were always in MySQL
 * ({@code deleted_at IS NOT NULL}); this is the read that exposes them.
 *
 * <p>{@code deckName} is joined from {@code deck} rather than left to the client, because the
 * client does not necessarily still have the deck in its list - it may be deleted too.
 *
 * @param deletedAt        when the card was deleted, epoch millis; also the sort key
 * @param reviewsPreserved review rows kept for this card, so the UI can say "history kept"
 */
public record DeletedCardResponse(
        @JsonProperty("id") long id,
        @JsonProperty("deckId") long deckId,
        @JsonProperty("deckName") String deckName,
        @JsonProperty("front") String front,
        @JsonProperty("back") String back,
        @JsonProperty("state") int state,
        @JsonProperty("due") long due,
        @JsonProperty("stability") Double stability,
        @JsonProperty("difficulty") Double difficulty,
        @JsonProperty("reps") int reps,
        @JsonProperty("lapses") int lapses,
        @JsonProperty("suspended") boolean suspended,
        @JsonProperty("deletedAt") Long deletedAt,
        @JsonProperty("reviewsPreserved") long reviewsPreserved) {
}
