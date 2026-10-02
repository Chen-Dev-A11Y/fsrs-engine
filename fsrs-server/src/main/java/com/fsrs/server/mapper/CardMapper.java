package com.fsrs.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fsrs.server.domain.Card;
import com.fsrs.server.dto.card.CardIdDue;
import com.fsrs.server.dto.card.DeletedCardResponse;
import com.fsrs.server.dto.stats.DeckCountRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Card reads and writes.
 *
 * <h2>Two markers, two questions</h2>
 * Deletion is a soft delete ({@code deleted_at}) and archiving is a separate marker
 * ({@code archived_at}); they are orthogonal, so every statement here has to say which question
 * it is answering:
 * <ul>
 *   <li><b>Workload</b> - "what may I hand out, count or grade right now?" - filters
 *       {@code deleted_at IS NULL AND archived_at IS NULL}. An archived card still exists and is
 *       still counted as a card; it is simply not work.</li>
 *   <li><b>Existence and history</b> - "does this row exist, and what happened to it?" - filters
 *       only {@code deleted_at IS NULL}, or nothing at all for the two statements that must see a
 *       deleted row to do their job ({@link #findByIdIncludingDeleted} and {@link #restore}).</li>
 * </ul>
 * Getting this wrong in one statement is the failure mode of both features: a missing
 * {@code deleted_at} filter shows the user a card they removed, and a missing
 * {@code archived_at} filter puts an archived deck back into the queue on the next rebuild.
 */
public interface CardMapper extends BaseMapper<Card> {

    /**
     * Card / due / new counts per deck in a single round trip.
     *
     * <p>The three counts are conditional sums rather than three queries, so listing decks
     * stays a two-query endpoint (decks + counts) no matter how many cards exist.
     *
     * <p>{@code cardCount} deliberately counts <em>archived</em> cards too: the deck list shows
     * "N cards" for a deck the user still owns, and archiving is "I am not studying this now",
     * not "these cards are gone". {@code dueCount} / {@code newCount} are workload numbers, so
     * they exclude archived cards - an archived deck must report zero work.
     *
     * <p>Soft-deleted cards are excluded from all three: a removed card is not in the deck the
     * user is looking at.
     */
    @Select("""
            SELECT c.deck_id AS deckId,
                   COUNT(*) AS cardCount,
                   COALESCE(SUM(CASE WHEN c.suspended = 0 AND c.archived_at IS NULL AND c.due_ts <= #{now} THEN 1 ELSE 0 END), 0) AS dueCount,
                   COALESCE(SUM(CASE WHEN c.suspended = 0 AND c.archived_at IS NULL AND c.state = 0 THEN 1 ELSE 0 END), 0) AS newCount
            FROM card c
            WHERE c.user_id = #{userId} AND c.deleted_at IS NULL
            GROUP BY c.deck_id
            """)
    List<DeckCountRow> countsByDeck(@Param("userId") long userId, @Param("now") long now);

    /**
     * Due count for one deck, or for the whole account when {@code deckId} is null.
     *
     * <p>Workload: archived cards are not due.
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM card
            WHERE user_id = #{userId} AND deleted_at IS NULL AND archived_at IS NULL
              AND suspended = 0 AND state &lt;&gt; 0 AND due_ts &lt;= #{now}
              <if test="deckId != null"> AND deck_id = #{deckId} </if>
            </script>
            """)
    long countDue(@Param("userId") long userId, @Param("deckId") Long deckId, @Param("now") long now);

    /** New-card count for one deck or the whole account; workload, so archived cards are out. */
    @Select("""
            <script>
            SELECT COUNT(*) FROM card
            WHERE user_id = #{userId} AND deleted_at IS NULL AND archived_at IS NULL
              AND suspended = 0 AND state = 0
              <if test="deckId != null"> AND deck_id = #{deckId} </if>
            </script>
            """)
    long countNew(@Param("userId") long userId, @Param("deckId") Long deckId);

    /**
     * Every card the user still owns, archived ones included.
     *
     * <p>Not a workload number: with {@code deleted_at IS NULL} the count is "cards in my
     * collection", and archiving does not remove a card from the collection.
     */
    @Select("SELECT COUNT(*) FROM card WHERE user_id = #{userId} AND deleted_at IS NULL")
    long countAll(@Param("userId") long userId);

    /** Live cards of one deck, archived ones included; used by the archive/unarchive cascade. */
    @Select("SELECT * FROM card WHERE deck_id = #{deckId} AND user_id = #{userId} AND deleted_at IS NULL")
    List<Card> findLiveByDeck(@Param("userId") long userId, @Param("deckId") long deckId);

    /** Card ids of one deck, live ones only; collected before a deck delete so Redis can be cleaned. */
    @Select("SELECT id FROM card WHERE deck_id = #{deckId} AND user_id = #{userId} AND deleted_at IS NULL")
    List<Long> findIdsByDeck(@Param("userId") long userId, @Param("deckId") long deckId);

    /**
     * Due cards inside a half-open local-day window, used by the forecast.
     *
     * <p>Reviews are subject to fuzzing and to cards being suspended, so a forecast is an
     * estimate by construction. It is still derived from the source of truth rather than
     * from Redis, which is what makes it correct right after a rebuild.
     *
     * <p>Workload: an archived card will not be reviewed, so it must not appear in the expected
     * load - otherwise a user who archived everything would keep seeing a full calendar.
     *
     * <p>The comparison operators below are literal {@code >=} and {@code <}. This statement
     * is deliberately <em>not</em> wrapped in a {@code <script>} element, so MyBatis passes it
     * through as raw SQL and never decodes XML entities - writing {@code &gt;=} here compiles
     * fine but fails at runtime with a MySQL syntax error near {@code '= <timestamp>'}, which
     * takes down {@code GET /api/stats/summary}.
     */
    @Select("""
            SELECT COUNT(*) FROM card
            WHERE user_id = #{userId} AND deleted_at IS NULL AND archived_at IS NULL AND suspended = 0
              AND due_ts >= #{from} AND due_ts < #{to}
            """)
    long countDueBetween(@Param("userId") long userId, @Param("from") long from, @Param("to") long to);

    /**
     * {@code (id, due_ts)} of every card that belongs in the due index: live, unarchived and
     * unsuspended; the input of a due-queue rebuild.
     *
     * <p>Both filters are load-bearing. The rebuild is a full overwrite of the Redis index, so a
     * card left in this result set is handed back out at the next tick - which means omitting
     * {@code archived_at IS NULL} here would silently un-archive the deck a few minutes after
     * the user archived it, and omitting {@code deleted_at IS NULL} would resurrect a deleted
     * card.
     */
    @Select("SELECT id AS id, due_ts AS dueTs FROM card "
            + "WHERE user_id = #{userId} AND deleted_at IS NULL AND archived_at IS NULL AND suspended = 0")
    List<CardIdDue> findAllDueRows(@Param("userId") long userId);

    /** Users that own at least one card in the workload; the periodic rebuild iterates over these. */
    @Select("SELECT DISTINCT user_id FROM card WHERE deleted_at IS NULL AND archived_at IS NULL")
    List<Long> selectDistinctUserIds();

    /**
     * {@code deck_id} of one card that may be indexed, or {@code null} when the card does not
     * exist, is not this user's, has been soft-deleted, is archived, or sits in an archived deck.
     *
     * <p>Four conditions rather than three because archiving is denormalised: a card whose own
     * {@code archived_at} is null can still belong to a deck whose {@code archived_at} is set -
     * for instance after a restore, which deliberately does not touch the archive markers - and
     * indexing it would put an archived deck back into the queue.
     */
    @Select("""
            SELECT c.deck_id FROM card c
            JOIN deck d ON d.id = c.deck_id
            WHERE c.id = #{id} AND c.user_id = #{userId}
              AND c.deleted_at IS NULL AND c.archived_at IS NULL
              AND d.deleted_at IS NULL AND d.archived_at IS NULL
            """)
    Long findIndexableDeckId(@Param("id") long id, @Param("userId") long userId);

    /**
     * One card by id regardless of deletion or archiving, or {@code null} when it is not this
     * user's.
     *
     * <p>The single read that is allowed to see a deleted row: the timeline of a removed card is
     * still meaningful data (its reviews were kept on purpose), and the endpoint returns it
     * rather than a 404 so that a bookmarked link keeps working.
     */
    @Select("SELECT * FROM card WHERE id = #{id} AND user_id = #{userId}")
    Card findByIdIncludingDeleted(@Param("id") Long id, @Param("userId") long userId);

    /**
     * Suspends or resumes one card.
     *
     * <p>A targeted {@code SET suspended} rather than an entity update, so the statement cannot
     * touch the memory state by accident. {@code deleted_at IS NULL AND archived_at IS NULL}
     * makes "resume a card that was deleted in another tab, or that belongs to an archived deck"
     * a no-op instead of an edit to a row the user cannot see.
     */
    @Update("UPDATE card SET suspended = #{suspended} "
            + "WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL AND archived_at IS NULL")
    int setSuspended(@Param("id") long id, @Param("userId") long userId, @Param("suspended") int suspended);

    /**
     * Soft-deletes one card.
     *
     * <p>The {@code deleted_at IS NULL} predicate is part of the statement, not a prior check,
     * so two concurrent deletes cannot both report that they removed a row - the second affects
     * zero rows and is answered as "already gone". The review history is untouched by design:
     * it is the optimizer's training data.
     *
     * <p>{@code archived_at} is deliberately not mentioned: delete and archive are orthogonal,
     * and an archived card that is deleted must still look archived if it is ever restored.
     *
     * @return 1 when this call marked a live card as deleted, 0 when it was already gone
     */
    @Update("UPDATE card SET deleted_at = #{now} "
            + "WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL")
    int softDelete(@Param("id") long id, @Param("userId") long userId, @Param("now") long now);

    /**
     * Soft-deletes every live card of one deck with the same timestamp.
     *
     * <p>One statement for the cascade, and one shared timestamp so the deck restore can
     * recognise exactly the cards that went down with this delete.
     *
     * @return number of cards marked
     */
    @Update("UPDATE card SET deleted_at = #{now} "
            + "WHERE deck_id = #{deckId} AND user_id = #{userId} AND deleted_at IS NULL")
    int softDeleteByDeck(@Param("userId") long userId, @Param("deckId") long deckId, @Param("now") long now);

    /**
     * Restores one card.
     *
     * <p>Only {@code deleted_at} is cleared. {@code archived_at} is left exactly as it was, so a
     * deck that was archived before it was deleted comes back archived - the two markers are
     * orthogonal and a restore undoes one of them, not both.
     *
     * @return 1 when a deleted card was brought back, 0 when it was not deleted (or not theirs)
     */
    @Update("UPDATE card SET deleted_at = NULL "
            + "WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NOT NULL")
    int restore(@Param("id") long id, @Param("userId") long userId);

    /**
     * Restores the cards a deck delete took down: this user's cards of this deck whose
     * {@code deleted_at} equals the deck's own delete timestamp.
     *
     * <p>Matching the timestamp, rather than clearing every deleted card in the deck, is what
     * makes the rule predictable: a card the user deleted individually <em>before</em> deleting
     * the deck stays deleted, because the user never asked to bring it back.
     *
     * @return number of cards restored
     */
    @Update("""
            UPDATE card SET deleted_at = NULL
            WHERE deck_id = #{deckId} AND user_id = #{userId} AND deleted_at = #{deletedAt}
            """)
    int restoreByDeck(@Param("userId") long userId, @Param("deckId") long deckId, @Param("deletedAt") long deletedAt);

    /**
     * Sets the redundant {@code archived_at} on every live card of a deck.
     *
     * <p>Called in the same transaction as the deck update, with the same value, which is the
     * invariant that makes the denormalised column trustworthy. Soft-deleted cards are skipped:
     * they are not part of the workload and their own restore will not touch the marker either.
     *
     * @param archivedAt epoch millis when archiving, or {@code null} to unarchive
     * @return number of card rows updated
     */
    @Update("""
            <script>
            UPDATE card SET archived_at =
              <choose><when test="archivedAt == null">NULL</when><otherwise>#{archivedAt}</otherwise></choose>
            WHERE deck_id = #{deckId} AND user_id = #{userId} AND deleted_at IS NULL
            </script>
            """)
    int setArchivedByDeck(@Param("userId") long userId,
                          @Param("deckId") long deckId,
                          @Param("archivedAt") Long archivedAt);

    /** Number of archived cards the user still owns; a card of a deleted deck does not count. */
    @Select("SELECT COUNT(*) FROM card WHERE user_id = #{userId} AND deleted_at IS NULL AND archived_at IS NOT NULL")
    long countArchived(@Param("userId") long userId);

    /**
     * The user's deleted cards, newest delete first - the recycle bin behind
     * {@code GET /api/cards/deleted}.
     *
     * <p>Two details carry the meaning of the list:
     * <ul>
     *   <li>{@code d.deleted_at IS NULL} on the joined deck: a card whose <em>deck</em> is
     *       deleted is <b>not</b> listed on its own. Deleting a 16-card deck must not add 16 card
     *       rows to the panel next to the one deck row, or the user would think they have to
     *       restore them one by one - restoring the deck brings them all back. The card is not
     *       lost: it comes back with its deck.</li>
     *   <li>{@code deckName} is joined rather than resolved by the caller, and an INNER JOIN is
     *       correct here because a live card always has a deck row.</li>
     * </ul>
     *
     * <p>{@code reviewsPreserved} is a correlated subquery: it is the count the UI shows as
     * "history kept", and it must stay the number that would still exist after a restore.
     */
    @Select("""
            SELECT c.id            AS id,
                   c.deck_id       AS deckId,
                   d.name          AS deckName,
                   c.front         AS front,
                   c.back          AS back,
                   c.state         AS state,
                   c.due_ts        AS due,
                   c.stability     AS stability,
                   c.difficulty    AS difficulty,
                   c.reps          AS reps,
                   c.lapses        AS lapses,
                   c.suspended     AS suspended,
                   c.deleted_at    AS deletedAt,
                   (SELECT COUNT(*) FROM review_log r WHERE r.card_id = c.id AND r.user_id = c.user_id)
                                   AS reviewsPreserved
            FROM card c
            JOIN deck d ON d.id = c.deck_id
            WHERE c.user_id = #{userId} AND c.deleted_at IS NOT NULL AND d.deleted_at IS NULL
            ORDER BY c.deleted_at DESC, c.id DESC
            """)
    List<DeletedCardResponse> findDeletedCards(@Param("userId") long userId);
}
