package com.fsrs.server.service;

import com.fsrs.server.config.FsrsProperties;
import com.fsrs.server.redis.SrsRedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keeps the Redis due index converging on MySQL.
 *
 * <p>This is the other half of the consistency strategy. Reviews update MySQL inside a
 * transaction and only then touch Redis; if that second step fails (Redis restarted, network
 * blip, process killed mid-flight) the review is still durable and the index is merely behind.
 * Rebuilding is therefore a normal, expected operation rather than an error path, and it runs
 * on a timer so the system heals without an operator.
 *
 * <p>Rebuilding on startup matters for a different reason: a restarted service must not
 * depend on a Redis instance that still holds yesterday's index, and a fresh Redis with no
 * index at all would make every user's queue empty until their first review.
 */
@Component
@Order(100)
public class DueQueueConvergenceJob implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DueQueueConvergenceJob.class);

    private final SrsRedisService redisService;
    private final FsrsProperties properties;

    public DueQueueConvergenceJob(SrsRedisService redisService, FsrsProperties properties) {
        this.redisService = redisService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.scheduler().rebuildOnStartup()) {
            return;
        }
        rebuildAll("startup");
    }

    @Scheduled(fixedDelayString = "${fsrs.scheduler.rebuild-interval-ms:600000}",
            initialDelayString = "${fsrs.scheduler.rebuild-interval-ms:600000}")
    public void scheduledRebuild() {
        if (properties.scheduler().rebuildIntervalMs() <= 0) {
            return;
        }
        rebuildAll("scheduled");
    }

    /**
     * Rebuilds one user at a time.
     *
     * <p>A failure for one user is logged and skipped so that a single bad row cannot stop the
     * convergence of everybody else - the job's only real requirement is that it eventually
     * runs again.
     */
    void rebuildAll(String trigger) {
        List<Long> userIds;
        try {
            userIds = redisService.usersWithCards();
        } catch (RuntimeException ex) {
            log.warn("due-queue rebuild ({}) could not list users: {}", trigger, ex.getMessage());
            return;
        }
        int indexed = 0;
        int failures = 0;
        for (Long userId : userIds) {
            try {
                indexed += redisService.rebuildDueQueue(userId);
            } catch (RuntimeException ex) {
                failures++;
                log.warn("due-queue rebuild ({}) failed for user {}: {}", trigger, userId, ex.getMessage());
            }
        }
        log.info("due-queue rebuild ({}): {} cards indexed for {} users, {} failures",
                trigger, indexed, userIds.size(), failures);
    }
}
