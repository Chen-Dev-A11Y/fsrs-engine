package com.fsrs.server.service;

import com.fsrs.server.common.ApiException;
import com.fsrs.server.common.TimeUtils;
import com.fsrs.server.domain.User;
import com.fsrs.server.dto.stats.HeatmapCell;
import com.fsrs.server.dto.stats.HeatmapDay;
import com.fsrs.server.dto.stats.RatingCount;
import com.fsrs.server.dto.stats.StatsSummaryResponse;
import com.fsrs.server.mapper.CardMapper;
import com.fsrs.server.mapper.DailyStatMapper;
import com.fsrs.server.mapper.DeckMapper;
import com.fsrs.server.mapper.ReviewLogMapper;
import com.fsrs.server.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Analytics built on {@code review_log}, {@code card} and {@code daily_stat}.
 *
 * <p>Every aggregation happens on the <em>user's</em> local day, never on the server's and
 * never on UTC. That single decision is why a review at 23:50 lands on the right heatmap
 * square and why the streak is not off by one for users east of the server.
 *
 * <p>Retention is "share of reviews that ended in recall". It is computed over review rows
 * rather than over cards, which is the definition the frontend's label implies; the more
 * nuanced card-level "true retention" would need a per-review ordering window and is not
 * worth the extra complexity for a dashboard number.
 */
@Service
public class StatsService {

    private static final int MAX_HEATMAP_DAYS = 3660;
    /** Retention windows: "last week" and "last month" as the learner experiences them. */
    private static final int RETENTION_SHORT_DAYS = 7;
    private static final int RETENTION_LONG_DAYS = 30;
    private static final int FORECAST_DAYS = 30;

    private final UserMapper userMapper;
    private final CardMapper cardMapper;
    private final DeckMapper deckMapper;
    private final ReviewLogMapper reviewLogMapper;
    private final DailyStatMapper dailyStatMapper;

    public StatsService(UserMapper userMapper,
                        CardMapper cardMapper,
                        DeckMapper deckMapper,
                        ReviewLogMapper reviewLogMapper,
                        DailyStatMapper dailyStatMapper) {
        this.userMapper = userMapper;
        this.cardMapper = cardMapper;
        this.deckMapper = deckMapper;
        this.reviewLogMapper = reviewLogMapper;
        this.dailyStatMapper = dailyStatMapper;
    }

    public List<HeatmapCell> heatmap(long userId, int days) {
        User user = requireUser(userId);
        ZoneId zone = TimeUtils.zone(user.getTimezone());
        long now = System.currentTimeMillis();
        int window = Math.max(1, Math.min(days, MAX_HEATMAP_DAYS));
        LocalDate today = TimeUtils.localDate(now, zone);
        long from = TimeUtils.startOfDay(today.minusDays(window - 1L), zone);
        long to = TimeUtils.startOfDay(today.plusDays(1), zone);

        List<HeatmapDay> rows = reviewLogMapper.heatmap(userId, from, to, zoneOffsetSeconds(zone, from, to));
        List<HeatmapCell> cells = new ArrayList<>(rows.size());
        for (HeatmapDay row : rows) {
            cells.add(new HeatmapCell(row.day(), row.total(), row.again(), row.hard(), row.good(), row.easy()));
        }
        return cells;
    }

    public StatsSummaryResponse summary(long userId) {
        User user = requireUser(userId);
        ZoneId zone = TimeUtils.zone(user.getTimezone());
        long now = System.currentTimeMillis();
        LocalDate today = TimeUtils.localDate(now, zone);

        // Cards the user owns, archived ones included: archiving removes work, not ownership.
        long totalCards = cardMapper.countAll(userId);
        // "dueToday" is intentionally "due right now", the same number the study queue acts
        // on: a dashboard that says 12 when the queue hands out 3 is worse than useless. It
        // excludes archived cards for the same reason - they will not be handed out.
        long dueToday = cardMapper.countDue(userId, null, now);
        long newToday = dailyStatMapper.newCardsOn(userId, today);

        // The two archive counters are what the dashboard needs to say "N decks / M cards are
        // archived", since those cards are neither gone nor in the workload.
        long archivedDecks = deckMapper.countArchived(userId);
        long archivedCards = cardMapper.countArchived(userId);

        double retention7d = retention(userId, zone, today, RETENTION_SHORT_DAYS, now);
        double retention30d = retention(userId, zone, today, RETENTION_LONG_DAYS, now);
        long streak = streakDays(userId, today);

        List<StatsSummaryResponse.ForecastEntry> forecast = forecast(userId, zone, today);

        return new StatsSummaryResponse(
                totalCards,
                reviewLogMapper.countAll(),
                dueToday,
                newToday,
                retention7d,
                retention30d,
                streak,
                archivedDecks,
                archivedCards,
                forecast);
    }

    private double retention(long userId, ZoneId zone, LocalDate today, int days, long now) {
        long from = TimeUtils.startOfDay(today.minusDays(days - 1L), zone);
        RatingCount counts = reviewLogMapper.ratingCounts(userId, from, now + 1);
        return counts == null ? 0.0 : counts.retention();
    }

    /**
     * Consecutive days with at least one review, ending today (or yesterday, when today has
     * none yet - a streak must not break just because the learner has not started studying).
     *
     * <p>Read from {@code daily_stat} instead of scanning {@code review_log}: the streak is
     * shown on every dashboard load and a year of logs should not be aggregated on the
     * request path.
     */
    private long streakDays(long userId, LocalDate today) {
        List<LocalDate> active = dailyStatMapper.recentActiveDays(userId, today.minusDays(400));
        if (active.isEmpty()) {
            return 0;
        }
        java.util.Set<LocalDate> days = new java.util.HashSet<>(active);
        LocalDate cursor = days.contains(today) ? today : today.minusDays(1);
        long streak = 0;
        while (days.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    /**
     * Expected load per local day for the next 30 days.
     *
     * <p>One query per day is a deliberate trade: a single grouped query would need the
     * timezone conversion to happen inside MySQL, which depends on the server's timezone
     * tables being loaded. Thirty indexed range counts stay well under a millisecond each and
     * are correct regardless of how MySQL is configured.
     */
    private List<StatsSummaryResponse.ForecastEntry> forecast(long userId, ZoneId zone, LocalDate today) {
        List<StatsSummaryResponse.ForecastEntry> forecast = new ArrayList<>(FORECAST_DAYS);
        for (int i = 0; i < FORECAST_DAYS; i++) {
            LocalDate day = today.plusDays(i);
            long from = TimeUtils.startOfDay(day, zone);
            long to = TimeUtils.startOfDay(day.plusDays(1), zone);
            forecast.add(new StatsSummaryResponse.ForecastEntry(day.toString(),
                    cardMapper.countDueBetween(userId, from, to)));
        }
        return forecast;
    }

    /**
     * Zone offset used to bucket reviews into local days.
     *
     * <p>Sampled at the middle of the window. A single fixed offset is exact for every zone
     * without DST - including the {@code Asia/Shanghai} default - and is off by at most one
     * hour for one hour-long window per year in a DST zone, which cannot move a review across
     * a day boundary by more than that same hour. The alternative, converting each row in
     * Java, would transfer a year of reviews to group them.
     */
    private int zoneOffsetSeconds(ZoneId zone, long from, long to) {
        long middle = from + (to - from) / 2;
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(middle), zone).getOffset().getTotalSeconds();
    }

    private User requireUser(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw ApiException.unauthorized("account no longer exists");
        }
        return user;
    }
}
