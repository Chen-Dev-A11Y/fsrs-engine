package com.fsrs.server.dto.stats;

/** Per-deck card / due / new counts, produced by one grouped query. */
public record DeckCountRow(Long deckId, Long cardCount, Long dueCount, Long newCount) {
}
