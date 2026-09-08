package io.github.mmcgill.cobbleware.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/** Loads Cobbleware's server configuration from Fabric's config directory. */
public final class CobblewareConfigLoader {
    public static final String FILE_NAME = "cobbleware.json";
    public static final String DEFAULT_MAIN_WORKER_URL = "http://localhost:9000";

    private CobblewareConfigLoader() {
    }

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
        String mainWorkerUrl = requiredString(object, "mainWorkerUrl");
        String mainWorkerApiKey = requiredString(object, "mainWorkerApiKey");
        URI baseUri = normalizeBaseUri(mainWorkerUrl);
        return new CobblewareConfig(baseUri, mainWorkerApiKey, null);
    }

    private static String requiredString(JsonObject object, String property) {
        JsonElement value = object.get(property);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(property + " must be a JSON string");
        }
        return value.getAsString();
    }

    private static URI normalizeBaseUri(String value) {
        try {
            URI parsed = new URI(value);
            String scheme = parsed.getScheme();
            if (!parsed.isAbsolute() || parsed.getHost() == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || parsed.getRawUserInfo() != null || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
                throw new IllegalArgumentException("mainWorkerUrl must be an absolute HTTP or HTTPS URL without credentials, query, or fragment");
            }
            return new URI(parsed.toString().endsWith("/") ? parsed.toString() : parsed + "/");
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("mainWorkerUrl is not a valid URI", exception);
        }
    }

    private static String defaultJson() {
        return "{\n"
                + "  \"mainWorkerUrl\": \"" + DEFAULT_MAIN_WORKER_URL + "\",\n"
                + "  \"mainWorkerApiKey\": \"\"\n"
                + "}\n";
    }
}
