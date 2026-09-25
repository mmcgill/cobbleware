package io.github.mmcgill.cobbleware.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import cobbleware.common.Uuid;
import cobbleware.internal.ExecutionStatus;
import cobbleware.internal.ExecutionsRequest;
import cobbleware.internal.ExecutionsResponse;
import com.sun.net.httpserver.HttpServer;
import io.github.mmcgill.cobbleware.config.CobblewareConfig;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
                new CobblewareConfig(URI.create("http://127.0.0.1:1/"), "", 600, 10, null),
                HttpClient.newHttpClient(),
                LOGGER);

        MainWorkerClient.AuthenticationResult result = client.authenticate(request()).get();

        assertEquals(MainWorkerClient.Status.CONFIGURATION_ERROR, result.status());
        assertEquals("mainWorkerApiKey is empty", result.detail());
    }

    @Test
    void getsActiveProgramMetadataAndStartStateNames() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        startServer("/worker/modapi/v1/players/123e4567-e89b-12d3-a456-426614174000/program", 200, """
                {"id":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","name":"examples.eszip","uploadedAt":"2026-09-03T00:00:00.000Z","sizeBytes":42,"startStates":{"idle":[1],"running":[2]}}
                """, exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
        });

        MainWorkerClient.ProgramResult result = client("secret").getProgram(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000")).get();

        assertEquals(MainWorkerClient.ProgramStatus.SUCCESS, result.status());
        assertEquals("examples.eszip", result.program().name());
        assertEquals(42, result.program().sizeBytes());
        assertEquals(List.of("idle", "running"), result.program().startStateNames());
        assertArrayEquals(new byte[] {1}, result.program().startState("idle").orElseThrow());
        assertArrayEquals(new byte[] {2}, result.program().startState("running").orElseThrow());
        assertEquals("GET", method.get());
        assertEquals("Bearer secret", authorization.get());
    }

    @Test
    void rejectsMalformedStartStateBytes() throws Exception {
        startServer("/worker/modapi/v1/players/123e4567-e89b-12d3-a456-426614174000/program", 200, """
                {"id":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","name":"examples.eszip","uploadedAt":"2026-09-03T00:00:00.000Z","sizeBytes":42,"startStates":{"invalid":[256]}}
                """, exchange -> { });

        MainWorkerClient.ProgramResult result = client("secret").getProgram(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000")).get();

        assertEquals(MainWorkerClient.ProgramStatus.UNAVAILABLE, result.status());
    }

    @Test
    void programInfoDefensivelyCopiesStartStateBytes() {
        byte[] original = {1};
        MainWorkerClient.ProgramInfo program = new MainWorkerClient.ProgramInfo(
                "id", "name", "2026-09-03T00:00:00.000Z", 1, java.util.Map.of("initial", original));

        original[0] = 2;
        byte[] returned = program.startState("initial").orElseThrow();
        returned[0] = 3;

        assertArrayEquals(new byte[] {1}, program.startState("initial").orElseThrow());
    }

    @Test
    void classifiesMissingProgramAndProgramErrors() throws Exception {
        startServer("/worker/modapi/v1/players/123e4567-e89b-12d3-a456-426614174000/program", 404, "", exchange -> { });
        MainWorkerClient.ProgramResult noProgram = client("secret").getProgram(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000")).get();
        assertEquals(MainWorkerClient.ProgramStatus.NO_PROGRAM, noProgram.status());

        server.stop(0);
        server = null;
        startServer("/worker/modapi/v1/players/123e4567-e89b-12d3-a456-426614174000/program", 200,
                "{\"programError\":\"program exploded\"}", exchange -> { });
        MainWorkerClient.ProgramResult programError = client("secret").getProgram(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000")).get();
        assertEquals(MainWorkerClient.ProgramStatus.PROGRAM_ERROR, programError.status());
        assertEquals("program exploded", programError.detail());
    }

    @Test
    void postsBinaryExecutionSnapshotAndParsesProtobufResponse() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        ExecutionsResponse expected = ExecutionsResponse.newBuilder()
                .addResults(cobbleware.internal.ExecutionResult.newBuilder()
                        .setExecutionId(Uuid.newBuilder().setHigh(1).setLow(2))
                        .setFinalState(com.google.protobuf.ByteString.copyFrom(new byte[] {9}))
                        .setEventSeqNum(0)
                        .setEffectSeqNum(1)
                        .setState(ExecutionStatus.EXECUTION_STATUS_RUNNING))
                .build();
        startBinaryServer("/worker/modapi/v1/executions", 200, expected.toByteArray(), exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
        });

        ExecutionsRequest request = ExecutionsRequest.newBuilder().build();
        MainWorkerClient.ExecutionSyncResult result = client("secret").syncExecutions(request).get();

        assertEquals(MainWorkerClient.ExecutionSyncStatus.SUCCESS, result.status());
        assertEquals(expected, result.response());
        assertEquals("Bearer secret", authorization.get());
        assertEquals("application/octet-stream", contentType.get());
        assertArrayEquals(request.toByteArray(), requestBody.get());
    }

    private void startServer(int status, String responseBody, RequestInspector inspector) throws Exception {
        startServer("/worker/modapi/v1/authentication", status, responseBody, inspector);
    }

    private void startServer(String path, int status, String responseBody, RequestInspector inspector) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
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

    private void startBinaryServer(String path, int status, byte[] responseBody, RequestInspector inspector) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            inspector.inspect(exchange);
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(status, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();
    }

    private MainWorkerClient client(String apiKey) {
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/worker/");
        return new MainWorkerClient(
                new CobblewareConfig(baseUri, apiKey, 600, 10, null),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                LOGGER);
    }

    private MainWorkerClient productionClient(String apiKey) {
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/worker/");
        return new MainWorkerClient(new CobblewareConfig(baseUri, apiKey, 600, 10, null), LOGGER);
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
