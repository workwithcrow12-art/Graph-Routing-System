package graphroute.io;

import graphroute.model.Graph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Map;

/** Loads bundled OSM data, external OSM extracts, or the legacy node/edge JSON format. */
public class GraphFileManager {
    public static final int MAX_METADATA_BYTES = 64 * 1024;
    private final GraphSerializer serializer;

    public GraphFileManager() {
        this(new GraphSerializer());
    }

    public GraphFileManager(GraphSerializer serializer) {
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    public Graph loadGraph(String nodeResource, String edgeResource) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = GraphFileManager.class.getClassLoader();
        }
        // Opening inside the resource declaration closes the first stream even if
        // locating/opening the second one fails.
        try (InputStream nodes = openResource(loader, nodeResource);
             InputStream edges = openResource(loader, edgeResource)) {
            return serializer.load(nodes, edges);
        } catch (IOException exception) {
            throw new GraphSerializer.GraphDataException("Cannot load graph resources '"
                    + nodeResource + "' and '" + edgeResource + "': " + exception.getMessage(), exception);
        }
    }

    public Graph loadGraph(Path nodeFile, Path edgeFile) {
        Objects.requireNonNull(nodeFile, "nodeFile");
        Objects.requireNonNull(edgeFile, "edgeFile");
        try (InputStream nodes = Files.newInputStream(nodeFile);
             InputStream edges = Files.newInputStream(edgeFile)) {
            return serializer.load(nodes, edges);
        } catch (IOException exception) {
            throw new GraphSerializer.GraphDataException("Cannot load graph files '"
                    + nodeFile + "' and '" + edgeFile + "': " + exception.getMessage(), exception);
        }
    }

    public Graph loadThanhXuanGraph() {
        Graph graph = loadGraph("data/thanh_xuan_nodes.json", "data/thanh_xuan_edges.json");
        graph.setProvenance(Map.of("source", "Estimated educational Thanh Xuan sample",
                "distance", "At least geodesic endpoint distance; synthetic segments, not surveyed road lengths",
                "routingLimitations", "Eight manually selected junctions; no real road geometry, access restrictions or live traffic"));
        return graph;
    }

    /** A missing production dataset is an installation error; samples are selected explicitly. */
    public Graph loadDefaultGraph() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = GraphFileManager.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream("data/thanh_xuan_osm.json")) {
            if (input == null) throw new GraphSerializer.MissingGraphResourceException("Missing bundled OSM extract: data/thanh_xuan_osm.json; restore the dataset or explicitly select a sample graph");
            Graph graph = new OsmImporter().load(input);
            try (InputStream metadata = loader.getResourceAsStream("data/thanh_xuan_osm.json.metadata.json")) {
                if (metadata != null) addMetadata(graph, metadata);
            }
            return graph;
        } catch (IOException exception) {
            throw new GraphSerializer.GraphDataException("Cannot load bundled OSM extract: " + exception.getMessage(), exception);
        }
    }

    public Graph loadOsm(Path file) {
        Objects.requireNonNull(file, "file");
        try (InputStream input = Files.newInputStream(file)) {
            Graph graph = new OsmImporter().load(input);
            Path metadata = file.resolveSibling(file.getFileName() + ".metadata.json");
            if (Files.exists(metadata)) {
                try (InputStream metadataInput = Files.newInputStream(metadata)) { addMetadata(graph, metadataInput); }
            }
            var provenance = new java.util.LinkedHashMap<>(graph.getProvenance());
            provenance.put("file", file.getFileName().toString());
            graph.setProvenance(provenance);
            return graph;
        } catch (IOException exception) {
            throw new GraphSerializer.GraphDataException("Cannot load OSM file '" + file + "': " + exception.getMessage(), exception);
        }
    }

    private static void addMetadata(Graph graph, InputStream input) throws IOException {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var data = mapper.readTree(BoundedInput.read(input, MAX_METADATA_BYTES, "OSM metadata"));
        if (data == null || !data.isObject()) throw new GraphSerializer.GraphDataException("OSM metadata must be a JSON object");
        var provenance = new java.util.LinkedHashMap<>(graph.getProvenance());
        for (String key : java.util.List.of("name", "endpoint", "query", "queryBbox", "bbox", "downloadedDate", "downloadedAt")) {
            if (data.has(key) && data.get(key).isTextual()) {
                String value = data.get(key).asText();
                if (value.length() > GraphSerializer.MAX_TEXT_CHARS) {
                    throw new GraphSerializer.GraphDataException("OSM metadata " + key + " is too long");
                }
                provenance.put(key.equals("bbox") ? "queryBbox" : key, value);
            }
        }
        graph.setProvenance(provenance);
    }

    private static InputStream openResource(ClassLoader loader, String resource) {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("Graph resource name must not be blank");
        }
        String normalized = resource.startsWith("/") ? resource.substring(1) : resource;
        InputStream input = loader.getResourceAsStream(normalized);
        if (input == null) {
            throw new GraphSerializer.MissingGraphResourceException("Missing graph resource: " + resource);
        }
        return input;
    }
}
