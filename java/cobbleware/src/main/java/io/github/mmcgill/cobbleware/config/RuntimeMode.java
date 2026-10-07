package io.github.mmcgill.cobbleware.config;

import java.util.Locale;

/** Controls whether the Mod stages and owns its bundled Edge Runtime. */
public enum RuntimeMode {
    AUTO,
    MANAGED,
    EXTERNAL,
    EXTRACT_ONLY;

    /** Parses the documented lowercase JSON spelling. */
    public static RuntimeMode parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("runtimeMode must be a JSON string");
        }
        try {
            return RuntimeMode.valueOf(value.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "runtimeMode must be one of auto, managed, external, or extract-only", exception);
        }
    }
}
