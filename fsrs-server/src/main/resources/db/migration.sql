-- =============================================================================
-- Idempotent forward migration, run after `schema.sql` on every boot.
--
-- Why this file exists at all: `schema.sql` is written with CREATE TABLE IF NOT
-- EXISTS, which is a no-op for a table that already exists. So a column added to
-- `schema.sql` reaches a fresh database but never an existing one - and the service
-- would then fail with "Unknown column 'deleted_at'" on the user's real database.
-- Anything added after the first release belongs here as well as in `schema.sql`.
--
-- MySQL 8 has no `ADD COLUMN IF NOT EXISTS` (that is MariaDB syntax), so each column
-- is added only after asking information_schema whether it is already there. The
-- pattern is idempotent: on a second boot the SELECT finds the column and the prepared
-- statement degrades to `DO 0`.
--
-- The statements are deliberately unguarded against errors: `spring.sql.init`
-- keeps `continue-on-error: false`, so a genuine migration failure must abort the
-- boot rather than start a service that cannot read its own tables.
-- =============================================================================

-- card.deleted_at -------------------------------------------------------------
SET @column_exists := (SELECT COUNT(*)
                       FROM information_schema.columns
                       WHERE table_schema = DATABASE()
                         AND table_name = 'card'
                         AND column_name = 'deleted_at');
SET @ddl := IF(@column_exists = 0,
               'ALTER TABLE card ADD COLUMN deleted_at BIGINT NULL COMMENT ''soft delete: NULL = live, otherwise epoch millis of the delete''',
               'DO 0');
PREPARE migrate_card FROM @ddl;
EXECUTE migrate_card;
DEALLOCATE PREPARE migrate_card;

-- deck.deleted_at -------------------------------------------------------------
SET @column_exists := (SELECT COUNT(*)
                       FROM information_schema.columns
                       WHERE table_schema = DATABASE()
                         AND table_name = 'deck'
                         AND column_name = 'deleted_at');
SET @ddl := IF(@column_exists = 0,
               'ALTER TABLE deck ADD COLUMN deleted_at BIGINT NULL COMMENT ''soft delete: NULL = live, otherwise epoch millis of the delete''',
               'DO 0');
PREPARE migrate_deck FROM @ddl;
EXECUTE migrate_deck;
DEALLOCATE PREPARE migrate_deck;

-- card.archived_at ------------------------------------------------------------
-- Orthogonal to deleted_at: a card can be archived without being deleted, and deleting an
-- archived card must not clear its archived marker.
SET @column_exists := (SELECT COUNT(*)
                       FROM information_schema.columns
                       WHERE table_schema = DATABASE()
                         AND table_name = 'card'
                         AND column_name = 'archived_at');
SET @ddl := IF(@column_exists = 0,
               'ALTER TABLE card ADD COLUMN archived_at BIGINT NULL COMMENT ''archive: redundant copy of deck.archived_at, kept in the same transaction''',
               'DO 0');
PREPARE migrate_card_archived FROM @ddl;
EXECUTE migrate_card_archived;
DEALLOCATE PREPARE migrate_card_archived;

-- deck.archived_at ------------------------------------------------------------
-- The semantic source of truth for a deck's archived state; card.archived_at is kept equal to
-- it for every card of the deck, in the same transaction.
SET @column_exists := (SELECT COUNT(*)
                       FROM information_schema.columns
                       WHERE table_schema = DATABASE()
                         AND table_name = 'deck'
                         AND column_name = 'archived_at');
SET @ddl := IF(@column_exists = 0,
               'ALTER TABLE deck ADD COLUMN archived_at BIGINT NULL COMMENT ''archive: NULL = active; source of truth for the deck''''s archived state''',
               'DO 0');
PREPARE migrate_deck_archived FROM @ddl;
EXECUTE migrate_deck_archived;
DEALLOCATE PREPARE migrate_deck_archived;
