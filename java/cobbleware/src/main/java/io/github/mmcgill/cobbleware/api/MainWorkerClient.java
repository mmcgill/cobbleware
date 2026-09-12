package io.github.mmcgill.cobbleware.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cobbleware.internal.ErrorResponse;
import cobbleware.internal.ExecutionStats;
import cobbleware.internal.ExecutionsRequest;
import cobbleware.internal.ExecutionsResponse;
import io.github.mmcgill.cobbleware.config.CobblewareConfig;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;

/** Asynchronous client for the Main Worker's Mod API. */
public final class MainWorkerClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final CobblewareConfig config;
    private final HttpClient httpClient;
    private final Logger logger;

    public MainWorkerClient(CobblewareConfig config, Logger logger) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build(), logger);
    }

    MainWorkerClient(CobblewareConfig config, HttpClient httpClient, Logger logger) {
        this.config = config;
        this.httpClient = httpClient;
        this.logger = logger;
    }

    /** Authenticates a player for a pending browser-generated code without blocking the server thread. */
    public CompletableFuture<AuthenticationResult> authenticate(AuthenticationRequest authentication) {
        if (!config.isEnabled()) {
            return CompletableFuture.completedFuture(AuthenticationResult.configurationError(config.disabledReason()));
        }

        URI endpoint = config.mainWorkerBaseUri().resolve("modapi/v1/authentication");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.mainWorkerApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson(authentication)))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrap(failure);
                        logger.warn("Main Worker authentication request failed: {}", cause.toString());
                        return AuthenticationResult.unavailable("Main Worker request failed");
                    }
                    return responseResult(response);
                });
    }

    /** Gets the authenticated player's active program information without blocking the server thread. */
    public CompletableFuture<ProgramResult> getProgram(UUID playerUuid) {
        if (!config.isEnabled()) {
            return CompletableFuture.completedFuture(ProgramResult.configurationError(config.disabledReason()));
        }

        URI endpoint = config.mainWorkerBaseUri().resolve("modapi/v1/players/" + playerUuid + "/program");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + config.mainWorkerApiKey())
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrap(failure);
                        logger.warn("Main Worker program request failed: {}", cause.toString());
                        return ProgramResult.unavailable("Main Worker request failed");
                    }
                    return programResponseResult(response);
                });
    }

    /** Synchronizes the Mod's current execution snapshot without blocking the server thread. */
    public CompletableFuture<ExecutionSyncResult> syncExecutions(ExecutionsRequest executions) {
        if (executions == null) {
            throw new IllegalArgumentException("executions must not be null");
        }
        if (!config.isEnabled()) {
            return CompletableFuture.completedFuture(ExecutionSyncResult.configurationError(config.disabledReason()));
        }

        URI endpoint = config.mainWorkerBaseUri().resolve("modapi/v1/executions");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .header("Authorization", "Bearer " + config.mainWorkerApiKey())
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(executions.toByteArray()))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .handle((response, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrap(failure);
                        logger.warn("Main Worker execution sync failed: {}", cause.toString());
                        return ExecutionSyncResult.unavailable("Main Worker request failed");
                    }
                    return executionSyncResponseResult(response);
                });
    }

    /** Sends a complete instrumentation report without blocking or retrying the server tick. */
    public CompletableFuture<Void> reportExecutionStats(ExecutionStats stats) {
        if (stats == null) {
            throw new IllegalArgumentException("execution stats must not be null");
        }
        if (!config.isEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        URI endpoint = config.mainWorkerBaseUri().resolve("modapi/v1/execution-stats");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + config.mainWorkerApiKey())
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(stats.toByteArray()))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .handle((response, failure) -> {
                    if (failure != null) {
                        logger.debug("Main Worker execution-stats report failed: {}", unwrap(failure).toString());
                    } else if (response.statusCode() != 204) {
                        logger.debug("Main Worker execution-stats report returned HTTP {}", response.statusCode());
                    }
                    return null;
                });
    }

    private AuthenticationResult responseResult(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 201) {
            return AuthenticationResult.success();
        }

        String workerError = errorMessage(response.body());
        if (status == 400) {
            logger.info("Main Worker rejected authentication code: {}", workerError);
            return AuthenticationResult.rejected(workerError);
        }
        if (status == 401) {
            logger.error("Main Worker rejected the configured API key");
            return AuthenticationResult.unauthorized();
        }

        logger.warn("Main Worker authentication request returned HTTP {}: {}", status, workerError);
        return AuthenticationResult.unavailable("Main Worker returned HTTP " + status);
    }

    private ProgramResult programResponseResult(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 200) {
            try {
                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonElement programError = body.get("programError");
                if (programError != null && programError.isJsonPrimitive()
                        && programError.getAsJsonPrimitive().isString()) {
                    return ProgramResult.programError(programError.getAsString());
                }
                return ProgramResult.success(parseProgram(body));
            } catch (RuntimeException exception) {
                logger.warn("Main Worker returned an invalid program response: {}", exception.toString());
                return ProgramResult.unavailable("Main Worker returned an invalid program response");
            }
        }
        if (status == 404) {
            return ProgramResult.noProgram();
        }
        if (status == 401) {
            logger.error("Main Worker rejected the configured API key");
            return ProgramResult.unauthorized();
        }

        logger.warn("Main Worker program request returned HTTP {}: {}", status, errorMessage(response.body()));
        return ProgramResult.unavailable("Main Worker returned HTTP " + status);
    }

    private ExecutionSyncResult executionSyncResponseResult(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 200) {
            try {
                return ExecutionSyncResult.success(ExecutionsResponse.parseFrom(response.body()));
            } catch (com.google.protobuf.InvalidProtocolBufferException exception) {
                logger.warn("Main Worker returned an invalid execution-sync response: {}", exception.toString());
                return ExecutionSyncResult.unavailable("Main Worker returned an invalid execution response");
            }
        }

        String detail = protobufErrorMessage(response.body()).orElse("no error message supplied");
        if (status == 401) {
            logger.error("Main Worker rejected the configured API key");
            return ExecutionSyncResult.unauthorized();
        }
        logger.warn("Main Worker execution sync returned HTTP {}: {}", status, detail);
        return ExecutionSyncResult.unavailable("Main Worker returned HTTP " + status);
    }

    private static Optional<String> protobufErrorMessage(byte[] responseBody) {
        try {
            String error = ErrorResponse.parseFrom(responseBody).getError();
            return error.isEmpty() ? Optional.empty() : Optional.of(error);
        } catch (com.google.protobuf.InvalidProtocolBufferException ignored) {
            return Optional.empty();
        }
    }

    private static ProgramInfo parseProgram(JsonObject body) {
        JsonElement startStates = body.get("startStates");
        if (startStates == null || !startStates.isJsonObject()) {
            throw new IllegalArgumentException("program response has no startStates object");
        }
        Map<String, byte[]> parsedStartStates = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : startStates.getAsJsonObject().entrySet()) {
            parsedStartStates.put(entry.getKey(), parseStateBytes(entry.getValue()));
        }
        return new ProgramInfo(
                requiredString(body, "id"),
                requiredString(body, "name"),
                requiredString(body, "uploadedAt"),
                requiredLong(body, "sizeBytes"),
                parsedStartStates);
    }

    private static byte[] parseStateBytes(JsonElement state) {
        if (!state.isJsonArray()) {
            throw new IllegalArgumentException("program start state is not a byte array");
        }
        byte[] bytes = new byte[state.getAsJsonArray().size()];
        for (int index = 0; index < bytes.length; index++) {
            JsonElement element = state.getAsJsonArray().get(index);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("program start state contains a non-numeric byte");
            }
            try {
                int value = new BigDecimal(element.getAsString()).intValueExact();
                if (value < 0 || value > 255) {
                    throw new IllegalArgumentException("program start state byte is outside 0..255");
                }
                bytes[index] = (byte) value;
            } catch (NumberFormatException | ArithmeticException exception) {
                throw new IllegalArgumentException("program start state contains an invalid byte", exception);
            }
        }
        return bytes;
    }

    private static String requiredString(JsonObject body, String field) {
        JsonElement value = body.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("program response has no string " + field);
        }
        return value.getAsString();
    }

    private static long requiredLong(JsonObject body, String field) {
        JsonElement value = body.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("program response has no numeric " + field);
        }
        return value.getAsLong();
    }

    private static String requestJson(AuthenticationRequest authentication) {
        JsonObject object = new JsonObject();
        object.addProperty("code", authentication.code());
        object.addProperty("playerUUID", authentication.playerUuid().toString());
        object.addProperty("playerProfileName", authentication.playerProfileName());
        return object.toString();
    }

    private static String errorMessage(String responseBody) {
        try {
            JsonElement element = JsonParser.parseString(responseBody);
            if (element.isJsonObject()) {
                JsonElement error = element.getAsJsonObject().get("error");
                if (error != null && error.isJsonPrimitive() && error.getAsJsonPrimitive().isString()) {
                    return error.getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // An error body is diagnostic only; malformed bodies still have a meaningful HTTP status.
        }
        return "no error message supplied";
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    public record AuthenticationRequest(String code, UUID playerUuid, String playerProfileName) {
        public AuthenticationRequest {
            if (code == null || playerUuid == null || playerProfileName == null) {
                throw new IllegalArgumentException("authentication request values must not be null");
            }
        }
    }

    public record AuthenticationResult(Status status, String detail) {
        public static AuthenticationResult success() {
            return new AuthenticationResult(Status.SUCCESS, null);
        }

        public static AuthenticationResult configurationError(String detail) {
            return new AuthenticationResult(Status.CONFIGURATION_ERROR, detail);
        }

        public static AuthenticationResult rejected(String detail) {
            return new AuthenticationResult(Status.REJECTED, detail);
        }

        public static AuthenticationResult unauthorized() {
            return new AuthenticationResult(Status.UNAUTHORIZED, null);
        }

        public static AuthenticationResult unavailable(String detail) {
            return new AuthenticationResult(Status.UNAVAILABLE, detail);
        }
    }

    public enum Status {
        SUCCESS,
        CONFIGURATION_ERROR,
        REJECTED,
        UNAUTHORIZED,
        UNAVAILABLE
    }

    public record ProgramInfo(String id, String name, String uploadedAt, long sizeBytes, Map<String, byte[]> startStates) {
        public ProgramInfo {
            if (id == null || name == null || uploadedAt == null || sizeBytes < 0 || startStates == null) {
                throw new IllegalArgumentException("program information is invalid");
            }
            Map<String, byte[]> copiedStates = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : startStates.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IllegalArgumentException("program start states must not contain null values");
                }
                copiedStates.put(entry.getKey(), Arrays.copyOf(entry.getValue(), entry.getValue().length));
            }
            startStates = Collections.unmodifiableMap(copiedStates);
        }

        @Override
        public Map<String, byte[]> startStates() {
            Map<String, byte[]> copiedStates = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : startStates.entrySet()) {
                copiedStates.put(entry.getKey(), Arrays.copyOf(entry.getValue(), entry.getValue().length));
            }
            return Collections.unmodifiableMap(copiedStates);
        }

        public List<String> startStateNames() {
            return List.copyOf(startStates.keySet());
        }

        public Optional<byte[]> startState(String stateName) {
            byte[] state = startStates.get(stateName);
            return state == null ? Optional.empty() : Optional.of(Arrays.copyOf(state, state.length));
        }
    }

    public record ProgramResult(ProgramStatus status, ProgramInfo program, String detail) {
        public static ProgramResult success(ProgramInfo program) {
            return new ProgramResult(ProgramStatus.SUCCESS, program, null);
        }

        public static ProgramResult noProgram() {
            return new ProgramResult(ProgramStatus.NO_PROGRAM, null, null);
        }

        public static ProgramResult configurationError(String detail) {
            return new ProgramResult(ProgramStatus.CONFIGURATION_ERROR, null, detail);
        }

        public static ProgramResult unauthorized() {
            return new ProgramResult(ProgramStatus.UNAUTHORIZED, null, null);
        }

        public static ProgramResult programError(String detail) {
            return new ProgramResult(ProgramStatus.PROGRAM_ERROR, null, detail);
        }

        public static ProgramResult unavailable(String detail) {
            return new ProgramResult(ProgramStatus.UNAVAILABLE, null, detail);
        }
    }

    public enum ProgramStatus {
        SUCCESS,
        NO_PROGRAM,
        CONFIGURATION_ERROR,
        UNAUTHORIZED,
        PROGRAM_ERROR,
        UNAVAILABLE
    }

    /** Result of an asynchronous execution-sync request. */
    public record ExecutionSyncResult(ExecutionSyncStatus status, ExecutionsResponse response, String detail) {
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

    public enum ExecutionSyncStatus {
        SUCCESS,
        CONFIGURATION_ERROR,
        UNAUTHORIZED,
        UNAVAILABLE
    }
}
