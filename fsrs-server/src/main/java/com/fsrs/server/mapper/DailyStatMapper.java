package com.fsrs.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fsrs.server.domain.DailyStat;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface DailyStatMapper extends BaseMapper<DailyStat> {

    /**
     * Increments one day's counters in a single statement.
     *
     * <p>Read-modify-write would be wrong here: two concurrent reviews are the normal
     * case, and one of them would lose its increment. The unique key
     * {@code (user_id, stat_date)} turns the upsert into an atomic increment.
     */
    @Insert("""
            INSERT INTO daily_stat (user_id, stat_date, reviews, again, hard, good, easy, new_cards)
            VALUES (#{userId}, #{statDate}, 1,
                    #{again}, #{hard}, #{good}, #{easy}, #{newCards})
            ON DUPLICATE KEY UPDATE
                reviews   = reviews + 1,
                again     = again + VALUES(again),
                hard      = hard + VALUES(hard),
                good      = good + VALUES(good),
                easy      = easy + VALUES(easy),
                new_cards = new_cards + VALUES(new_cards)
            """)
    int accumulate(@Param("userId") long userId,
                   @Param("statDate") LocalDate statDate,
                   @Param("again") int again,
                   @Param("hard") int hard,
                   @Param("good") int good,
                   @Param("easy") int easy,
                   @Param("newCards") int newCards);

    @Select("""
            SELECT stat_date FROM daily_stat
            WHERE user_id = #{userId} AND reviews > 0 AND stat_date >= #{from}
            ORDER BY stat_date DESC
            """)
    List<LocalDate> recentActiveDays(@Param("userId") long userId, @Param("from") LocalDate from);

    /** Rolls individual review rows into {@code daily_stat}; idempotent, so it can re-run after a crash. */
    @Insert("""
            INSERT INTO daily_stat (user_id, stat_date, reviews, again, hard, good, easy, new_cards)
            SELECT user_id,
                   DATE_FORMAT(FROM_UNIXTIME(reviewed_at / 1000 + #{offsetSeconds}), '%Y-%m-%d') AS d,
                   COUNT(*),
                   SUM(CASE WHEN rating = 1 THEN 1 ELSE 0 END),
                   SUM(CASE WHEN rating = 2 THEN 1 ELSE 0 END),
                   SUM(CASE WHEN rating = 3 THEN 1 ELSE 0 END),
                   SUM(CASE WHEN rating = 4 THEN 1 ELSE 0 END),
                   0
            FROM review_log
            WHERE user_id = #{userId} AND reviewed_at >= #{from} AND reviewed_at < #{to}
            GROUP BY user_id, d
            ON DUPLICATE KEY UPDATE
                reviews = VALUES(reviews),
                again   = VALUES(again),
                hard    = VALUES(hard),
                good    = VALUES(good),
                easy    = VALUES(easy)
            """)
    int rollup(@Param("userId") long userId,
               @Param("from") long from,
               @Param("to") long to,
               @Param("offsetSeconds") int offsetSeconds);

    @Select("""
            SELECT COALESCE(SUM(new_cards), 0) FROM daily_stat
            WHERE user_id = #{userId} AND stat_date = #{statDate}
            """)
    long newCardsOn(@Param("userId") long userId, @Param("statDate") LocalDate statDate);

    @Insert("""
            INSERT INTO daily_stat (user_id, stat_date, reviews, again, hard, good, easy, new_cards)
            VALUES (#{userId}, #{statDate}, 0, 0, 0, 0, 0, #{newCards})
            ON DUPLICATE KEY UPDATE new_cards = new_cards + VALUES(new_cards)
            """)
    int addNewCards(@Param("userId") long userId, @Param("statDate") LocalDate statDate,
                    @Param("newCards") int newCards);
}
