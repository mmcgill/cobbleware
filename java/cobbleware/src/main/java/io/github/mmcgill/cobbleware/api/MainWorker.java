package io.github.mmcgill.cobbleware.api;

import cobbleware.internal.ExecutionStatsTick;
import cobbleware.internal.ExecutionTransition;
import cobbleware.internal.ExecutionsRequest;
import cobbleware.internal.ExecutionsResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Main Worker operations used by execution lifecycle processing.
 *
 * <p>This intentionally excludes player authentication and program-management operations, which are not needed by
 * the execution driver.
 */
public interface MainWorker {
    /** Synchronizes the Mod's current execution snapshot without blocking the server thread. */
    CompletableFuture<ExecutionSyncResult> syncExecutions(ExecutionsRequest executions);

    /** Offers one raw instrumentation tick without blocking the server tick. */
    void reportExecutionStats(ExecutionStatsTick stats);

    /** Publishes a non-durable execution lifecycle transition without blocking the server tick. */
    void reportExecutionTransition(
            UUID playerId,
            UUID executionId,
            ExecutionTransition transition,
            int executionCount,
            int executionLimit,
            long totalExecutionStateLimit);

    /** Publishes a non-durable state-limit log entry without blocking the server tick. */
    void reportLimitExceeded(UUID playerId, UUID executionId, String reason);

    /** Result of an asynchronous execution-sync request. */
    record ExecutionSyncResult(ExecutionSyncStatus status, ExecutionsResponse response, String detail) {
        public static ExecutionSyncResult success(ExecutionsResponse response) {
            return new ExecutionSyncResult(ExecutionSyncStatus.SUCCESS, response, null);
        }

        public static ExecutionSyncResult configurationError(String detail) {
            return new ExecutionSyncResult(ExecutionSyncStatus.CONFIGURATION_ERROR, null, detail);
        }

        public static ExecutionSyncResult unauthorized() {
            return new ExecutionSyncResult(ExecutionSyncStatus.UNAUTHORIZED, null, null);
        }

        public static ExecutionSyncResult unavailable(String detail) {
            return new ExecutionSyncResult(ExecutionSyncStatus.UNAVAILABLE, null, detail);
        }
    }

    enum ExecutionSyncStatus {
        SUCCESS,
        CONFIGURATION_ERROR,
        UNAUTHORIZED,
        UNAVAILABLE
    }
}
