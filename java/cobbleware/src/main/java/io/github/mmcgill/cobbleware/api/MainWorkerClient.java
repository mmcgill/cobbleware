package io.github.mmcgill.cobbleware.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mmcgill.cobbleware.config.CobblewareConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
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

    private static ProgramInfo parseProgram(JsonObject body) {
        JsonElement startStates = body.get("startStates");
        if (startStates == null || !startStates.isJsonObject()) {
            throw new IllegalArgumentException("program response has no startStates object");
        }
        return new ProgramInfo(
                requiredString(body, "id"),
                requiredString(body, "name"),
                requiredString(body, "uploadedAt"),
                requiredLong(body, "sizeBytes"),
                List.copyOf(startStates.getAsJsonObject().keySet()));
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

    public record ProgramInfo(String id, String name, String uploadedAt, long sizeBytes, List<String> startStateNames) {
        public ProgramInfo {
            if (id == null || name == null || uploadedAt == null || sizeBytes < 0 || startStateNames == null) {
                throw new IllegalArgumentException("program information is invalid");
            }
            startStateNames = List.copyOf(startStateNames);
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
}
