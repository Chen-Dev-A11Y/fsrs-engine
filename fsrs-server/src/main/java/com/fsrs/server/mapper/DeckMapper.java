package com.fsrs.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fsrs.server.domain.Deck;
import com.fsrs.server.dto.deck.DeletedDeckResponse;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Deck reads and writes.
 *
 * <p>Deletion is a soft delete ({@code deleted_at}) and archiving is an independent marker
 * ({@code archived_at}), so the statements here answer two different questions:
 * "is this deck still in my collection?" (live = {@code deleted_at IS NULL}) and "is it part of
 * my current workload?" (active = also {@code archived_at IS NULL}). Archiving is <b>not</b>
 * removal: an archived deck stays in {@code GET /api/decks}, so the list statements filter only
 * {@code deleted_at}. {@link #findByIdIncludingDeleted} deliberately filters nothing, because
 * restoring a deck has to see the row it is about to bring back.
 */
public interface DeckMapper extends BaseMapper<Deck> {

    /** One live deck by id (archived or not), or {@code null} when it is missing or not this
     *  user's. Archived decks are returned on purpose: archiving does not remove a deck. */
    @Select("SELECT * FROM deck WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL")
    Deck findLive(@Param("id") long id, @Param("userId") long userId);

    /** Every live deck of one user, archived ones included, ordered by id. */
    @Select("SELECT * FROM deck WHERE user_id = #{userId} AND deleted_at IS NULL ORDER BY id ASC")
    List<Deck> findLiveByUser(@Param("userId") long userId);

    /** One deck regardless of deletion, or {@code null} when it is not this user's. */
    @Select("SELECT * FROM deck WHERE id = #{id} AND user_id = #{userId}")
    Deck findByIdIncludingDeleted(@Param("id") long id, @Param("userId") long userId);

    /**
     * Renames one live deck.
     *
     * <p>A targeted {@code SET name} rather than an entity update through {@code updateById},
     * because the assembled entity would rewrite {@code user_id} and {@code created_at} with
     * whatever the service read a moment earlier - harmless here, but it turns a rename into a
     * three-column optimistic overwrite for no reason.
     *
     * @return 1 when the deck was renamed, 0 when it does not exist or is not this user's
     */
    @Update("UPDATE deck SET name = #{name} WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL")
    int rename(@Param("id") long id, @Param("userId") long userId, @Param("name") String name);

    /**
     * Soft-deletes one live deck.
     *
     * @return 1 when this call removed a live deck, 0 when it was already gone - which is what
     *         keeps a repeated delete idempotent under concurrency as well as in sequence
     */
    @Update("UPDATE deck SET deleted_at = #{now} WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL")
    int softDelete(@Param("id") long id, @Param("userId") long userId, @Param("now") long now);

    /**
     * Restores one deleted deck.
     *
     * <p>Only {@code deleted_at} is cleared: {@code archived_at} is a different axis and a
     * restore must not silently un-archive a deck the user had archived before deleting it.
     *
     * @return 1 when a deleted deck was brought back, 0 when it was not deleted (or not theirs)
     */
    @Update("UPDATE deck SET deleted_at = NULL WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NOT NULL")
    int restore(@Param("id") long id, @Param("userId") long userId);

    /**
     * Archives one live deck by setting {@code archived_at} only when it is currently null.
     *
     * <p>The {@code archived_at IS NULL} predicate is what makes archiving idempotent
     * <em>without refreshing the timestamp</em>: archiving an already-archived deck affects zero
     * rows and leaves the original instant in place, so "when did I archive this?" keeps its
     * answer. The alternative - overwriting it - would make the value depend on how many times
     * the button was pressed.
     *
     * <p>Soft-deleted decks cannot be archived: the row count is 0 and the caller answers 404.
     *
     * @return 1 when this call archived the deck, 0 when it was already archived or not theirs
     */
    @Update("UPDATE deck SET archived_at = #{now} "
            + "WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL AND archived_at IS NULL")
    int archive(@Param("id") long id, @Param("userId") long userId, @Param("now") long now);

    /**
     * Unarchives one archived live deck.
     *
     * @return 1 when this call unarchived the deck, 0 when it was not archived or not theirs
     */
    @Update("UPDATE deck SET archived_at = NULL "
            + "WHERE id = #{id} AND user_id = #{userId} AND deleted_at IS NULL AND archived_at IS NOT NULL")
    int unarchive(@Param("id") long id, @Param("userId") long userId);

    /** Number of live archived decks, for the summary; a deleted deck is not "archived". */
    @Select("SELECT COUNT(*) FROM deck WHERE user_id = #{userId} AND deleted_at IS NULL AND archived_at IS NOT NULL")
    long countArchived(@Param("userId") long userId);

    /**
     * The user's deleted decks, newest delete first - the recycle bin behind
     * {@code GET /api/decks/deleted}.
     *
     * <p>{@code cardCount} is the number of cards a restore would actually bring back, which is
     * the cards this delete took down: those whose {@code deleted_at} equals the deck's own
     * {@code deleted_at}. The obvious {@code c.deleted_at IS NULL} is <b>wrong</b> here - a deck
     * delete marks its cards as deleted in the same transaction, so that count is always zero for
     * exactly the rows this list shows. Matching the timestamps is the same rule
     * {@code CardMapper.restoreByDeck} uses, so the number in the list is the number that comes
     * back: a card the user had deleted individually beforehand is excluded from both.
     *
     * <p>{@code reviewsPreserved} counts the review rows of the deck's cards. It is deliberately
     * not conditioned on any delete timestamp: the history of a card that was already deleted
     * still exists and is still preserved by the deck delete, so excluding it would understate
     * what the delete kept.
     */
    @Select("""
            SELECT d.id        AS id,
                   d.name      AS name,
                   (SELECT COUNT(*) FROM card c
                     WHERE c.deck_id = d.id AND c.user_id = d.user_id
                       AND c.deleted_at = d.deleted_at)
                               AS cardCount,
                   d.deleted_at AS deletedAt,
                   (SELECT COUNT(*) FROM review_log r
                     WHERE r.user_id = d.user_id
                       AND r.card_id IN (SELECT c2.id FROM card c2 WHERE c2.deck_id = d.id))
                               AS reviewsPreserved
            FROM deck d
            WHERE d.user_id = #{userId} AND d.deleted_at IS NOT NULL
            ORDER BY d.deleted_at DESC, d.id DESC
            """)
    List<DeletedDeckResponse> findDeletedDecks(@Param("userId") long userId);
}
