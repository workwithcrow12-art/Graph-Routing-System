package graphroute.api;

import graphroute.io.GraphFileManager;
import graphroute.model.*;
import graphroute.routing.RoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ProblemReportHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void oversizedDeclaredBodyRespondsBeforeClientFinishesSending() throws Exception {
        try (var server = new RoutingServer(new RoutingService(new GraphFileManager().loadThanhXuanGraph()),
                0, Set.of(), 1, 2, Duration.ofSeconds(10))) {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write(("POST /api/routes HTTP/1.1\r\nHost: 127.0.0.1:" + server.port()
                        + "\r\nContent-Type: application/json\r\nContent-Length: 99999999\r\n\r\n{}")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                assertTrue(reader.readLine().contains("413"));
                int length = 0;
                for (String line; !(line = reader.readLine()).isEmpty();) {
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
                assertTrue(length > 0);
                char[] body = new char[length];
                int received = 0;
                while (received < length) {
                    int count = reader.read(body, received, length - received);
                    assertTrue(count > 0, "Complete JSON error must arrive before request-body timeout");
                    received += count;
                }
                assertEquals("REQUEST_TOO_LARGE", JSON.readTree(new String(body)).path("error").path("code").asText());
            }
        }
    }

    @Test void searchRequiresQueryAndHeadSendsNoBodyAndSearchReportsPagination() throws Exception {
        try (var server = new RoutingServer(new RoutingService(new GraphFileManager().loadThanhXuanGraph()), 0);
             var client = HttpClient.newHttpClient()) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.port());
            var missing = client.send(HttpRequest.newBuilder(base.resolve("/api/places")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, missing.statusCode());
            assertEquals("INVALID_REQUEST", JSON.readTree(missing.body()).path("error").path("code").asText());
            var head = client.send(HttpRequest.newBuilder(base.resolve("/api/health")).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, head.statusCode());
            assertEquals("", head.body());
            var search = client.send(HttpRequest.newBuilder(base.resolve("/api/places?q=nguyen&mode=walking&limit=1&offset=1")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, search.statusCode());
            var data = JSON.readTree(search.body());
            assertEquals(1, data.path("offset").asInt());
            assertTrue(data.path("total").asInt() > 1);
            assertEquals(1, data.path("places").size());
            assertFalse(data.path("places").get(0).path("modes").isEmpty());
        }
    }

    @Test void alternativeBudgetProbeReturnsStableErrorAndServerRecovers() throws Exception {
        Graph graph = new Graph(GraphType.DIRECTED);
        for (int i = 0; i <= 1800; i++) graph.addNode(new Node("n" + i, "Junction " + i, 0, 0));
        for (int i = 0; i < 1800; i++) {
            graph.addEdge(new Edge(graph.getNode("n" + i), graph.getNode("n" + (i + 1)), .01, "Main"));
            graph.addEdge(new Edge(graph.getNode("n" + i), graph.getNode("n" + (i + 1)), .02, "Parallel"));
        }
        try (var server = new RoutingServer(new RoutingService(graph), 0);
             var client = HttpClient.newHttpClient()) {
            server.start();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/routes"))
                    .header("Content-Type", "application/json").timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"origin\":\"n0\",\"destination\":\"n1800\",\"alternatives\":2}")).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(422, response.statusCode(), response.body());
            assertEquals("ROUTE_LIMIT_EXCEEDED", JSON.readTree(response.body()).path("error").path("code").asText());
            var health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
        }
    }

    @Test void schedulerShutdownRaceStillExecutesTransportCleanupWithInterruption() throws Exception {
        try (var executor = new DeadlineExecutor(1, 1, Duration.ofSeconds(10))) {
            var field = DeadlineExecutor.class.getDeclaredField("deadlines");
            field.setAccessible(true);
            ((ScheduledThreadPoolExecutor) field.get(executor)).shutdownNow();
            CompletableFuture<Boolean> cleaned = new CompletableFuture<>();
            executor.execute(() -> cleaned.complete(Thread.currentThread().isInterrupted()));
            assertTrue(cleaned.get(2, TimeUnit.SECONDS));
        }
    }
}
