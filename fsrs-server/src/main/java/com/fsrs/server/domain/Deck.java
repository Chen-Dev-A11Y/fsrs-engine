package com.fsrs.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** A named collection of cards owned by one user. */
@TableName("deck")
public class Deck {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private Long createdAt;
    /** Soft-delete marker: {@code null} while the deck is live, else epoch millis of the delete. */
    private Long deletedAt;
    /**
     * Archive marker: {@code null} while the deck is part of the active workload, otherwise the
     * epoch millis at which it was archived.
     *
     * <p><b>This is the source of truth</b> for the deck's archived state. {@code card.archived_at}
     * is a redundant copy maintained in the same transaction; see {@code Card#archivedAt} for why
     * the copy exists and what keeps it honest.
     *
     * <p>Orthogonal to {@link #deletedAt}: an archived deck can also be deleted, and restoring
     * the delete keeps the deck archived.
     */
    private Long archivedAt;

    /** Whether this deck has been soft-deleted. Not named {@code isDeleted} on purpose:
     *  a second boolean getter over a {@code Long} column makes the bean property ambiguous
     *  for MyBatis' OGNL. */
    public boolean deletedFlag() {
        return deletedAt != null;
    }

    /** Whether this deck is archived, i.e. out of the study queue and the workload counters. */
    public boolean archivedFlag() {
        return archivedAt != null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Long getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Long archivedAt) {
        this.archivedAt = archivedAt;
    }
}
