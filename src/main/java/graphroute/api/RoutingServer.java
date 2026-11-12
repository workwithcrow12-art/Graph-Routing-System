package graphroute.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import graphroute.routing.RouteRequest;
import graphroute.routing.RoutingException;
import graphroute.routing.RoutingService;
import graphroute.routing.TrafficSnapshot;
import graphroute.analysis.AlgorithmRequest;
import graphroute.analysis.AlgorithmService;
import graphroute.analysis.AnalysisWorkspace;
import graphroute.algorithm.AlgorithmResult;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.time.Duration;

/** Local-only JSON API. No JavaFX runtime or internet access is needed. */
public final class RoutingServer implements AutoCloseable {
    private static final int MAX_BODY_BYTES = 16_384;
    public static final int MAX_TRAFFIC_BODY_BYTES = 8 * 1024 * 1024;
    public static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final Set<String> POST_PATHS = Set.of("/api/routes", "/api/algorithms/run",
            "/api/traffic/update", "/api/analysis/graph/edit");
    private final RoutingService routing;
    private final AnalysisWorkspace analysis;
    private final HttpPolicy policy;
    private final byte[] mapJson;
    private final byte[] mapGzip;
    private final HttpServer server;
    private final DeadlineExecutor executor;
    private final FrontendAssets frontend;
    private final ObjectMapper json = createMapper();

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = JsonMapper.builder(JsonFactory.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    public RoutingServer(RoutingService routing, int port) throws IOException {
        this(routing, port, Set.of());
    }

    public RoutingServer(RoutingService routing, int port, Set<String> corsOrigins) throws IOException {
        this(routing, port, corsOrigins, Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())),
                32, Duration.ofSeconds(15));
    }

    RoutingServer(RoutingService routing, int port, Set<String> corsOrigins,
                  int workers, int queueCapacity, Duration timeout) throws IOException {
        this(routing, port, corsOrigins, workers, queueCapacity, timeout, new FrontendAssets());
    }

    RoutingServer(RoutingService routing, int port, Set<String> corsOrigins,
                  int workers, int queueCapacity, Duration timeout, FrontendAssets frontend) throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port must be between 0 and 65535");
        this.routing = java.util.Objects.requireNonNull(routing);
        this.frontend = java.util.Objects.requireNonNull(frontend);
        this.policy = new HttpPolicy(corsOrigins);
        this.analysis = new AnalysisWorkspace(routing.network());
        this.mapJson = BoundedJson.encode(json, routing.map(), MAX_RESPONSE_BYTES);
        this.mapGzip = gzip(mapJson);
        this.executor = new DeadlineExecutor(workers, queueCapacity, timeout);
        try {
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
        } catch (IOException | RuntimeException exception) {
            executor.close();
            throw exception;
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() {
        try { server.stop(0); } finally { executor.close(); }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            policy.check(exchange, port());
            String path = exchange.getRequestURI().getPath();
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (path.equals("/index.html") || path.startsWith("/assets/")
                    || path.equals("/") && accept != null && accept.contains("text/html")) {
                serveFrontend(exchange, path);
                return;
            }
            String expectedMethod = POST_PATHS.contains(path) ? "POST" : "GET";
            if (!Set.of("/", "/api", "/api/health", "/api/map", "/api/places", "/api/nearest", "/api/routes",
                    "/api/algorithms", "/api/algorithms/run", "/api/traffic", "/api/traffic/update",
                    "/api/analysis/graphs", "/api/analysis/graph", "/api/analysis/graph/edit").contains(path)) {
                error(exchange, 404, "NOT_FOUND", "No endpoint exists at this path");
                return;
            }
            if (exchange.getRequestMethod().equals("OPTIONS")) {
                policy.preflight(exchange, expectedMethod);
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            boolean head = exchange.getRequestMethod().equals("HEAD") && expectedMethod.equals("GET");
            if (!exchange.getRequestMethod().equals(expectedMethod) && !head) {
                exchange.getResponseHeaders().set("Allow", expectedMethod.equals("GET") ? "GET, HEAD" : expectedMethod);
                error(exchange, 405, "METHOD_NOT_ALLOWED", "Use " + expectedMethod + " for this endpoint");
                return;
            }
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            switch (path) {
                case "/", "/api" -> {
                    allowOnly(query, Set.of());
                    send(exchange, 200, Map.of("name", "Graph Routing API", "version", "1.1",
                            "analysisWorkspace", List.of("GET /api/analysis/graphs", "GET /api/analysis/graph?graph=sample",
                                    "POST /api/analysis/graph/edit"),
                            "trafficLimits", Map.of("maxUpdates", TrafficSnapshot.MAX_UPDATES, "maxBodyBytes", MAX_TRAFFIC_BODY_BYTES),
                            "endpoints", Map.of(
                            "health", "GET /api/health", "map", "GET /api/map", "search", "GET /api/places?q=nguyen&limit=10",
                            "nearest", "GET /api/nearest?lat=20.99351&lng=105.80436&maxDistanceMeters=2000",
                            "route", "POST /api/routes", "algorithms", "GET /api/algorithms",
                            "runAlgorithm", "POST /api/algorithms/run", "traffic", "GET /api/traffic",
                            "updateTraffic", "POST /api/traffic/update")));
                }
                case "/api/health" -> {
                    allowOnly(query, Set.of());
                    var map = routing.map();
                    send(exchange, 200, Map.of("status", "ok", "dataset", map.dataset(), "places", map.places().size(),
                            "directedRoadSegments", map.roads().size(), "trafficObservationsActive", routing.trafficStatus().active(),
                            "liveTrafficProviderConnected", false, "provenance", map.provenance()));
                }
                case "/api/map" -> {
                    allowOnly(query, Set.of("bbox"));
                    if (!query.containsKey("bbox")) sendBytes(exchange, 200, mapJson, mapGzip);
                    else {
                        String[] parts = query.get("bbox").split(",", -1);
                        if (parts.length != 4) throw new IllegalArgumentException("bbox requires south,west,north,east");
                        send(exchange, 200, routing.map(new RoutingService.Bounds(number(parts[0], "south"),
                                number(parts[1], "west"), number(parts[2], "north"), number(parts[3], "east"))));
                    }
                }
                case "/api/analysis/graphs" -> { allowOnly(query, Set.of()); send(exchange, 200, analysis.graphs()); }
                case "/api/analysis/graph" -> {
                    allowOnly(query, Set.of("graph"));
                    send(exchange, 200, analysis.view(query.get("graph")));
                }
                case "/api/places" -> {
                    allowOnly(query, Set.of("q", "limit", "offset", "mode"));
                    if (query.get("q") == null || query.get("q").isBlank()) throw new IllegalArgumentException("q is required and must not be blank");
                    var results = routing.search(query.get("q"), integer(query.getOrDefault("limit", "10"), "limit"),
                            integer(query.getOrDefault("offset", "0"), "offset"), query.get("mode"));
                    send(exchange, 200, Map.of("places", results.places(), "total", results.total(), "offset", results.offset()));
                }
                case "/api/nearest" -> {
                    allowOnly(query, Set.of("lat", "lng", "maxDistanceMeters", "mode"));
                    send(exchange, 200, routing.nearest(number(query.get("lat"), "lat"), number(query.get("lng"), "lng"),
                            number(query.getOrDefault("maxDistanceMeters", "2000"), "maxDistanceMeters"), query.get("mode")));
                }
                case "/api/algorithms" -> { allowOnly(query, Set.of()); send(exchange, 200, AlgorithmService.algorithms()); }
                case "/api/traffic" -> { allowOnly(query, Set.of()); send(exchange, 200, routing.trafficStatus()); }
                case "/api/routes", "/api/algorithms/run", "/api/traffic/update", "/api/analysis/graph/edit" -> {
                    allowOnly(query, Set.of());
                    String type = exchange.getRequestHeaders().getFirst("Content-Type");
                    if (type == null || !type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT).equals("application/json")) {
                        error(exchange, 415, "UNSUPPORTED_MEDIA_TYPE", "Use Content-Type: application/json");
                        return;
                    }
                    int maximum = path.equals("/api/traffic/update") ? MAX_TRAFFIC_BODY_BYTES : MAX_BODY_BYTES;
                    String declaredLength = exchange.getRequestHeaders().getFirst("Content-Length");
                    if (declaredLength != null && Long.parseLong(declaredLength) > maximum) {
                        exchange.getResponseHeaders().set("Connection", "close");
                        error(exchange, 413, "REQUEST_TOO_LARGE", "Request body must not exceed " + maximum + " bytes for this endpoint");
                        // The response is already flushed. Consume a bounded amount of
                        // an upload already in flight so closing a socket with unread
                        // data does not reset away the JSON body on Windows. Stalled
                        // clients remain covered by the transport deadline.
                        discardRejectedBody(exchange, maximum + 1L);
                        return;
                    }
                    byte[] body = exchange.getRequestBody().readNBytes(maximum + 1);
                    if (body.length > maximum) {
                        error(exchange, 413, "REQUEST_TOO_LARGE", "Request body must not exceed " + maximum + " bytes for this endpoint");
                        return;
                    }
                    Object response = switch (path) {
                        case "/api/routes" -> routing.route(json.readValue(body, RouteRequest.class));
                        case "/api/algorithms/run" -> analysis.execute(json.readValue(body, AlgorithmRequest.class));
                        case "/api/analysis/graph/edit" -> analysis.edit(json.readValue(body, AnalysisWorkspace.Edit.class));
                        case "/api/traffic/update" -> routing.updateTraffic(json.readValue(body, TrafficSnapshot.class));
                        default -> throw new IllegalStateException("Unhandled POST endpoint");
                    };
                    int status = response instanceof graphroute.analysis.AlgorithmResponse result
                            && result.status() == AlgorithmResult.Status.INVALID ? 422 : 200;
                    send(exchange, status, response);
                }
                default -> throw new IllegalStateException("Unhandled endpoint");
            }
        } catch (RoutingException exception) {
            int status = switch (exception.getCode()) {
                case "PLACE_NOT_FOUND" -> 404;
                case "NO_ROUTE", "OUTSIDE_COVERAGE" -> 422;
                case "ROUTE_LIMIT_EXCEEDED", "ANALYSIS_LIMIT_EXCEEDED", "RESPONSE_LIMIT_EXCEEDED" -> 422;
                case "HOST_NOT_ALLOWED", "ORIGIN_NOT_ALLOWED", "INVALID_PREFLIGHT" -> 403;
                case "REVISION_CONFLICT" -> 409;
                default -> 400;
            };
            error(exchange, status, exception.getCode(), exception.getMessage());
        } catch (JsonProcessingException exception) {
            error(exchange, 400, "INVALID_JSON", "Expected a valid object with known fields and correctly typed values");
        } catch (graphroute.algorithm.shortestpath.SearchBudget.LimitExceededException exception) {
            error(exchange, 422, "ANALYSIS_LIMIT_EXCEEDED", exception.getMessage());
        } catch (java.util.concurrent.CancellationException exception) {
            error(exchange, 503, "REQUEST_CANCELLED", "The request was cancelled");
        } catch (IllegalArgumentException exception) {
            error(exchange, 400, "INVALID_REQUEST", exception.getMessage());
        } catch (IOException exception) {
            // A timed-out/disconnected transport cannot receive another error response.
            throw exception;
        } catch (Exception exception) {
            System.err.println("Routing request failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            error(exchange, 500, "INTERNAL_ERROR", "The request could not be completed");
        } finally {
            exchange.close();
        }
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = BoundedJson.encode(json, body, MAX_RESPONSE_BYTES);
        sendBytes(exchange, status, bytes, null);
    }

    private void serveFrontend(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        if (method.equals("OPTIONS")) {
            policy.preflight(exchange, "GET"); exchange.sendResponseHeaders(204, -1); return;
        }
        if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            error(exchange, 405, "METHOD_NOT_ALLOWED", "Use GET or HEAD for frontend assets"); return;
        }
        FrontendAssets.Asset asset = frontend.read(path);
        if (asset == null) {
            boolean index = path.equals("/") || path.equals("/index.html");
            error(exchange, index ? 503 : 404, index ? "FRONTEND_NOT_BUILT" : "NOT_FOUND",
                    index ? "Build src/main/frontend with npm ci and npm run build before packaging or serving the UI" : "Unknown frontend asset");
            return;
        }
        byte[] bytes = asset.bytes();
        exchange.getResponseHeaders().add("Vary", "Accept, Accept-Encoding");
        if (bytes.length >= 1024 && HttpPolicy.acceptsGzip(exchange)) {
            bytes = gzip(bytes); exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        exchange.getResponseHeaders().set("Content-Type", asset.contentType());
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'");
        if (method.equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.getResponseBody().flush();
        }
    }

    private void sendBytes(HttpExchange exchange, int status, byte[] bytes, byte[] compressed) throws IOException {
        exchange.getResponseHeaders().add("Vary", "Accept-Encoding");
        if (bytes.length >= 1024 && HttpPolicy.acceptsGzip(exchange)) {
            bytes = compressed != null ? compressed : gzip(bytes);
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            // Flush before exchange.close() attempts to drain an unfinished request body.
            exchange.getResponseBody().flush();
        }
    }

    private static byte[] gzip(byte[] data) throws IOException {
        var output = new java.io.ByteArrayOutputStream();
        try (var stream = new java.util.zip.GZIPOutputStream(output)) { stream.write(data); }
        return output.toByteArray();
    }

    private static void discardRejectedBody(HttpExchange exchange, long maximum) {
        byte[] buffer = new byte[8192];
        try {
            while (maximum > 0) {
                int read = exchange.getRequestBody().read(buffer, 0, (int) Math.min(buffer.length, maximum));
                if (read < 0) break;
                maximum -= read;
            }
        } catch (IOException disconnected) { /* The error response has already been sent. */ }
    }

    private void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        send(exchange, status, Map.of("error", Map.of("code", code, "message", message)));
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return values;
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Repeated query parameter: " + key);
        }
        return values;
    }

    private static void allowOnly(Map<String, String> query, Set<String> keys) {
        for (String key : query.keySet()) if (!keys.contains(key)) throw new IllegalArgumentException("Unknown query parameter: " + key);
    }
    private static int integer(String value, String name) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(name + " must be an integer"); }
    }
    private static double number(String value, String name) {
        if (value == null) throw new IllegalArgumentException("Missing query parameter: " + name);
        try { return Double.parseDouble(value); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(name + " must be a number"); }
    }
}
