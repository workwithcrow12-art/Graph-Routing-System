package graphroute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphroute.api.RoutingServer;
import graphroute.io.GraphFileManager;
import graphroute.model.*;
import graphroute.routing.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class ApiReviewRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static RoutingServer start(Graph graph, Set<String> origins) throws IOException {
        var server = new RoutingServer(new RoutingService(graph), 0, origins);
        server.start();
        return server;
    }
    private static HttpRequest.Builder request(RoutingServer server, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).timeout(Duration.ofSeconds(40));
    }
    private static HttpResponse<String> post(HttpClient client, RoutingServer server, String path, Object body) throws Exception {
        return client.send(request(server, path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void mapArcIdsAllowParallelTrafficUpdatesOverHttp() throws Exception {
        Graph graph = new Graph(GraphType.DIRECTED);
        Node a = new Node("a", "A", 21, 105), b = new Node("b", "B", 21, 105.001);
        graph.addNode(a); graph.addNode(b);
        for (int i = 0; i < 2; i++) graph.addEdge(new Edge(a, b, 1, "Parallel"));
        try (var server = start(graph, Set.of()); var client = HttpClient.newHttpClient()) {
            var map = client.send(request(server, "/api/map").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, map.statusCode(), map.body());
            var roads = JSON.readTree(map.body()).path("roads");
            String first = roads.get(0).path("id").asText(), second = roads.get(1).path("id").asText();
            assertFalse(first.isBlank());
            assertFalse(second.isBlank());
            assertNotEquals(first, second);
            Instant now = Instant.now();
            var payload = new TrafficSnapshot("parallel observation", now.toString(), now.plusSeconds(600).toString(), List.of(
                    new TrafficSnapshot.Update("a", "b", null, "driving", null, true, first),
                    new TrafficSnapshot.Update("a", "b", null, "driving", 10.0, false, second)));
            var response = post(client, server, "/api/traffic/update", payload);
            assertEquals(200, response.statusCode(), response.body());
            var route = post(client, server, "/api/routes", new RouteRequest("a", "b"));
            assertEquals(200, route.statusCode(), route.body());
            assertEquals(second, JSON.readTree(route.body()).path("routes").get(0).path("segments").get(0).path("id").asText());
        }
    }

    @Test void developmentProxyRequiresRewrittenHostAndAllowedForwardedOrigin() throws Exception {
        String origin = "http://localhost:5173";
        for (var origins : List.of(Set.<String>of(), Set.of(origin))) {
            try (var server = start(new GraphFileManager().loadThanhXuanGraph(), origins);
                 var client = HttpClient.newHttpClient()) {
                // HttpClient supplies the backend Host, as a changeOrigin proxy would.
                var rewritten = client.send(request(server, "/api/health").header("Origin", origin).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(origins.isEmpty() ? 403 : 200, rewritten.statusCode());
                try (Socket socket = new Socket("127.0.0.1", server.port())) {
                    socket.setSoTimeout(5000);
                    int frontendPort = server.port() == 5173 ? 5174 : 5173;
                    socket.getOutputStream().write(("GET /api/health HTTP/1.1\r\nHost: localhost:" + frontendPort
                            + "\r\nOrigin: " + origin + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                    assertNotNull(status);
                    assertTrue(status.contains("403"), status);
                }
            }
        }
    }

    @Test void tenThousandTrafficUpdatesFitTheDedicatedBodyLimitAndCountLimitRemainsEnforced() throws Exception {
        Graph graph = new Graph(GraphType.DIRECTED);
        Node a = new Node("a", "A", 21, 105), b = new Node("b", "B", 21, 105.001);
        graph.addNode(a); graph.addNode(b);
        for (int i = 0; i < 10000; i++) graph.addEdge(new Edge(a, b, .2, "Road " + i,
                List.of(new Edge.Point(21, 105), new Edge.Point(21, 105.001)),
                Map.of(Edge.Mode.DRIVING, 30.0), "way-" + i, Map.of()));
        var updates = new ArrayList<TrafficSnapshot.Update>();
        for (int i = 0; i < 10000; i++) updates.add(new TrafficSnapshot.Update("a", "b", "way-" + i, "driving", 10.0, false));
        Instant now = Instant.now();
        try (var server = start(graph, Set.of()); var client = HttpClient.newHttpClient()) {
            for (int count : List.of(300, 10000)) {
                var payload = new TrafficSnapshot("large feed", now.toString(), now.plusSeconds(3600).toString(), updates.subList(0, count));
                assertTrue(JSON.writeValueAsBytes(payload).length > 16384);
                var response = post(client, server, "/api/traffic/update", payload);
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(count, JSON.readTree(response.body()).path("updates").asInt());
            }
            updates.add(updates.getFirst());
            var tooMany = post(client, server, "/api/traffic/update",
                    new TrafficSnapshot("too many", now.toString(), now.plusSeconds(3600).toString(), updates));
            assertEquals(400, tooMany.statusCode());
            var tooLarge = client.send(request(server, "/api/traffic/update").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(" ".repeat(RoutingServer.MAX_TRAFFIC_BODY_BYTES + 1))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(413, tooLarge.statusCode());
        }
    }

    @Test void explicitCorsPreflightAndHostChecksRejectUntrustedBrowserRequests() throws Exception {
        String origin = "http://localhost:5173";
        try (var server = start(new GraphFileManager().loadThanhXuanGraph(), Set.of(origin));
             var client = HttpClient.newHttpClient()) {
            var preflight = client.send(request(server, "/api/routes").header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, preflight.statusCode());
            assertEquals(origin, preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            assertEquals("POST", preflight.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
            for (String blocked : List.of("https://attacker.example", "null", "http://localhost:51730")) {
                var response = client.send(request(server, "/api/health").header("Origin", blocked).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, response.statusCode());
                assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            }
            var valid = client.send(request(server, "/api/health").header("Origin", origin).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, valid.statusCode());
            var badHeader = client.send(request(server, "/api/routes").header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "x-unapproved")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, badHeader.statusCode());
            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(("GET /api/health HTTP/1.1\r\nHost: attacker.example:" + server.port()
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(response.startsWith("HTTP/1.1 403"), response);
                assertTrue(response.contains("HOST_NOT_ALLOWED"));
            }
        }
    }

    @Test void gzipMapDecodesToTheSameJsonAndHonorsExplicitQZero() throws Exception {
        try (var server = start(new GraphFileManager().loadDefaultGraph(), Set.of());
             var client = HttpClient.newHttpClient()) {
            var plain = client.send(request(server, "/api/map").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            var compressed = client.send(request(server, "/api/map").header("Accept-Encoding", "br, gzip;q=0.8").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals("gzip", compressed.headers().firstValue("Content-Encoding").orElseThrow());
            assertTrue(compressed.body().length < plain.body().length / 2);
            try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed.body()))) {
                assertArrayEquals(plain.body(), input.readAllBytes());
            }
            var disabled = client.send(request(server, "/api/map").header("Accept-Encoding", "gzip;q=0,*;q=1").GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertTrue(disabled.headers().firstValue("Content-Encoding").isEmpty());
            assertArrayEquals(plain.body(), disabled.body());
        }
    }

    @Test void allEightAlgorithmsRunOnSampleWithOsmDefaultAndFailuresHaveActionableReasons() throws Exception {
        var graph = new GraphFileManager().loadDefaultGraph();
        try (var server = start(graph, Set.of()); var client = HttpClient.newHttpClient()) {
            var response = client.send(request(server, "/api/analysis/graph?graph=sample").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var nodes = JSON.readTree(response.body()).path("nodes");
            for (String algorithm : List.of("astar", "dijkstra", "bellman-ford", "floyd-warshall", "bfs", "dfs", "prim", "kruskal")) {
                var body = JSON.createObjectNode().put("algorithm", algorithm).put("graph", "sample");
                if (!Set.of("prim", "kruskal").contains(algorithm))
                    body.put("startId", nodes.get(0).path("id").asText()).put("goalId", nodes.get(7).path("id").asText());
                var result = post(client, server, "/api/algorithms/run", body);
                assertEquals(200, result.statusCode(), result.body());
                assertEquals("SUCCESS", JSON.readTree(result.body()).path("status").asText());
            }
            var refused = post(client, server, "/api/algorithms/run", Map.of("algorithm", "prim"));
            assertEquals(422, refused.statusCode());
            assertEquals("INVALID", JSON.readTree(refused.body()).path("status").asText());
            assertTrue(JSON.readTree(refused.body()).path("message").asText().contains("undirected"));
        }
    }

    @Test void graphCatalogExplainsAlgorithmAvailabilityBeforeExecution() throws Exception {
        try (var server = start(new GraphFileManager().loadDefaultGraph(), Set.of());
             var client = HttpClient.newHttpClient()) {
            var response = client.send(request(server, "/api/analysis/graphs").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var catalog = JSON.readTree(response.body());
            assertEquals(3, catalog.size());
            for (var dataset : catalog) {
                boolean loaded = dataset.path("id").asText().equals("loaded");
                assertEquals(8, dataset.path("algorithms").size());
                for (var algorithm : dataset.path("algorithms")) {
                    boolean supported = !loaded || Set.of("DIJKSTRA", "ASTAR", "BFS", "DFS")
                            .contains(algorithm.path("algorithm").asText());
                    assertEquals(supported, algorithm.path("available").asBoolean(), algorithm.toString());
                    if (!supported) assertTrue(algorithm.path("message").asText().contains("sample"));
                }
            }
        }
    }

    @Test void missingEditCoordinatesAndWeightDoNotBecomeZeroOrChangeRevision() throws Exception {
        try (var server = start(new GraphFileManager().loadThanhXuanGraph(), Set.of());
             var client = HttpClient.newHttpClient()) {
            var initial = JSON.readTree(client.send(request(server, "/api/analysis/graph?graph=custom").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            for (String field : List.of("latitude", "longitude")) {
                var node = JSON.createObjectNode().put("id", "missing-coordinate").put("name", "Invalid")
                        .put("latitude", 21.0).put("longitude", 105.0);
                node.remove(field);
                var body = JSON.createObjectNode().put("operation", "addNode").put("expectedRevision", 0);
                body.set("node", node);
                assertEquals(400, post(client, server, "/api/analysis/graph/edit", body).statusCode());
                node.putNull(field);
                assertEquals(400, post(client, server, "/api/analysis/graph/edit", body).statusCode());
            }
            var edge = JSON.createObjectNode()
                    .put("source", initial.path("nodes").get(0).path("id").asText())
                    .put("target", initial.path("nodes").get(1).path("id").asText());
            var body = JSON.createObjectNode().put("operation", "addEdge").put("expectedRevision", 0);
            body.set("edge", edge);
            assertEquals(400, post(client, server, "/api/analysis/graph/edit", body).statusCode());
            edge.putNull("weight");
            assertEquals(400, post(client, server, "/api/analysis/graph/edit", body).statusCode());
            var unchanged = JSON.readTree(client.send(request(server, "/api/analysis/graph?graph=custom").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(initial, unchanged);
            // Explicit zero is a legitimate graph weight, unlike an omitted value.
            edge.put("weight", 0);
            assertEquals(200, post(client, server, "/api/analysis/graph/edit", body).statusCode());
        }
    }

    @Test void customGraphEditsUseRevisionsAndDoNotAlterTheRoadMap() throws Exception {
        try (var server = start(new GraphFileManager().loadThanhXuanGraph(), Set.of());
             var client = HttpClient.newHttpClient()) {
            var add = Map.of("operation", "addNode", "expectedRevision", 0,
                    "node", Map.of("id", "new", "name", "New", "latitude", 21, "longitude", 105));
            var result = post(client, server, "/api/analysis/graph/edit", add);
            assertEquals(200, result.statusCode(), result.body());
            assertEquals(9, JSON.readTree(result.body()).path("nodes").size());
            assertEquals(409, post(client, server, "/api/analysis/graph/edit", add).statusCode());
            var reset = post(client, server, "/api/analysis/graph/edit", Map.of("operation", "reset", "expectedRevision", 1));
            assertEquals(8, JSON.readTree(reset.body()).path("nodes").size());
            JsonNode health = JSON.readTree(client.send(request(server, "/api/health").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(8, health.path("places").asInt());
        }
    }
}
