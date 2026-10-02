package com.fsrs.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Per-user scheduling settings: the 21 FSRS-6 weights plus the retention target.
 *
 * <p>Weights are stored as a comma-separated string rather than a JSON column because
 * MySQL 8 JSON would still hand back a string, and a plain string keeps the row
 * readable in the {@code mysql} client. {@code source} records where the numbers came
 * from ({@code default} or {@code optimizer}) so a personalised profile can always be
 * distinguished from the shipped defaults.
 */
@TableName("scheduler_profile")
public class SchedulerProfile {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String weights;
    private Double desiredRetention;
    private Double maximumIntervalDays;
    private String source;
    private Long updatedAt;

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

    public String getWeights() {
        return weights;
    }

    public void setWeights(String weights) {
        this.weights = weights;
    }

    public Double getDesiredRetention() {
        return desiredRetention;
    }

    public void setDesiredRetention(Double desiredRetention) {
        this.desiredRetention = desiredRetention;
    }

    public Double getMaximumIntervalDays() {
        return maximumIntervalDays;
    }

    public void setMaximumIntervalDays(Double maximumIntervalDays) {
        this.maximumIntervalDays = maximumIntervalDays;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
