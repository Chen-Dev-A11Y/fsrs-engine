package com.fsrs.server.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs side effects only after the surrounding transaction has committed.
 *
 * <p>This exists because the service writes to two stores with different durability
 * guarantees: MySQL is the source of truth, Redis is a rebuildable index. Touching Redis
 * <em>inside</em> the transaction would publish state that a rollback then erases, producing a
 * cache that confidently disagrees with the database. Deferring to {@code afterCommit} makes
 * the ordering explicit and impossible to get wrong at a call site.
 *
 * <p>Outside a transaction the work runs immediately, so the helper is safe to call from
 * anywhere (a scheduled job, a test) without a branch at the caller.
 */
public final class Transactions {

    private Transactions() {
    }

    /**
     * @param work side effect to run after commit; exceptions are the caller's problem, which
     *             is why the Redis-degrading callers wrap their own bodies in try/catch
     */
    public static void afterCommit(Runnable work) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
        } else {
            work.run();
        }
    }
}
