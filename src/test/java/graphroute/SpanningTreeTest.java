package graphroute;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.SpanningTreeAlgorithm;
import graphroute.algorithm.mst.Kruskal;
import graphroute.algorithm.mst.Prim;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SpanningTreeTest {
    static Stream<SpanningTreeAlgorithm> algorithms() {
        return Stream.of(new Prim(), new Kruskal());
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void choosesCheapParallelRoadsAndNeverSelectsLoops(SpanningTreeAlgorithm algorithm) {
        Graph graph = graph(GraphType.UNDIRECTED, "D", "C", "B", "A");
        edge(graph, "A", "B", 9);
        edge(graph, "A", "B", 1);
        edge(graph, "B", "C", 2);
        edge(graph, "C", "D", 3);
        edge(graph, "A", "D", 10);
        edge(graph, "B", "B", 0);
        int originalEdges = graph.edgeCount();

        AlgorithmResult result = algorithm.execute(graph);

        assertEquals(AlgorithmResult.Status.SUCCESS, result.getStatus());
        assertEquals(6, result.getCost());
        assertEquals(3, result.getEdges().size());
        assertEquals(List.of("A", "B", "C", "D"), result.getPath().stream().map(Node::getId).toList());
        assertTrue(result.getEdges().stream().noneMatch(e -> e.getSource().equals(e.getDestination())));
        assertEquals(originalEdges, graph.edgeCount());
        assertEquals(9, graph.getNeighbors("A").get(0).getWeight());
        assertEquals(result.getEdges(), algorithm.execute(graph).getEdges());
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void returnsWholeMinimumForestIncludingIsolatedVertices(SpanningTreeAlgorithm algorithm) {
        Graph graph = graph(GraphType.UNDIRECTED, "A", "B", "C", "D", "E");
        edge(graph, "A", "B", 2);
        edge(graph, "C", "D", 3);

        AlgorithmResult result = algorithm.execute(graph);

        assertEquals(AlgorithmResult.Status.PARTIAL, result.getStatus());
        assertEquals(5, result.getCost());
        assertEquals(5, result.getPath().size());
        assertEquals(2, result.getEdges().size());
        assertFalse(result.isSuccess());
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void rejectsDirectedAndEmptyGraphsButSupportsSingleVertex(SpanningTreeAlgorithm algorithm) {
        Graph directed = graph(GraphType.DIRECTED, "A", "B");
        edge(directed, "A", "B", 1);
        assertEquals(AlgorithmResult.Status.INVALID, algorithm.execute(directed).getStatus());
        assertEquals(AlgorithmResult.Status.INVALID, algorithm.execute(new Graph()).getStatus());
        assertEquals(AlgorithmResult.Status.INVALID, algorithm.execute(null).getStatus());

        AlgorithmResult singleton = algorithm.execute(graph(GraphType.UNDIRECTED, "A"));
        assertEquals(AlgorithmResult.Status.SUCCESS, singleton.getStatus());
        assertEquals(0, singleton.getCost());
        assertTrue(singleton.getEdges().isEmpty());
    }

    private static Graph graph(GraphType type, String... ids) {
        Graph graph = new Graph(type);
        for (String id : ids) graph.addNode(new Node(id, id, 0, 0));
        return graph;
    }

    private static void edge(Graph graph, String from, String to, double weight) {
        graph.addEdge(new Edge(graph.getNode(from), graph.getNode(to), weight));
    }
}
