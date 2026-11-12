package graphroute.io;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;

import java.io.IOException;
import java.io.InputStream;

/** Loads validated road networks from the project's node and edge JSON arrays. */
public class GraphSerializer {
    public static final int MAX_INPUT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_RECORDS = 100_000;
    public static final int MAX_TEXT_CHARS = 4_096;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Reads a graph without taking ownership of the input streams.
     *
     * <p>Legacy {@code graphType} values declare one direction policy for the whole
     * graph and must agree. For mixed roads, omit {@code graphType} and set
     * {@code directed} on individual edges (false by default). A mixed network is
     * represented as a directed graph with explicit reverse arcs for two-way roads.
     * Fully undirected networks preserve the original graph and edge-count semantics.</p>
     */
    public Graph load(InputStream nodesInput, InputStream edgesInput) throws IOException {
        if (nodesInput == null || edgesInput == null) {
            throw new IllegalArgumentException("Node and edge input streams are required");
        }
        JsonNode nodesData = readArray(nodesInput, "nodes");
        JsonNode edgesData = readArray(edgesInput, "edges");
        DirectionPlan directions = determineDirections(edgesData);
        Graph graph = new Graph(directions.type());

        for (int index = 0; index < nodesData.size(); index++) {
            String context = "node[" + index + "]";
            JsonNode data = requireObject(nodesData.get(index), context);
            String id = requiredText(data, "id", context, false);
            if (graph.hasNode(id)) {
                throw new GraphDataException(context + ": duplicate node id '" + id + "'");
            }
            String name = requiredText(data, "name", context, true);
            double latitude = requiredNumber(data, "latitude", context);
            double longitude = requiredNumber(data, "longitude", context);
            if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                throw new GraphDataException(context + ": GPS coordinates are outside valid latitude/longitude ranges");
            }
            graph.addNode(new Node(id, name, latitude, longitude,
                    optionalNumber(data, "x", context), optionalNumber(data, "y", context)));
        }

        for (int index = 0; index < edgesData.size(); index++) {
            String context = "edge[" + index + "]";
            JsonNode data = edgesData.get(index);
            String sourceId = requiredText(data, "source", context, false);
            String targetId = requiredText(data, "target", context, false);
            Node source = graph.getNode(sourceId);
            Node target = graph.getNode(targetId);
            if (source == null || target == null) {
                throw new GraphDataException(context + ": unknown node '"
                        + (source == null ? sourceId : targetId) + "'");
            }
            double weight = requiredNumber(data, "weight", context);
            if (weight < 0) {
                throw new GraphDataException(context + ".weight must be nonnegative");
            }
            String road = data.has("road") ? requiredText(data, "road", context, true) : "";
            Edge edge = new Edge(source, target, weight, road);
            graph.addEdge(edge);
            if (directions.type() == GraphType.DIRECTED && !directions.oneWay()[index]
                    && !sourceId.equals(targetId)) {
                graph.addEdge(edge.reverse());
            }
        }
        return graph;
    }

    private JsonNode readArray(InputStream input, String name) throws IOException {
        JsonNode data = objectMapper.readTree(BoundedInput.read(input, MAX_INPUT_BYTES, name));
        if (data == null || !data.isArray()) {
            throw new GraphDataException(name + " must be a JSON array");
        }
        if (data.size() > MAX_RECORDS) throw new GraphDataException(name + " exceeds " + MAX_RECORDS + " records");
        return data;
    }

    private DirectionPlan determineDirections(JsonNode edgesData) {
        GraphType declaredType = null;
        for (int index = 0; index < edgesData.size(); index++) {
            String context = "edge[" + index + "]";
            JsonNode data = requireObject(edgesData.get(index), context);
            if (data.has("graphType")) {
                String value = requiredText(data, "graphType", context, false);
                GraphType type;
                try {
                    type = GraphType.valueOf(value);
                } catch (IllegalArgumentException exception) {
                    throw new GraphDataException(context + ".graphType must be DIRECTED or UNDIRECTED", exception);
                }
                if (declaredType != null && declaredType != type) {
                    throw new GraphDataException(context + ": conflicting graphType declarations");
                }
                declaredType = type;
            }
            if (data.has("directed") && !data.get("directed").isBoolean()) {
                throw new GraphDataException(context + ".directed must be a boolean");
            }
        }

        boolean[] oneWay = new boolean[edgesData.size()];
        boolean anyDirected = false;
        for (int index = 0; index < edgesData.size(); index++) {
            JsonNode data = edgesData.get(index);
            oneWay[index] = data.has("directed")
                    ? data.get("directed").booleanValue() : declaredType == GraphType.DIRECTED;
            if (declaredType != null && oneWay[index] != (declaredType == GraphType.DIRECTED)) {
                throw new GraphDataException("edge[" + index + "].directed conflicts with graphType " + declaredType);
            }
            anyDirected |= oneWay[index];
        }
        return new DirectionPlan(anyDirected ? GraphType.DIRECTED : GraphType.UNDIRECTED, oneWay);
    }

    private static JsonNode requireObject(JsonNode data, String context) {
        if (!data.isObject()) {
            throw new GraphDataException(context + " must be a JSON object");
        }
        return data;
    }

    private static String requiredText(JsonNode data, String field, String context, boolean allowBlank) {
        JsonNode value = data.get(field);
        if (value == null || !value.isTextual() || (!allowBlank && value.textValue().isBlank())) {
            throw new GraphDataException(context + "." + field + " must be "
                    + (allowBlank ? "a string" : "a nonblank string"));
        }
        if (value.textValue().length() > MAX_TEXT_CHARS) {
            throw new GraphDataException(context + "." + field + " exceeds " + MAX_TEXT_CHARS + " characters");
        }
        return value.textValue();
    }

    private static double requiredNumber(JsonNode data, String field, String context) {
        JsonNode value = data.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw new GraphDataException(context + "." + field + " must be a finite number");
        }
        return value.doubleValue();
    }

    private static double optionalNumber(JsonNode data, String field, String context) {
        return data.has(field) ? requiredNumber(data, field, context) : 0.0;
    }

    private record DirectionPlan(GraphType type, boolean[] oneWay) { }

    public static class MissingGraphResourceException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public MissingGraphResourceException(String message) {
            super(message);
        }
    }

    public static class GraphDataException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public GraphDataException(String message) {
            super(message);
        }

        public GraphDataException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
