package io.github.mmcgill.cobbleware.api;

import cobbleware.common.Uuid;
import cobbleware.internal.ExecutionLogEntry;
import cobbleware.internal.ExecutionPoolTransition;
import cobbleware.internal.ExecutionPoolTransitionLogEntry;
import cobbleware.internal.ExecutionTransition;
import cobbleware.internal.ExecutionTransitionLogEntry;
import cobbleware.internal.LimitExceededLogEntry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/** Publishes non-durable Mod execution log entries over the existing log transport. */
final class ExecutionTransitionPublisher implements WebSocket.Listener {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final long INITIAL_RECONNECT_MILLIS = 1_000;
    private static final long MAX_RECONNECT_MILLIS = 30_000;
    private static final int MAX_PENDING_LOG_ENTRIES = 10_000;

    private final MainWorkerConnection connection;
    private final HttpClient httpClient;
    private final Logger logger;
    private final Object lock = new Object();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cobbleware-execution-transitions");
        thread.setDaemon(true);
        return thread;
    });
    private final Deque<ExecutionLogEntry> pending = new ArrayDeque<>();

    private CompletableFuture<WebSocket> connecting;
    private ScheduledFuture<?> reconnect;
    private WebSocket socket;
    private boolean sendPending;
    private boolean closed;
    private long nextReconnectMillis = INITIAL_RECONNECT_MILLIS;

    ExecutionTransitionPublisher(MainWorkerConnection connection, HttpClient httpClient, Logger logger) {
        this.connection = connection;
        this.httpClient = httpClient;
        this.logger = logger;
    }

    void offer(
            UUID playerId,
            UUID executionId,
            ExecutionTransition transition,
            int executionCount,
            int executionLimit,
            long totalExecutionStateLimit) {
        if (playerId == null
                || executionId == null
                || transition == null
                || transition == ExecutionTransition.EXECUTION_TRANSITION_UNSPECIFIED
                || executionCount < 0
                || executionLimit <= 0
                || executionCount > executionLimit
                || totalExecutionStateLimit <= 0) {
            throw new IllegalArgumentException("execution transition is invalid");
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            offerLocked(transitionEntry(
                    playerId, executionId, transition, executionCount, executionLimit, totalExecutionStateLimit));
        }
    }

    void offerPoolTransition(UUID playerId, ExecutionPoolTransition transition) {
        if (playerId == null
                || transition == null
                || transition == ExecutionPoolTransition.EXECUTION_POOL_TRANSITION_UNSPECIFIED
                || transition == ExecutionPoolTransition.UNRECOGNIZED) {
            throw new IllegalArgumentException("execution-pool transition is invalid");
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            offerLocked(ExecutionLogEntry.newBuilder()
                    .setPlayerId(protoUuid(playerId))
                    .setPoolTransition(
                            ExecutionPoolTransitionLogEntry.newBuilder().setTransition(transition))
                    .build());
        }
    }

    void offerLimitExceeded(UUID playerId, UUID executionId, String reason) {
        if (playerId == null || executionId == null || reason == null || reason.isEmpty()) {
            throw new IllegalArgumentException("limit-exceeded log entry is invalid");
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            offerLocked(ExecutionLogEntry.newBuilder()
                    .setPlayerId(protoUuid(playerId))
                    .setLimited(LimitExceededLogEntry.newBuilder().setReason(reason))
                    .build());
        }
    }

    private void offerLocked(ExecutionLogEntry entry) {
        if (pending.size() == MAX_PENDING_LOG_ENTRIES) {
            pending.removeFirst();
            logger.warn("Dropping the oldest pending execution log because the publication queue is full");
        }
        pending.addLast(entry);
        ensureConnectionLocked();
        sendNextLocked();
    }

    private void sendNextLocked() {
        if (socket == null || sendPending || pending.isEmpty()) {
            return;
        }
        WebSocket target = socket;
        ExecutionLogEntry next = pending.getFirst();
        sendPending = true;
        target.sendBinary(ByteBuffer.wrap(next.toByteArray()), true).whenComplete((ignored, failure) -> {
            synchronized (lock) {
                if (socket != target) {
                    return;
                }
                sendPending = false;
                if (failure != null) {
                    logger.debug("Main Worker execution-log send failed: {}", unwrap(failure));
                    socket = null;
                    target.abort();
                    scheduleReconnectLocked();
                    return;
                }
                pending.removeFirst();
                sendNextLocked();
            }
        });
    }

    private void ensureConnectionLocked() {
        if (closed || socket != null || connecting != null || (reconnect != null && !reconnect.isDone())) {
            return;
        }
        URI endpoint = websocketUri(connection.baseUri().resolve("modapi/v1/logs"));
        connecting = httpClient
                .newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .header("Authorization", "Bearer " + connection.apiKey())
                .buildAsync(endpoint, this);
        connecting.whenComplete((opened, failure) -> {
            synchronized (lock) {
                connecting = null;
                if (closed) {
                    if (opened != null) {
                        opened.abort();
                    }
                    return;
                }
                if (failure != null) {
                    logger.debug("Main Worker execution-log connection failed: {}", unwrap(failure));
                    scheduleReconnectLocked();
                    return;
                }
                socket = opened;
                sendPending = false;
                nextReconnectMillis = INITIAL_RECONNECT_MILLIS;
                sendNextLocked();
            }
        });
    }

    private void scheduleReconnectLocked() {
        if (closed || reconnect != null && !reconnect.isDone()) {
            return;
        }
        long delay = nextReconnectMillis;
        nextReconnectMillis = Math.min(MAX_RECONNECT_MILLIS, nextReconnectMillis * 2);
        reconnect = reconnectExecutor.schedule(
                () -> {
                    synchronized (lock) {
                        reconnect = null;
                        ensureConnectionLocked();
                    }
                },
                delay,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        logger.debug("Main Worker execution-log stream rejected a connection: {}", data);
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        disconnected(webSocket, "closed with " + statusCode + " " + reason);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        disconnected(webSocket, unwrap(error).toString());
    }

    private void disconnected(WebSocket webSocket, String detail) {
        synchronized (lock) {
            if (socket != webSocket) {
                return;
            }
            logger.debug("Main Worker execution-log stream {}", detail);
            socket = null;
            sendPending = false;
            scheduleReconnectLocked();
        }
    }

    void close() {
        WebSocket target;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            target = socket;
            socket = null;
            if (reconnect != null) {
                reconnect.cancel(false);
            }
        }
        if (target != null) {
            target.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping").exceptionally(ignored -> {
                target.abort();
                return null;
            });
        }
        reconnectExecutor.shutdownNow();
    }

    private static ExecutionLogEntry transitionEntry(
            UUID playerId,
            UUID executionId,
            ExecutionTransition transition,
            int executionCount,
            int executionLimit,
            long totalExecutionStateLimit) {
        return ExecutionLogEntry.newBuilder()
                .setPlayerId(protoUuid(playerId))
                .setTransition(ExecutionTransitionLogEntry.newBuilder()
                        .setExecutionId(protoUuid(executionId))
                        .setTransition(transition)
                        .setExecutionCount(executionCount)
                        .setExecutionLimit(executionLimit)
                        .setTotalExecutionStateLimit(totalExecutionStateLimit))
                .build();
    }

    private static Uuid protoUuid(UUID uuid) {
        return Uuid.newBuilder()
                .setHigh(uuid.getMostSignificantBits())
                .setLow(uuid.getLeastSignificantBits())
                .build();
    }

    private static URI websocketUri(URI httpUri) {
        String value = httpUri.toString();
        if (value.startsWith("https:")) {
            return URI.create("wss:" + value.substring("https:".length()));
        }
        if (value.startsWith("http:")) {
            return URI.create("ws:" + value.substring("http:".length()));
        }
        throw new IllegalArgumentException("Main Worker URL must use http or https");
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}
