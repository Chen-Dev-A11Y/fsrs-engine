package com.fsrs.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fsrs.core.CardState;

/**
 * One flashcard plus its FSRS scheduling state.
 *
 * <p>{@code stability} and {@code difficulty} are boxed on purpose: a NEW card has no
 * memory state yet, and NULL expresses that better than a sentinel such as 0 or 90.
 * {@code dueTs} is epoch millis in UTC.
 */
@TableName("card")
public class Card {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long deckId;
    /** Quoted because {@code FRONT} is not reservable, but quoting keeps the DDL symmetric. */
    @TableField("`front`")
    private String front;
    @TableField("`back`")
    private String back;
    private Integer state;
    private Long dueTs;
    private Double stability;
    private Double difficulty;
    private Integer reps;
    private Integer lapses;
    private Long lastReviewTs;
    private Integer suspended;
    private Long createdAt;
    /**
     * Soft-delete marker: {@code null} while the card is live, otherwise the epoch millis at
     * which it was deleted.
     *
     * <p>A timestamp rather than a flag because it answers "when was this removed?" for free,
     * and because the column is nullable in exactly the way the semantics need: the absence of
     * a value is the normal case.
     */
    private Long deletedAt;
    /**
     * Archive marker: {@code null} while the card is in the active workload, otherwise the
     * epoch millis at which its deck was archived.
     *
     * <p><b>This column is a denormalisation.</b> {@code deck.archived_at} is the source of
     * truth for "is this deck archived"; this copy exists so that the hottest queries in the
     * service - the due queue, the counters, the forecast - can exclude archived work with one
     * indexed predicate instead of a join against {@code deck}. The invariant that keeps the
     * copy honest is that archiving or unarchiving a deck writes the deck row and every one of
     * its card rows in the <em>same transaction</em>, with the same value. Nothing else may
     * write this column.
     *
     * <p>Orthogonal to {@link #deletedAt}: a card can be archived and then deleted, and the
     * restore of a delete clears only {@code deleted_at} - the archived state survives.
     */
    private Long archivedAt;

    /** Whether this card has been soft-deleted. Not named {@code isDeleted}: see below. */
    public boolean deletedFlag() {
        return deletedAt != null;
    }

    /** Whether this card's deck is archived, i.e. this card is outside the active workload. */
    public boolean archivedFlag() {
        return archivedAt != null;
    }

    /**
     * Whether this card is part of the workload the user is studying: not deleted and not
     * archived.
     *
     * <p>The single predicate every workload query is built from, so "which cards count" is
     * answered in one place instead of being re-derived per call site.
     */
    public boolean studyable() {
        return deletedAt == null && archivedAt == null;
    }

    /**
     * Convenience view of the {@code suspended} column.
     *
     * <p>Deliberately <b>not</b> named {@code isSuspended}: MyBatis (and any JavaBeans
     * introspector) already derives a {@code suspended} property from {@code getSuspended()},
     * and a second getter of a different type makes the property ambiguous. OGNL then fails
     * at runtime inside a dynamic SQL tag, which is a remarkably expensive way to learn this.
     */
    public boolean suspendedFlag() {
        return suspended != null && suspended != 0;
    }

    public boolean isNew() {
        return state != null && state == CardState.NEW.code();
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

    public Long getDeckId() {
        return deckId;
    }

    public void setDeckId(Long deckId) {
        this.deckId = deckId;
    }

    public String getFront() {
        return front;
    }

    public void setFront(String front) {
        this.front = front;
    }

    public String getBack() {
        return back;
    }

    public void setBack(String back) {
        this.back = back;
    }

    public Integer getState() {
        return state;
    }

    public void setState(Integer state) {
        this.state = state;
    }

    public Long getDueTs() {
        return dueTs;
    }

    public void setDueTs(Long dueTs) {
        this.dueTs = dueTs;
    }

    public Double getStability() {
        return stability;
    }

    public void setStability(Double stability) {
        this.stability = stability;
    }

    public Double getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Double difficulty) {
        this.difficulty = difficulty;
    }

    public Integer getReps() {
        return reps;
    }

    public void setReps(Integer reps) {
        this.reps = reps;
    }

    public Integer getLapses() {
        return lapses;
    }

    public void setLapses(Integer lapses) {
        this.lapses = lapses;
    }

    public Long getLastReviewTs() {
        return lastReviewTs;
    }

    public void setLastReviewTs(Long lastReviewTs) {
        this.lastReviewTs = lastReviewTs;
    }

    public Integer getSuspended() {
        return suspended;
    }

    public void setSuspended(Integer suspended) {
        this.suspended = suspended;
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
