package com.fsrs.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Append-only record of one graded review.
 *
 * <p>This is the largest table and the only input of the parameter optimizer, which is
 * why every intermediate value the model used ({@code sBefore}, {@code dBefore},
 * {@code rBefore}, {@code elapsedDays}, {@code scheduledDays}) is stored instead of
 * being recomputed later: recomputing would silently change history whenever the
 * weights change.
 */
@TableName("review_log")
public class ReviewLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long cardId;
    private Long userId;
    /** 1 Again, 2 Hard, 3 Good, 4 Easy. */
    private Integer rating;
    private Long reviewedAt;
    private Double elapsedDays;
    private Double scheduledDays;
    private Integer stateBefore;
    private Double sBefore;
    private Double dBefore;
    private Double rBefore;
    private Integer durationMs;
    /** Client-supplied idempotency key; a UNIQUE index makes replays impossible. */
    private String clientReviewId;
    private Long createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCardId() {
        return cardId;
    }

    public void setCardId(Long cardId) {
        this.cardId = cardId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public Long getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Long reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Double getElapsedDays() {
        return elapsedDays;
    }

    public void setElapsedDays(Double elapsedDays) {
        this.elapsedDays = elapsedDays;
    }

    public Double getScheduledDays() {
        return scheduledDays;
    }

    public void setScheduledDays(Double scheduledDays) {
        this.scheduledDays = scheduledDays;
    }

    public Integer getStateBefore() {
        return stateBefore;
    }

    public void setStateBefore(Integer stateBefore) {
        this.stateBefore = stateBefore;
    }

    public Double getSBefore() {
        return sBefore;
    }

    public void setSBefore(Double sBefore) {
        this.sBefore = sBefore;
    }

    public Double getDBefore() {
        return dBefore;
    }

    public void setDBefore(Double dBefore) {
        this.dBefore = dBefore;
    }

    public Double getRBefore() {
        return rBefore;
    }

    public void setRBefore(Double rBefore) {
        this.rBefore = rBefore;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public String getClientReviewId() {
        return clientReviewId;
    }

    public void setClientReviewId(String clientReviewId) {
        this.clientReviewId = clientReviewId;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
