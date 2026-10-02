package com.fsrs.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fsrs.server.domain.ReviewLog;
import com.fsrs.server.dto.stats.DailyCount;
import com.fsrs.server.dto.stats.HeatmapDay;
import com.fsrs.server.dto.stats.RatingCount;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ReviewLogMapper extends BaseMapper<ReviewLog> {

    @Select("SELECT * FROM review_log WHERE client_review_id = #{clientReviewId}")
    ReviewLog findByClientReviewId(@Param("clientReviewId") String clientReviewId);

    @Select("SELECT COUNT(*) FROM review_log")
    long countAll();

    /**
     * Reviews grouped by <em>user-local</em> day.
     *
     * <p>{@code CONVERT_TZ} needs the MySQL timezone tables to be loaded; when they are not,
     * it returns NULL and silently corrupts the grouping. Adding the pre-computed offset is
     * therefore used instead - the caller passes the offset that applies to this window.
     * Zone offsets change at DST boundaries, which is acceptable for a heatmap of a
     * timezone-aware but DST-free deployment target, and the alternative (grouping in Java)
     * would pull a year of rows over the wire.
     */
    @Select("""
            SELECT DATE_FORMAT(FROM_UNIXTIME(reviewed_at / 1000 + #{offsetSeconds}), '%Y-%m-%d') AS day,
                   COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN rating = 1 THEN 1 ELSE 0 END), 0) AS again,
                   COALESCE(SUM(CASE WHEN rating = 2 THEN 1 ELSE 0 END), 0) AS hard,
                   COALESCE(SUM(CASE WHEN rating = 3 THEN 1 ELSE 0 END), 0) AS good,
                   COALESCE(SUM(CASE WHEN rating = 4 THEN 1 ELSE 0 END), 0) AS easy
            FROM review_log
            WHERE user_id = #{userId} AND reviewed_at >= #{from} AND reviewed_at < #{to}
            GROUP BY day
            ORDER BY day
            """)
    List<HeatmapDay> heatmap(@Param("userId") long userId,
                             @Param("from") long from,
                             @Param("to") long to,
                             @Param("offsetSeconds") int offsetSeconds);

    @Select("""
            SELECT DATE_FORMAT(FROM_UNIXTIME(reviewed_at / 1000 + #{offsetSeconds}), '%Y-%m-%d') AS day,
                   COUNT(*) AS total
            FROM review_log
            WHERE user_id = #{userId} AND reviewed_at >= #{from} AND reviewed_at < #{to}
            GROUP BY day
            ORDER BY day
            """)
    List<DailyCount> dailyCounts(@Param("userId") long userId,
                                 @Param("from") long from,
                                 @Param("to") long to,
                                 @Param("offsetSeconds") int offsetSeconds);

    /** Oldest-first review history of a single card; the source of its interval-evolution chart. */
    @Select("""
            SELECT * FROM review_log
            WHERE card_id = #{cardId} AND user_id = #{userId}
            ORDER BY reviewed_at ASC, id ASC
            """)
    List<ReviewLog> timeline(@Param("cardId") long cardId, @Param("userId") long userId);

    /** Rating distribution over a window; the numerator/denominator of the retention metric. */
    @Select("""
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN rating >= 3 THEN 1 ELSE 0 END), 0) AS recalled
            FROM review_log
            WHERE user_id = #{userId} AND reviewed_at >= #{from} AND reviewed_at < #{to}
            """)
    RatingCount ratingCounts(@Param("userId") long userId,
                             @Param("from") long from,
                             @Param("to") long to);

    /**
     * Number of stored reviews of one card.
     *
     * <p>Used to report {@code reviewsPreserved} after a delete. Nothing in this mapper ever
     * deletes a row: {@code review_log} is the input of the parameter optimizer, so a card that
     * is removed disappears from the card list while its history stays available for fitting.
     */
    @Select("SELECT COUNT(*) FROM review_log WHERE card_id = #{cardId} AND user_id = #{userId}")
    long countByCardId(@Param("cardId") long cardId, @Param("userId") long userId);

    /** Reviews of every card of one deck; the count a deck delete reports as preserved. */
    @Select("SELECT COUNT(*) FROM review_log WHERE user_id = #{userId} AND card_id IN (SELECT id FROM card WHERE deck_id = #{deckId})")
    long countByDeckId(@Param("userId") long userId, @Param("deckId") long deckId);
}
