package graphroute.api;

import com.sun.net.httpserver.HttpExchange;
import graphroute.routing.RoutingException;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Exact Host/Origin checks for a loopback service and explicit browser development origins. */
final class HttpPolicy {
    private static final Pattern ENCODING_WEIGHT =
            Pattern.compile("q=(?:0(?:\\.[0-9]{0,3})?|1(?:\\.0{0,3})?)", Pattern.CASE_INSENSITIVE);

    private final Set<String> origins;
    HttpPolicy(Set<String> origins) {
        this.origins = Set.copyOf(origins);
        for (String origin : origins) {
            URI uri = URI.create(origin);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty()))
                throw new IllegalArgumentException("CORS origins must be exact http(s) origins without path or credentials");
        }
    }

    void check(HttpExchange exchange, int port) {
        List<String> hosts = exchange.getRequestHeaders().get("Host");
        String host = hosts == null || hosts.size() != 1 ? "" : hosts.getFirst().toLowerCase(Locale.ROOT);
        Set<String> accepted = new HashSet<>(Set.of("127.0.0.1:" + port, "localhost:" + port));
        if (port == 80) accepted.addAll(Set.of("localhost", "127.0.0.1"));
        if (!accepted.contains(host)) throw new RoutingException("HOST_NOT_ALLOWED",
                "Host must match this loopback server and port; configure a development proxy with changeOrigin: true");
        List<String> values = exchange.getRequestHeaders().get("Origin");
        exchange.getResponseHeaders().add("Vary", "Origin");
        if (values == null) return;
        if (values.size() != 1) throw new RoutingException("ORIGIN_NOT_ALLOWED", "Exactly one Origin is allowed");
        String origin = values.getFirst();
        boolean sameOrigin = origin.equals("http://127.0.0.1:" + port) || origin.equals("http://localhost:" + port)
                || (port == 80 && (origin.equals("http://127.0.0.1") || origin.equals("http://localhost")));
        if (!sameOrigin && !origins.contains(origin))
            throw new RoutingException("ORIGIN_NOT_ALLOWED", "Origin is not configured; use --cors-origin for the browser origin, including when forwarded by a development proxy");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
    }

    void preflight(HttpExchange exchange, String method) {
        String requested = exchange.getRequestHeaders().getFirst("Access-Control-Request-Method");
        boolean head = method.equals("GET") && "HEAD".equals(requested);
        if (exchange.getRequestHeaders().getFirst("Origin") == null || (!method.equals(requested) && !head))
            throw new RoutingException("INVALID_PREFLIGHT", "Preflight requires Origin and the endpoint's allowed method");
        String headers = exchange.getRequestHeaders().getFirst("Access-Control-Request-Headers");
        if (headers != null && Arrays.stream(headers.split(",")).anyMatch(h -> !h.trim().equalsIgnoreCase("Content-Type")))
            throw new RoutingException("INVALID_PREFLIGHT", "Only Content-Type is allowed in browser requests");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", method.equals("GET") ? "GET, HEAD" : method);
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "600");
    }

    static boolean acceptsGzip(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().get("Accept-Encoding");
        if (values == null) return false;
        Boolean gzip = null, wildcard = null;
        for (String entry : String.join(",", values).split(",")) {
            String[] parts = entry.split(";", -1);
            String coding = parts[0].trim().toLowerCase(Locale.ROOT);
            if (!coding.equals("gzip") && !coding.equals("*")) continue;
            String weight = parts.length == 2 ? parts[1].trim() : "";
            boolean accepted = parts.length == 1 || (ENCODING_WEIGHT.matcher(weight).matches()
                    && Double.parseDouble(weight.substring(2)) > 0);
            if (coding.equals("gzip")) gzip = accepted;
            else wildcard = accepted;
        }
        return gzip != null ? gzip : Boolean.TRUE.equals(wildcard);
    }
}
