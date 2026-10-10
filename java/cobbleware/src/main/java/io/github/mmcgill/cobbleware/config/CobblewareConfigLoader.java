package io.github.mmcgill.cobbleware.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/** Loads Cobbleware's server configuration from Fabric's config directory. */
public final class CobblewareConfigLoader {
    public static final String FILE_NAME = "cobbleware.json";
    public static final int DEFAULT_INSTRUMENTATION_WINDOW_TICKS = 600;
    public static final int DEFAULT_MAX_RUNNING_EXECUTIONS_PER_PLAYER = 10;
    public static final int DEFAULT_MAX_QUEUED_EVENTS_PER_PLAYER = 200;
    public static final int DEFAULT_MAX_PENDING_EFFECTS_PER_PLAYER = 200;
    public static final long DEFAULT_MAX_TOTAL_EXECUTION_STATE_BYTES_PER_PLAYER = 1024L * 1024L;
    /** A zero value defers the documented default to the server player limit. */
    public static final int DEFAULT_GUARANTEED_POOL_SIZE = 0;

    public static final int DEFAULT_BEST_EFFORT_POOL_SIZE = 0;
    public static final int DEFAULT_RUNTIME_PORT = 9000;

    private CobblewareConfigLoader() {}

    /** Loads the normal Fabric configuration file, creating it with defaults on first start. */
    public static CobblewareConfig load(Logger logger) {
        return load(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME), logger);
    }

    /**
     * Loads one configuration file. This overload permits configuration behavior to be tested without Fabric.
     */
    public static CobblewareConfig load(Path configPath, Logger logger) {
        try {
            if (Files.notExists(configPath)) {
                Path parent = configPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try {
                    Files.writeString(configPath, defaultJson(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                    logger.info("Created default Cobbleware configuration at {}", configPath);
                } catch (FileAlreadyExistsException ignored) {
                    // Another server-start task created the file after the existence check.
                }
            }

            String contents = Files.readString(configPath, StandardCharsets.UTF_8);
            CobblewareConfig config = parse(contents);
            if (config.configurationError() != null) {
                logger.error("Cobbleware configuration at {} is invalid: {}", configPath, config.configurationError());
            }
            return config;
        } catch (IOException | RuntimeException exception) {
            logger.error("Could not load Cobbleware configuration at {}: {}", configPath, exception.getMessage());
            return CobblewareConfig.disabled("Cobbleware configuration could not be loaded");
        }
    }

    private static CobblewareConfig parse(String contents) {
        JsonElement element = JsonParser.parseString(contents);
        if (!element.isJsonObject()) {
            return CobblewareConfig.disabled("Cobbleware configuration must be a JSON object");
        }

        JsonObject object = element.getAsJsonObject();
        int instrumentationWindowTicks =
                optionalPositiveInt(object, "instrumentationWindowTicks", DEFAULT_INSTRUMENTATION_WINDOW_TICKS);
        int maxRunningExecutionsPerPlayer =
                optionalPositiveInt(object, "maxRunningExecutionsPerPlayer", DEFAULT_MAX_RUNNING_EXECUTIONS_PER_PLAYER);
        int maxQueuedEventsPerPlayer =
                optionalPositiveInt(object, "maxQueuedEventsPerPlayer", DEFAULT_MAX_QUEUED_EVENTS_PER_PLAYER);
        int maxPendingEffectsPerPlayer =
                optionalPositiveInt(object, "maxPendingEffectsPerPlayer", DEFAULT_MAX_PENDING_EFFECTS_PER_PLAYER);
        long maxTotalExecutionStateBytesPerPlayer = optionalPositiveLong(
                object, "maxTotalExecutionStateBytesPerPlayer", DEFAULT_MAX_TOTAL_EXECUTION_STATE_BYTES_PER_PLAYER);
        int guaranteedPoolSize = optionalPositiveInt(object, "guaranteedPoolSize", DEFAULT_GUARANTEED_POOL_SIZE);
        int bestEffortPoolSize = optionalNonNegativeInt(object, "bestEffortPoolSize", DEFAULT_BEST_EFFORT_POOL_SIZE);
        int runtimePort = optionalPort(object, "runtimePort", DEFAULT_RUNTIME_PORT);
        return new CobblewareConfig(
                instrumentationWindowTicks,
                maxRunningExecutionsPerPlayer,
                maxQueuedEventsPerPlayer,
                maxPendingEffectsPerPlayer,
                maxTotalExecutionStateBytesPerPlayer,
                guaranteedPoolSize,
                bestEffortPoolSize,
                runtimePort,
                null);
    }

    private static int optionalPositiveInt(JsonObject object, String property, int defaultValue) {
        JsonElement value = object.get(property);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(property + " must be a positive integer");
        }
        try {
            BigDecimal number = value.getAsBigDecimal();
            int result = number.intValueExact();
            if (result <= 0) {
                throw new IllegalArgumentException(property + " must be a positive integer");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(property + " must be a positive integer", exception);
        }
    }

    private static long optionalPositiveLong(JsonObject object, String property, long defaultValue) {
        JsonElement value = object.get(property);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(property + " must be a positive integer");
        }
        try {
            long result = value.getAsBigDecimal().longValueExact();
            if (result <= 0) {
                throw new IllegalArgumentException(property + " must be a positive integer");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(property + " must be a positive integer", exception);
        }
    }

    private static int optionalNonNegativeInt(JsonObject object, String property, int defaultValue) {
        JsonElement value = object.get(property);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(property + " must be a non-negative integer");
        }
        try {
            int result = value.getAsBigDecimal().intValueExact();
            if (result < 0) {
                throw new IllegalArgumentException(property + " must be a non-negative integer");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(property + " must be a non-negative integer", exception);
        }
    }

    private static int optionalPort(JsonObject object, String property, int defaultValue) {
        JsonElement value = object.get(property);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(property + " must be an integer between 1 and 65535");
        }
        try {
            int result = value.getAsBigDecimal().intValueExact();
            if (result <= 0 || result > 65_535) {
                throw new IllegalArgumentException(property + " must be an integer between 1 and 65535");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(property + " must be an integer between 1 and 65535", exception);
        }
    }

    private static String defaultJson() {
        return "{\n"
                + "  \"runtimePort\": " + DEFAULT_RUNTIME_PORT + ",\n"
                + "  \"instrumentationWindowTicks\": " + DEFAULT_INSTRUMENTATION_WINDOW_TICKS + ",\n"
                + "  \"maxRunningExecutionsPerPlayer\": "
                + DEFAULT_MAX_RUNNING_EXECUTIONS_PER_PLAYER + ",\n"
                + "  \"maxQueuedEventsPerPlayer\": " + DEFAULT_MAX_QUEUED_EVENTS_PER_PLAYER + ",\n"
                + "  \"maxPendingEffectsPerPlayer\": " + DEFAULT_MAX_PENDING_EFFECTS_PER_PLAYER + ",\n"
                + "  \"maxTotalExecutionStateBytesPerPlayer\": "
                + DEFAULT_MAX_TOTAL_EXECUTION_STATE_BYTES_PER_PLAYER + ",\n"
                + "  \"bestEffortPoolSize\": " + DEFAULT_BEST_EFFORT_POOL_SIZE + "\n"
                + "}\n";
    }
}
