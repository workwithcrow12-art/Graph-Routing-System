package graphroute.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphroute.io.GraphFileManager;
import graphroute.io.OsmImporter;
import graphroute.model.Graph;
import graphroute.model.Node;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit bounded downloads. Existing valid extracts are reused unless --force is supplied. */
public final class OSMDataUpdater {
    private static final URI DEFAULT_ENDPOINT = URI.create("https://overpass-api.de/api/interpreter");
    private static final String USER_AGENT = "GraphRoutingSystem/2.0 (bounded educational road-network importer)";

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        boolean force = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--force")) { force = true; continue; }
            if (!java.util.Set.of("--bbox", "--output", "--endpoint").contains(args[i]) || i + 1 >= args.length) {
                throw new IllegalArgumentException("Usage: OSMDataUpdater --bbox south,west,north,east --output extract.json [--endpoint HTTPS_URL] [--force]");
            }
            if (options.put(args[i], args[++i]) != null) throw new IllegalArgumentException("Duplicate option");
        }
        if (!options.containsKey("--bbox") || !options.containsKey("--output")) throw new IllegalArgumentException("--bbox and --output are required");
        String[] values = options.get("--bbox").split(",", -1);
        if (values.length != 4) throw new IllegalArgumentException("--bbox must be south,west,north,east");
        BoundingBox bbox = new BoundingBox(Double.parseDouble(values[0]), Double.parseDouble(values[1]), Double.parseDouble(values[2]), Double.parseDouble(values[3]));
        Graph graph = download(bbox, Path.of(options.get("--output")), URI.create(options.getOrDefault("--endpoint", DEFAULT_ENDPOINT.toString())), force);
        System.out.printf("Validated OSM network: %d junctions, %d directed arcs. %s%n", graph.nodeCount(), graph.edgeCount(), graph.getProvenance().get("attribution"));
    }

    public static Graph download(BoundingBox bbox, Path output, URI endpoint, boolean force) throws IOException, InterruptedException {
        if (bbox == null || output == null || endpoint == null) throw new IllegalArgumentException("Bounding box, output and endpoint are required");
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null) throw new IllegalArgumentException("Overpass endpoint must be an HTTPS URL without credentials");
        Path destination = output.toAbsolutePath().normalize();
        Path metadataFile = destination.resolveSibling(destination.getFileName() + ".metadata.json");
        String query = bbox.query();
        ObjectMapper mapper = new ObjectMapper();
        if (Files.exists(destination) && !force) {
            var metadata = Files.exists(metadataFile) ? mapper.readTree(metadataFile.toFile()) : null;
            if (metadata == null || !query.equals(metadata.path("query").asText())) {
                throw new IllegalArgumentException("Existing extract has no matching bbox metadata; choose another output or use --force");
            }
            if (Files.size(destination) > OsmImporter.MAX_INPUT_BYTES || !sha256(Files.readAllBytes(destination)).equals(metadata.path("sha256").asText())) {
                throw new IOException("Existing cache checksum does not match metadata; use --force to refresh");
            }
            return new GraphFileManager().loadOsm(destination);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT).header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8))).build();
        byte[] bytes;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()) {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) throw new IOException("Overpass HTTP " + response.statusCode() + "; existing cache was preserved");
                bytes = readBounded(body, Duration.ofSeconds(60));
            }
        }
        if (bytes.length > OsmImporter.MAX_INPUT_BYTES) throw new IOException("Overpass extract exceeds 64 MiB; reduce the bounding box");
        Graph graph = new OsmImporter().load(new java.io.ByteArrayInputStream(bytes));
        if (graph.isEmpty()) throw new IOException("Overpass returned no accessible roads; existing cache was preserved");
        Files.createDirectories(destination.getParent());
        Map<String, String> metadata = new LinkedHashMap<>(graph.getProvenance());
        metadata.put("name", "OpenStreetMap extract " + destination.getFileName());
        metadata.put("queryBbox", String.format(Locale.ROOT, "%.7f,%.7f,%.7f,%.7f", bbox.south(), bbox.west(), bbox.north(), bbox.east()));
        metadata.put("downloadedAt", Instant.now().toString());
        metadata.put("endpoint", endpoint.toString());
        metadata.put("query", query);
        metadata.put("sha256", sha256(bytes));
        graph.setProvenance(metadata);
        // A crash between these atomic moves is detected by the checksum on the next load.
        writeAtomic(destination, bytes);
        writeAtomic(metadataFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(metadata));
        return graph;
    }

    static byte[] readBounded(InputStream body, Duration timeout) throws IOException {
        AtomicBoolean expired = new AtomicBoolean();
        var timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "osm-body-timeout");
            thread.setDaemon(true);
            return thread;
        });
        var watchdog = timer.schedule(() -> {
            expired.set(true);
            try { body.close(); } catch (IOException ignored) { }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            byte[] bytes = body.readNBytes(OsmImporter.MAX_INPUT_BYTES + 1);
            if (expired.get()) throw new IOException("Overpass response body timed out");
            if (bytes.length > OsmImporter.MAX_INPUT_BYTES) throw new IOException("Overpass extract exceeds 64 MiB; reduce the bounding box");
            return bytes;
        } finally {
            watchdog.cancel(false);
            timer.shutdownNow();
        }
    }

    private static String sha256(byte[] data) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void writeAtomic(Path destination, byte[] data) throws IOException {
        Path temp = Files.createTempFile(destination.getParent(), "osm-download-", ".tmp");
        try {
            Files.write(temp, data);
            try { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    public record BoundingBox(double south, double west, double north, double east) {
        public BoundingBox {
            Node.validateCoordinates(south, west);
            Node.validateCoordinates(north, east);
            if (south >= north || west >= east) throw new IllegalArgumentException("Bounding box requires south<north and west<east");
            if ((north - south) * (east - west) > 0.01 || north - south > 0.2 || east - west > 0.2) throw new IllegalArgumentException("Use a bounded local extract: at most 0.01 square degrees and 0.2 degrees per side");
        }
        public String query() {
            return String.format(Locale.ROOT, "[out:json][timeout:45];way[\"highway\"][\"area\"!=\"yes\"](%.7f,%.7f,%.7f,%.7f);out body geom;", south, west, north, east);
        }
    }
}
