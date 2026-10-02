package com.fsrs.server.dto.card;

/**
 * Lightweight {@code (cardId, dueTs)} row used when rebuilding the Redis due index,
 * where loading whole card rows would be wasteful.
 */
public class CardIdDue {

    private Long id;
    private Long dueTs;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDueTs() {
        return dueTs;
    }

    public void setDueTs(Long dueTs) {
        this.dueTs = dueTs;
    }
}
