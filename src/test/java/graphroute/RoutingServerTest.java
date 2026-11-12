package graphroute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphroute.api.RoutingServer;
import graphroute.io.GraphFileManager;
import graphroute.routing.RoutingService;
import graphroute.routing.TrafficSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class RoutingServerTest {
    private RoutingServer server;
    private HttpClient client;
    private String base;
    private final ObjectMapper json = new ObjectMapper();
    private static final String REQUEST = """
            {"origin":"tx-khuat-duy-tien-nguyen-trai", "destination":"tx-le-van-luong-hoang-dao-thuy", "alternatives":3}
            """;

    @BeforeEach void start() throws Exception {
        server = new RoutingServer(new RoutingService(new GraphFileManager().loadThanhXuanGraph()), 0);
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach void stop() { if (client != null) client.close(); if (server != null) server.close(); }

    @Test void servesDiscoveryHealthAndMap() throws Exception {
        assertEquals(200, get("/").statusCode());
        assertEquals(200, get("/api").statusCode());
        var health = get("/api/health");
        assertEquals(200, health.statusCode());
        assertEquals(8, body(health).path("places").asInt());
        assertEquals(20, body(health).path("directedRoadSegments").asInt());
        assertEquals(8, body(get("/api/map")).path("places").size());
        assertTrue(health.headers().firstValue("Content-Type").orElseThrow().contains("utf-8"));
    }

    @Test void viewportMapValidatesBoundsAndHeadMatchesGetWithoutBody() throws Exception {
        var full = get("/api/map");
        var empty = get("/api/map?bbox=0,0,1,1");
        assertEquals(200, empty.statusCode());
        assertTrue(body(empty).path("roads").isEmpty());
        assertTrue(body(empty).path("places").isEmpty());
        assertEquals(400, get("/api/map?bbox=1,0,-1,1").statusCode());
        assertEquals(400, get("/api/map?bbox=0,0,NaN,1").statusCode());
        assertEquals(400, get("/api/map?bbox=0,0,1").statusCode());
        var head = client.send(HttpRequest.newBuilder(URI.create(base + "/api/map"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
        assertEquals(full.headers().firstValue("content-length"), head.headers().firstValue("content-length"));
        var postOnly = client.send(HttpRequest.newBuilder(URI.create(base + "/api/routes"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, postOnly.statusCode());
        assertEquals("", postOnly.body());
    }

    static Stream<Arguments> mapEncodings() {
        return Stream.of(
                Arguments.of(List.of(";"), false),
                Arguments.of(List.of(";;, gzip"), true),
                Arguments.of(List.of("br", "gzip"), true),
                Arguments.of(List.of("*;q=1", "gzip;q=0"), false),
                Arguments.of(List.of("gzip;q=0", "*;q=1"), false),
                Arguments.of(List.of("gzip;q=oops", "*;q=1"), false),
                Arguments.of(List.of("gzip;q", "*;q=1"), false),
                Arguments.of(List.of("gzip;q=1e0"), false),
                Arguments.of(List.of("gzip;q=0.1234"), false),
                Arguments.of(List.of("gzip;q=0;q=1"), false),
                Arguments.of(List.of("GZIP; Q=0.001"), true),
                Arguments.of(List.of("gzip;q=1.000"), true));
    }

    @ParameterizedTest
    @MethodSource("mapEncodings")
    void mapNegotiatesEncodingAcrossHeaderFields(List<String> encodings, boolean gzip) throws Exception {
        var uri = URI.create(base + "/api/map");
        var plain = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
        encodings.forEach(value -> request.header("Accept-Encoding", value));

        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        assertEquals(gzip ? "gzip" : "", response.headers().firstValue("Content-Encoding").orElse(""));
        if (gzip) {
            try (var decoded = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
                assertArrayEquals(plain.body(), decoded.readAllBytes());
            }
        } else {
            assertArrayEquals(plain.body(), response.body());
        }
    }

    @Test void searchesAndSnapsCoordinates() throws Exception {
        assertFalse(body(get("/api/places?q=nguyen%20trai&limit=2")).path("places").isEmpty());
        assertEquals(2, body(get("/api/places?q=nguyen%20trai&limit=2")).path("places").size());
        assertEquals(0, body(get("/api/nearest?lat=20.99351&lng=105.80436")).path("distanceMeters").asDouble());
        assertError(get("/api/nearest?lat=0&lng=0"), 422, "OUTSIDE_COVERAGE");
    }

    @Test void returnsStructuredRoutesWithGeoJsonAndDirections() throws Exception {
        var response = post(REQUEST);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode route = body(response).path("routes").get(0);
        assertEquals(1935, route.path("distanceMeters").asDouble(), 1e-8);
        assertEquals("LineString", route.path("geometry").path("type").asText());
        assertFalse(route.path("directions").isEmpty());
    }

    @Test void validatesMethodsPathsAndQueryParameters() throws Exception {
        assertError(get("/api/routes"), 405, "METHOD_NOT_ALLOWED");
        assertError(get("/api/health/extra"), 404, "NOT_FOUND");
        assertError(get("/api/nearest?lat=0"), 400, "INVALID_REQUEST");
        assertError(get("/api/nearest?lat=NaN&lng=0"), 400, "INVALID_REQUEST");
        assertError(get("/api/places?limit=1.2"), 400, "INVALID_REQUEST");
        assertError(get("/api/places?q=a&q=b"), 400, "INVALID_REQUEST");
        assertError(get("/api/places?typo=a"), 400, "INVALID_REQUEST");
        assertEquals("POST", get("/api/routes").headers().firstValue("Allow").orElseThrow());
    }

    @Test void rejectsMalformedUnknownAndWronglyTypedRequestBodies() throws Exception {
        for (String request : new String[]{"{", "[]", "", REQUEST + " {}", REQUEST.replace("3", "\"3\""),
                REQUEST.replace("3", "1.5"), REQUEST.replace("\"alternatives\"", "\"unknownField\""),
                "{\"origin\":123,\"destination\":true}", "{\"origin\":\"a\",\"waypoints\":[456]}",
                "{\"origin\":\"a\",\"origin\":\"b\"}", "{\"mode\":true}"}) {
            assertError(post(request), 400, "INVALID_JSON");
        }
        assertError(post("null"), 400, "INVALID_REQUEST");
        assertError(post("{}"), 400, "INVALID_REQUEST");
        assertError(post(REQUEST.replace("tx-khuat-duy-tien-nguyen-trai", "missing")), 404, "PLACE_NOT_FOUND");
    }

    @Test void rejectsWrongContentTypeAndOversizedBody() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/routes"))
                .POST(HttpRequest.BodyPublishers.ofString(REQUEST)).build(), HttpResponse.BodyHandlers.ofString());
        assertError(response, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertError(post(" ".repeat(16_385)), 413, "REQUEST_TOO_LARGE");
    }

    @Test void servesConcurrentIndependentRoutingRequests() throws Exception {
        var responses = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < 12; i++) responses.add(client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/api/routes"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(REQUEST)).build(), HttpResponse.BodyHandlers.ofString()));
        for (var pending : responses) {
            var response = pending.get();
            assertEquals(200, response.statusCode());
            assertEquals(1935, body(response).path("routes").get(0).path("distanceMeters").asDouble(), 1e-8);
        }
    }

    @Test void discoversAndExecutesAllAlgorithmsThroughTheApi() throws Exception {
        var discovery = get("/api/algorithms");
        assertEquals(200, discovery.statusCode());
        assertEquals(8, body(discovery).size());
        for (JsonNode descriptor : body(discovery)) {
            var request = json.createObjectNode().put("algorithm", descriptor.path("id").asText()).put("maxTraceSteps", 1);
            if (!descriptor.path("category").asText().equals("SPANNING_TREE")) {
                request.put("startId", "tx-khuat-duy-tien-nguyen-trai");
                request.put("goalId", "tx-le-van-luong-hoang-dao-thuy");
            }
            var response = post("/api/algorithms/run", request.toString());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("SUCCESS", body(response).path("status").asText());
            assertEquals(descriptor.path("id").asText(), body(response).path("algorithm").asText());
            assertEquals(1, body(response).path("trace").size());
            assertTrue(body(response).path("metrics").path("traceTruncated").asBoolean());
        }
        var disabled = post("/api/algorithms/run", "{\"algorithm\":\"bfs\",\"maxTraceSteps\":0}");
        assertTrue(body(disabled).path("trace").isEmpty());
        assertEquals(8, body(disabled).path("metrics").path("traceStepCount").asInt());
    }

    @Test void validatesAlgorithmBodiesMethodsAndTraceLimits() throws Exception {
        assertError(get("/api/algorithms/run"), 405, "METHOD_NOT_ALLOWED");
        assertError(post("/api/algorithms", "{}"), 405, "METHOD_NOT_ALLOWED");
        assertError(get("/api/algorithms?unknown=x"), 400, "INVALID_REQUEST");
        for (String request : new String[]{"null", "{}", "{\"algorithm\":\"invalid\"}",
                "{\"algorithm\":\"dijkstra\"}", "{\"algorithm\":\"bfs\",\"startId\":\"missing\"}",
                "{\"algorithm\":\"bfs\",\"maxTraceSteps\":-1}", "{\"algorithm\":\"bfs\",\"maxTraceSteps\":10001}"}) {
            assertError(post("/api/algorithms/run", request), 400, "INVALID_REQUEST");
        }
        for (String request : new String[]{"[]", "{", "{\"algorithm\":2}",
                "{\"algorithm\":\"bfs\",\"maxTraceSteps\":\"1\"}",
                "{\"algorithm\":\"bfs\",\"maxTraceSteps\":1.5}",
                "{\"algorithm\":\"bfs\",\"unknown\":0}", "{\"algorithm\":\"bfs\",\"algorithm\":\"dfs\"}"}) {
            assertError(post("/api/algorithms/run", request), 400, "INVALID_JSON");
        }
        assertError(post("/api/algorithms/run", " ".repeat(16_385)), 413, "REQUEST_TOO_LARGE");
    }

    @Test void trafficObservationsAffectFastestRoutingAndCanBeReplaced() throws Exception {
        assertFalse(body(get("/api/traffic")).path("active").asBoolean());
        assertFalse(body(get("/api/health")).path("liveTrafficProviderConnected").asBoolean());
        JsonNode primary = body(post(REQUEST)).path("routes").get(0);
        String from = primary.path("path").get(0).path("id").asText();
        String to = primary.path("path").get(1).path("id").asText();
        Instant now = Instant.now();
        TrafficSnapshot observation = new TrafficSnapshot("Test observation", now.toString(), now.plusSeconds(3600).toString(),
                List.of(new TrafficSnapshot.Update(from, to, null, "driving", .1, false)));
        var updated = post("/api/traffic/update", json.writeValueAsString(observation));
        assertEquals(200, updated.statusCode(), updated.body());
        assertTrue(body(updated).path("active").asBoolean());
        assertEquals(1, body(get("/api/traffic")).path("updates").asInt());
        assertTrue(body(get("/api/health")).path("trafficObservationsActive").asBoolean());
        JsonNode shortest = body(post(REQUEST));
        var fastestRequest = json.readTree(REQUEST).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) fastestRequest).put("preference", "fastest");
        JsonNode fastest = body(post(fastestRequest.toString()));
        assertEquals("fastest", fastest.path("preference").asText());
        assertTrue(fastest.path("traffic").path("active").asBoolean());
        assertEquals("Test observation", fastest.path("traffic").path("source").asText());
        assertTrue(fastest.path("routes").get(0).path("durationSeconds").asLong()
                < shortest.path("routes").get(0).path("durationSeconds").asLong());
        assertEquals(1935, shortest.path("routes").get(0).path("distanceMeters").asDouble(), 1e-8);
        TrafficSnapshot cleared = new TrafficSnapshot("Cleared observations", now.plusSeconds(1).toString(),
                now.plusSeconds(3600).toString(), List.of());
        var clearedResponse = post("/api/traffic/update", json.writeValueAsString(cleared));
        assertEquals(200, clearedResponse.statusCode());
        assertFalse(body(clearedResponse).path("active").asBoolean());
        var clearedStatus = body(get("/api/traffic"));
        assertFalse(clearedStatus.path("active").asBoolean());
        assertEquals(0, clearedStatus.path("updates").asInt());
        assertFalse(body(get("/api/health")).path("trafficObservationsActive").asBoolean());
        assertEquals(primary.path("durationSeconds").asLong(), body(post(REQUEST)).path("routes").get(0).path("durationSeconds").asLong());
    }

    @Test void rejectsInvalidTrafficWithoutReplacingAcceptedObservations() throws Exception {
        Instant now = Instant.now();
        String accepted = json.writeValueAsString(new TrafficSnapshot("Accepted", now.toString(), now.plusSeconds(3600).toString(), List.of()));
        assertEquals(200, post("/api/traffic/update", accepted).statusCode());
        TrafficSnapshot unknownRoad = new TrafficSnapshot("Invalid", now.toString(), now.plusSeconds(3600).toString(),
                List.of(new TrafficSnapshot.Update("missing", "missing", null, "driving", 10.0, false)));
        TrafficSnapshot expired = new TrafficSnapshot("Expired", now.minusSeconds(60).toString(), now.minusSeconds(1).toString(), List.of());
        for (String content : new String[]{"null", json.writeValueAsString(unknownRoad), json.writeValueAsString(expired)}) {
            assertError(post("/api/traffic/update", content), 400, "INVALID_REQUEST");
            assertEquals("Accepted", body(get("/api/traffic")).path("source").asText());
        }
        assertError(get("/api/traffic/update"), 405, "METHOD_NOT_ALLOWED");
        assertError(post("/api/traffic", "{}"), 405, "METHOD_NOT_ALLOWED");
        assertError(post("/api/traffic/update", "{\"unknown\":1}"), 400, "INVALID_JSON");
        // 16 KiB is no longer the traffic body limit: this body fits but is invalid JSON.
        assertError(post("/api/traffic/update", " ".repeat(16_385)), 400, "INVALID_JSON");
        assertError(post("/api/traffic/update", " ".repeat(RoutingServer.MAX_TRAFFIC_BODY_BYTES + 1)),
                413, "REQUEST_TOO_LARGE");
        assertEquals("Accepted", body(get("/api/traffic")).path("source").asText());
        assertError(post(REQUEST.replace("\"alternatives\":3", "\"preference\":\"invalid\"")), 400, "INVALID_REQUEST");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String content) throws Exception {
        return post("/api/routes", content);
    }
    private HttpResponse<String> post(String path, String content) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(content)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode body(HttpResponse<String> response) throws Exception { return json.readTree(response.body()); }
    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, body(response).path("error").path("code").asText(), response.body());
    }
}
