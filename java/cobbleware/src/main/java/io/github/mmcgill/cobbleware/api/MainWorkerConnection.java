package io.github.mmcgill.cobbleware.api;

import java.net.URI;

/** Immutable in-memory connection details for the Runtime-owned Main Worker. */
public record MainWorkerConnection(URI baseUri, String apiKey) {
    public MainWorkerConnection {
        if (baseUri == null || !baseUri.isAbsolute()) {
            throw new IllegalArgumentException("baseUri must be an absolute URI");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }
    }
}
