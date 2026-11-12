package graphroute;

import graphroute.io.GraphFileManager;
import graphroute.io.GraphSerializer;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GraphFileManagerTest {
    private static final String NODES = """
            [
              {"id":"a","name":"Origin","latitude":21.0,"longitude":105.8},
              {"id":"b","name":"Junction","latitude":21.1,"longitude":105.8},
              {"id":"c","name":"Destination","latitude":21.2,"longitude":105.8}
            ]
            """;
    private static final String EDGES = """
            [{"source":"a","target":"b","weight":1.5,"road":"Main Street"}]
            """;

    @TempDir Path directory;

    @Test
    void loadsBundledNetworkWithOriginalUndirectedSemantics() {
        Graph graph = new GraphFileManager().loadThanhXuanGraph();
        assertEquals(GraphType.UNDIRECTED, graph.getType());
        assertEquals(8, graph.nodeCount());
        assertEquals(10, graph.edgeCount());
        assertEquals(20, graph.getAllEdges().size());
        graph.getAllEdges().forEach(edge -> assertTrue(graph.hasEdge(
                edge.getDestination().getId(), edge.getSource().getId())));
    }

    @Test
    void loadsExternalFilesAndPreservesOptionalDefaults() throws IOException {
        Path nodes = Files.writeString(directory.resolve("nodes.json"), NODES);
        Path edges = Files.writeString(directory.resolve("edges.json"), EDGES);
        Graph graph = new GraphFileManager().loadGraph(nodes, edges);
        assertEquals(3, graph.nodeCount());
        assertEquals(0.0, graph.getNode("a").getX());
        assertEquals(0.0, graph.getNode("a").getY());
        assertEquals(1.5, graph.getNeighbors("a").getFirst().getWeight());
        assertEquals("Main Street", graph.getNeighbors("a").getFirst().getRoadName());
        assertTrue(graph.hasEdge("b", "a"));
    }

    @Test
    void supportsMixedRoadDirectionsWithoutOpeningOneWayRoadsInReverse() throws IOException {
        Graph graph = load(NODES, """
                [
                  {"source":"a","target":"b","weight":1,"directed":true},
                  {"source":"b","target":"c","weight":2,"directed":false}
                ]
                """);
        assertEquals(GraphType.DIRECTED, graph.getType());
        assertEquals(3, graph.edgeCount());
        assertTrue(graph.hasEdge("a", "b"));
        assertFalse(graph.hasEdge("b", "a"));
        assertTrue(graph.hasEdge("b", "c"));
        assertTrue(graph.hasEdge("c", "b"));
        assertEquals(2.0, graph.getNeighbors("c").getFirst().getWeight());
    }

    @Test
    void detectsOneWayRoadsBeyondTheFirstRecord() throws IOException {
        Graph graph = load(NODES, """
                [
                  {"source":"a","target":"b","weight":1},
                  {"source":"b","target":"c","weight":2,"directed":true}
                ]
                """);
        assertEquals(GraphType.DIRECTED, graph.getType());
        assertTrue(graph.hasEdge("b", "a"));
        assertFalse(graph.hasEdge("c", "b"));
    }

    @Test
    void legacyDeclarationAppliesToEveryRecord() throws IOException {
        Graph graph = load(NODES, """
                [
                  {"source":"a","target":"b","weight":1,"graphType":"DIRECTED"},
                  {"source":"b","target":"c","weight":2}
                ]
                """);
        assertEquals(GraphType.DIRECTED, graph.getType());
        assertEquals(2, graph.edgeCount());
        assertFalse(graph.hasEdge("b", "a"));
        assertFalse(graph.hasEdge("c", "b"));
    }

    @Test
    void supportsEmptyNetworkAndZeroWeightRoads() throws IOException {
        assertTrue(load("[]", "[]").isEmpty());
        assertEquals(0.0, load(NODES, EDGES.replace("1.5", "0"))
                .getNeighbors("a").getFirst().getWeight());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "42", "\"text\"", "", "[null]", "[[]]"})
    void rejectsInvalidNodeArrayShape(String data) {
        assertThrows(GraphSerializer.GraphDataException.class, () -> load(data, "[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "42", "\"text\"", "", "[null]", "[[]]"})
    void rejectsInvalidEdgeArrayShape(String data) {
        assertThrows(GraphSerializer.GraphDataException.class, () -> load(NODES, data));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"id\":\"a\",\"latitude\":21,\"longitude\":105}",
            "{\"id\":\" \",\"name\":\"Name\",\"latitude\":21,\"longitude\":105}",
            "{\"id\":1,\"name\":\"Name\",\"latitude\":21,\"longitude\":105}",
            "{\"id\":\"a\",\"name\":null,\"latitude\":21,\"longitude\":105}",
            "{\"id\":\"a\",\"name\":\"Name\",\"longitude\":105}",
            "{\"id\":\"a\",\"name\":\"Name\",\"latitude\":\"21\",\"longitude\":105}",
            "{\"id\":\"a\",\"name\":\"Name\",\"latitude\":91,\"longitude\":105}",
            "{\"id\":\"a\",\"name\":\"Name\",\"latitude\":21,\"longitude\":-181}",
            "{\"id\":\"a\",\"name\":\"Name\",\"latitude\":1e1000,\"longitude\":105}",
            "{\"id\":\"a\",\"name\":\"Name\",\"latitude\":21,\"longitude\":105,\"x\":null}",
            "{\"id\":\"a\",\"name\":\"Name\",\"latitude\":21,\"longitude\":105,\"y\":1e1000}"
    })
    void rejectsInvalidNodeFields(String node) {
        assertThrows(GraphSerializer.GraphDataException.class, () -> load("[" + node + "]", "[]"));
    }

    @Test
    void rejectsDuplicateNodeIdsInsteadOfSilentlyDroppingThem() {
        var exception = assertThrows(GraphSerializer.GraphDataException.class,
                () -> load(NODES.replace("\"b\"", "\"a\""), "[]"));
        assertTrue(exception.getMessage().contains("duplicate node id 'a'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"source\":\"unknown\",\"target\":\"b\",\"weight\":1}",
            "{\"source\":\"a\",\"target\":\"unknown\",\"weight\":1}",
            "{\"source\":\"a\",\"target\":\"b\"}",
            "{\"source\":\"a\",\"target\":\"b\",\"weight\":\"1\"}",
            "{\"source\":\"a\",\"target\":\"b\",\"weight\":-1}",
            "{\"source\":\"a\",\"target\":\"b\",\"weight\":1e1000}",
            "{\"source\":\"a\",\"target\":\"b\",\"weight\":1,\"road\":42}",
            "{\"source\":\"a\",\"target\":\"b\",\"weight\":1,\"directed\":\"false\"}",
            "{\"source\":\"a\",\"target\":\"b\",\"weight\":1,\"directed\":null}",
            "{\"source\":\"a\",\"target\":\"b\",\"weight\":1,\"graphType\":\"OTHER\"}",
            "{\"source\":\"a\",\"target\":\"b\",\"weight\":1,\"graphType\":\"DIRECTED\",\"directed\":false}",
            "{\"source\":\"a\",\"target\":\"b\",\"weight\":1,\"graphType\":\"UNDIRECTED\",\"directed\":true}"
    })
    void rejectsInvalidEdges(String edge) {
        assertThrows(GraphSerializer.GraphDataException.class, () -> load(NODES, "[" + edge + "]"));
    }

    @Test
    void rejectsConflictingDeclarationsAcrossRecords() {
        assertThrows(GraphSerializer.GraphDataException.class, () -> load(NODES, """
                [
                  {"source":"a","target":"b","weight":1,"graphType":"DIRECTED"},
                  {"source":"b","target":"c","weight":2,"graphType":"UNDIRECTED"}
                ]
                """));
    }

    @Test
    void rejectsTrailingDocumentsAndDuplicateJsonFields() {
        assertThrows(IOException.class, () -> load(NODES + " []", EDGES));
        assertThrows(IOException.class, () -> load(NODES,
                "[{\"source\":\"a\",\"target\":\"b\",\"weight\":1,\"weight\":2}]"));
    }

    @Test
    void fileFailuresRetainTheCauseAndPath() {
        Path missing = directory.resolve("missing.json");
        var exception = assertThrows(GraphSerializer.GraphDataException.class,
                () -> new GraphFileManager().loadGraph(missing, missing));
        assertInstanceOf(IOException.class, exception.getCause());
        assertTrue(exception.getMessage().contains("missing.json"));
    }

    @Test
    void malformedFilesRetainParsingCause() throws IOException {
        Path nodes = Files.writeString(directory.resolve("nodes.json"), "[broken");
        Path edges = Files.writeString(directory.resolve("edges.json"), EDGES);
        var exception = assertThrows(GraphSerializer.GraphDataException.class,
                () -> new GraphFileManager().loadGraph(nodes, edges));
        assertInstanceOf(IOException.class, exception.getCause());
    }

    @Test
    void serializerLeavesOwnershipOfStreamsWithItsCaller() throws IOException {
        TrackedInput nodes = new TrackedInput(NODES);
        TrackedInput edges = new TrackedInput(EDGES);
        new GraphSerializer().load(nodes, edges);
        assertFalse(nodes.closed);
        assertFalse(edges.closed);
    }

    @Test
    void bundledLoaderClosesBothStreamsAfterSuccess() {
        TrackedInput nodes = new TrackedInput(NODES);
        TrackedInput edges = new TrackedInput(EDGES);
        withResources(nodes, edges, () -> new GraphFileManager().loadGraph("/nodes", "/edges"));
        assertTrue(nodes.closed);
        assertTrue(edges.closed);
    }

    @Test
    void bundledLoaderClosesBothStreamsAfterValidationFailure() {
        TrackedInput nodes = new TrackedInput("[{}]");
        TrackedInput edges = new TrackedInput(EDGES);
        withResources(nodes, edges, () -> assertThrows(GraphSerializer.GraphDataException.class,
                () -> new GraphFileManager().loadGraph("nodes", "edges")));
        assertTrue(nodes.closed);
        assertTrue(edges.closed);
    }

    @Test
    void bundledLoaderClosesFirstStreamWhenSecondResourceIsMissing() {
        TrackedInput nodes = new TrackedInput(NODES);
        withResources(nodes, null, () -> {
            var exception = assertThrows(GraphSerializer.MissingGraphResourceException.class,
                    () -> new GraphFileManager().loadGraph("nodes", "edges"));
            assertTrue(exception.getMessage().contains("edges"));
        });
        assertTrue(nodes.closed);
    }

    @Test
    void bundledLoaderWorksWithoutAContextClassLoader() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(null);
            assertEquals(8, new GraphFileManager().loadThanhXuanGraph().nodeCount());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static Graph load(String nodes, String edges) throws IOException {
        return new GraphSerializer().load(new TrackedInput(nodes), new TrackedInput(edges));
    }

    private static void withResources(InputStream nodes, InputStream edges, Runnable action) {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader resources = new ClassLoader(original) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return switch (name) {
                    case "nodes" -> nodes;
                    case "edges" -> edges;
                    default -> null;
                };
            }
        };
        try {
            Thread.currentThread().setContextClassLoader(resources);
            action.run();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static final class TrackedInput extends ByteArrayInputStream {
        boolean closed;

        TrackedInput(String json) {
            super(json.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
