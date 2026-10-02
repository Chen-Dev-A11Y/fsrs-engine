-- =============================================================================
-- FSRS scheduling service schema.
--
-- Conventions that matter for the whole system:
--   * every timestamp is epoch milliseconds in UTC (`*_ts`), never a local DATETIME,
--     because "which day is this?" depends on the user's timezone and must be
--     decided at read time, not at write time;
--   * `card.state` and `review_log.state_before` use the numeric codes of
--     com.fsrs.core.CardState (0 NEW, 1 LEARNING, 2 REVIEW, 3 RELEARNING) and must
--     never be reordered;
--   * `card.deleted_at` / `deck.deleted_at` are soft-delete markers (NULL = live), and
--     `card.archived_at` / `deck.archived_at` are archive markers (NULL = active). They are
--     orthogonal: a deck may be archived, deleted, or both. A deleted card keeps its
--     `review_log` rows, and so does an archived one - that history is the optimizer's
--     training data, so neither operation may destroy samples;
--   * `deck.archived_at` is the source of truth for "is this deck archived"; the redundant
--     `card.archived_at` is maintained in the same transaction so the due queue, the counters
--     and the forecast can filter cards with one indexed predicate instead of a join;
--   * MySQL is the single source of truth. Redis only holds a rebuildable index.
--
-- Written with IF NOT EXISTS so `spring.sql.init.mode=always` can re-run on boot.
-- Columns added after the first release live in `migration.sql`, which runs after this
-- file: CREATE TABLE IF NOT EXISTS silently does nothing to an already-existing table.
-- =============================================================================

CREATE TABLE IF NOT EXISTS `user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `username`      VARCHAR(64)  NOT NULL,
    `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt',
    `timezone`      VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai'
        COMMENT 'IANA zone; defines the user''s natural day for "due today" and the daily new-card quota',
    `created_at`    BIGINT       NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `deck` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT      NOT NULL,
    `name`       VARCHAR(128) NOT NULL,
    `created_at` BIGINT      NOT NULL,
    `deleted_at` BIGINT      NULL COMMENT 'soft delete: NULL = live, otherwise epoch millis of the delete',
    `archived_at` BIGINT     NULL COMMENT 'archive: NULL = active; source of truth for the deck''s archived state',
    PRIMARY KEY (`id`),
    KEY `idx_deck_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `card` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`        BIGINT       NOT NULL,
    `deck_id`        BIGINT       NOT NULL,
    `front`          VARCHAR(512) NOT NULL,
    `back`           VARCHAR(1024) NOT NULL,
    `state`          TINYINT      NOT NULL DEFAULT 0 COMMENT '0 NEW, 1 LEARNING, 2 REVIEW, 3 RELEARNING',
    `due_ts`         BIGINT       NOT NULL COMMENT 'epoch millis UTC; for NEW cards this is "introduce no earlier than"',
    `stability`      DOUBLE       NULL COMMENT 'NULL until the first review: a NEW card has no memory state',
    `difficulty`     DOUBLE       NULL COMMENT 'NULL until the first review, then in [1, 10]',
    `reps`           INT          NOT NULL DEFAULT 0,
    `lapses`         INT          NOT NULL DEFAULT 0,
    `last_review_ts` BIGINT       NULL,
    `suspended`      TINYINT      NOT NULL DEFAULT 0,
    `created_at`     BIGINT       NOT NULL,
    `deleted_at`     BIGINT       NULL COMMENT 'soft delete: NULL = live, otherwise epoch millis of the delete',
    `archived_at`    BIGINT       NULL COMMENT 'archive: redundant copy of deck.archived_at, kept in the same transaction',
    PRIMARY KEY (`id`),
    -- Serves the hottest query in the service: "my unsuspended cards due before now".
    KEY `idx_card_user_due` (`user_id`, `due_ts`, `suspended`),
    KEY `idx_card_deck` (`deck_id`),
    KEY `idx_card_user_state` (`user_id`, `state`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `review_log` (
    `id`               BIGINT   NOT NULL AUTO_INCREMENT,
    `card_id`          BIGINT   NOT NULL,
    `user_id`          BIGINT   NOT NULL,
    `rating`           TINYINT  NOT NULL COMMENT '1 Again, 2 Hard, 3 Good, 4 Easy',
    `reviewed_at`      BIGINT   NOT NULL COMMENT 'epoch millis UTC',
    `elapsed_days`     DOUBLE   NOT NULL DEFAULT 0 COMMENT 'whole days used by the model',
    `scheduled_days`   DOUBLE   NOT NULL DEFAULT 0 COMMENT 'interval the card had been scheduled for',
    `state_before`     TINYINT  NOT NULL,
    `s_before`         DOUBLE   NULL,
    `d_before`         DOUBLE   NULL,
    `r_before`         DOUBLE   NULL COMMENT 'retrievability predicted at review time',
    `duration_ms`      INT      NULL,
    `client_review_id` VARCHAR(64) NOT NULL COMMENT 'idempotency key supplied by the client',
    `created_at`       BIGINT   NOT NULL,
    PRIMARY KEY (`id`),
    -- The idempotency guarantee is enforced by the database, not only by Redis.
    UNIQUE KEY `uk_client_review` (`client_review_id`),
    KEY `idx_card_time` (`card_id`, `reviewed_at`),
    KEY `idx_review_user_time` (`user_id`, `reviewed_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `scheduler_profile` (
    `id`                    BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`               BIGINT      NOT NULL,
    `weights`               VARCHAR(512) NOT NULL COMMENT '21 doubles, comma separated',
    `desired_retention`     DOUBLE      NOT NULL DEFAULT 0.9,
    `maximum_interval_days` DOUBLE      NOT NULL DEFAULT 36500,
    `source`                VARCHAR(32) NOT NULL DEFAULT 'default' COMMENT 'default | optimizer',
    `updated_at`            BIGINT      NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_profile_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `daily_stat` (
    `id`        BIGINT  NOT NULL AUTO_INCREMENT,
    `user_id`   BIGINT  NOT NULL,
    `stat_date` CHAR(10) NOT NULL COMMENT 'user-local calendar day, yyyy-MM-dd',
    `reviews`   INT     NOT NULL DEFAULT 0,
    `again`     INT     NOT NULL DEFAULT 0,
    `hard`      INT     NOT NULL DEFAULT 0,
    `good`      INT     NOT NULL DEFAULT 0,
    `easy`      INT     NOT NULL DEFAULT 0,
    `new_cards` INT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_daily_stat_user_date` (`user_id`, `stat_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
