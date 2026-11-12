package graphroute.api;

import graphroute.io.SampleGraphs;
import graphroute.routing.RoutingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class FrontendHttpTest {
    @TempDir Path temporary;

    @Test void servesUiAndApiOnOnePortWithCorrectHeadHeadersAndTraversalRejection() throws Exception {
        Files.createDirectories(temporary.resolve("assets"));
        Files.writeString(temporary.resolve("index.html"), "<!doctype html><h1>Routing UI</h1>");
        Files.writeString(temporary.resolve("assets/app.js"), "console.log('hello');");
        try (var server = new RoutingServer(new RoutingService(SampleGraphs.thanhXuan()), 0, Set.of(),
                2, 4, Duration.ofSeconds(10), new FrontendAssets(temporary)); var client = HttpClient.newHttpClient()) {
            server.start(); String base = "http://127.0.0.1:" + server.port();
            var ui = client.send(HttpRequest.newBuilder(URI.create(base + "/")).header("Accept", "text/html").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ui.statusCode()); assertTrue(ui.body().contains("Routing UI"));
            assertTrue(ui.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
            assertTrue(ui.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
            var head = client.send(HttpRequest.newBuilder(URI.create(base + "/assets/app.js"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, head.statusCode()); assertEquals("", head.body());
            assertTrue(head.headers().firstValue("Content-Type").orElseThrow().startsWith("text/javascript"));
            assertTrue(Integer.parseInt(head.headers().firstValue("Content-Length").orElseThrow()) > 0);
            for (String path : new String[]{"/assets/../pom.xml", "/assets/%2e%2e/pom.xml", "/assets/missing.js"}) {
                assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            var api = client.send(HttpRequest.newBuilder(URI.create(base + "/api")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, api.statusCode()); assertTrue(api.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
            var forbidden = client.send(HttpRequest.newBuilder(URI.create(base + "/assets/app.js"))
                    .header("Origin", "https://example.com").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, forbidden.statusCode());
        }
    }

    @Test void missingFrontendGivesActionableJsonErrorAndApiStillWorks() throws Exception {
        try (var server = new RoutingServer(new RoutingService(SampleGraphs.thanhXuan()), 0, Set.of(),
                2, 4, Duration.ofSeconds(10), new FrontendAssets(temporary)); var client = HttpClient.newHttpClient()) {
            server.start(); String base = "http://127.0.0.1:" + server.port();
            var missing = client.send(HttpRequest.newBuilder(URI.create(base + "/")).header("Accept", "text/html").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, missing.statusCode()); assertTrue(missing.body().contains("FRONTEND_NOT_BUILT"));
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(base + "/api/health")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
