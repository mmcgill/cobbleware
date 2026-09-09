package io.github.mmcgill.cobbleware.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import io.github.mmcgill.cobbleware.config.CobblewareConfig;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MainWorkerClientTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(MainWorkerClientTest.class);
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsExpectedAuthenticationRequestAndAcceptsCreated() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        startServer(201, "", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        });

        MainWorkerClient.AuthenticationResult result = productionClient("secret").authenticate(request()).get();

        assertEquals(MainWorkerClient.Status.SUCCESS, result.status());
        assertEquals("Bearer secret", authorization.get());
        assertEquals("application/json", contentType.get());
        assertEquals(
                "{\"code\":\"XAU1-98B1\",\"playerUUID\":\"123e4567-e89b-12d3-a456-426614174000\",\"playerProfileName\":\"Alice\"}",
                body.get());
    }

    @Test
    void classifiesWorkerRejectionAndBadApiKey() throws Exception {
        startServer(400, "{\"error\":\"invalid authentication request\"}", exchange -> { });
        MainWorkerClient.AuthenticationResult rejected = client("secret").authenticate(request()).get();
        assertEquals(MainWorkerClient.Status.REJECTED, rejected.status());

        server.stop(0);
        server = null;
        startServer(401, "{\"error\":\"unauthorized\"}", exchange -> { });
        MainWorkerClient.AuthenticationResult unauthorized = client("secret").authenticate(request()).get();
        assertEquals(MainWorkerClient.Status.UNAUTHORIZED, unauthorized.status());
    }

    @Test
    void doesNotMakeRequestsWhenApiKeyIsEmpty() throws Exception {
        MainWorkerClient client = new MainWorkerClient(
                new CobblewareConfig(URI.create("http://127.0.0.1:1/"), "", null),
                HttpClient.newHttpClient(),
                LOGGER);

        MainWorkerClient.AuthenticationResult result = client.authenticate(request()).get();

        assertEquals(MainWorkerClient.Status.CONFIGURATION_ERROR, result.status());
        assertEquals("mainWorkerApiKey is empty", result.detail());
    }

    private void startServer(int status, String responseBody, RequestInspector inspector) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/worker/modapi/v1/authentication", exchange -> {
            inspector.inspect(exchange);
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            if (response.length == 0) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
    }

    private MainWorkerClient client(String apiKey) {
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/worker/");
        return new MainWorkerClient(
                new CobblewareConfig(baseUri, apiKey, null),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                LOGGER);
    }

    private MainWorkerClient productionClient(String apiKey) {
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/worker/");
        return new MainWorkerClient(new CobblewareConfig(baseUri, apiKey, null), LOGGER);
    }

    private static MainWorkerClient.AuthenticationRequest request() {
        return new MainWorkerClient.AuthenticationRequest(
                "XAU1-98B1",
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                "Alice");
    }

    @FunctionalInterface
    private interface RequestInspector {
        void inspect(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException;
    }
}
