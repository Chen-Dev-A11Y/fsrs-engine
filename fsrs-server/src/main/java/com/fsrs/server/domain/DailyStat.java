package com.fsrs.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;

/**
 * Per-user, per-local-day review counters.
 *
 * <p>A materialised rollup rather than a live aggregation over {@code review_log}: the
 * heatmap and the streak are read on every dashboard load, and scanning a year of logs
 * each time would make the cheap query expensive. {@code stat_date} is the
 * <em>user-local</em> day, so a review at 23:50 local time lands on the right square.
 */
@TableName("daily_stat")
public class DailyStat {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private LocalDate statDate;
    private Integer reviews;
    private Integer again;
    private Integer hard;
    private Integer good;
    private Integer easy;
    private Integer newCards;

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

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public Integer getReviews() {
        return reviews;
    }

    public void setReviews(Integer reviews) {
        this.reviews = reviews;
    }

    public Integer getAgain() {
        return again;
    }

    public void setAgain(Integer again) {
        this.again = again;
    }

    public Integer getHard() {
        return hard;
    }

    public void setHard(Integer hard) {
        this.hard = hard;
    }

    public Integer getGood() {
        return good;
    }

    public void setGood(Integer good) {
        this.good = good;
    }

    public Integer getEasy() {
        return easy;
    }

    public void setEasy(Integer easy) {
        this.easy = easy;
    }

    public Integer getNewCards() {
        return newCards;
    }

    public void setNewCards(Integer newCards) {
        this.newCards = newCards;
    }
}
