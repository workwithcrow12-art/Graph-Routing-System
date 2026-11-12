package graphroute;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.traversal.BFS;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BFSTest {
    @Test
    void findsMinimumHopRouteAndReportsItsActualWeight() {
        Graph graph = graph(GraphType.DIRECTED, "A", "B", "C", "D");
        edge(graph, "A", "B", 1);
        edge(graph, "B", "C", 1);
        edge(graph, "C", "D", 1);
        edge(graph, "A", "D", 20);

        AlgorithmResult result = new BFS().execute(graph, "A", "D");

        assertEquals(AlgorithmResult.Status.SUCCESS, result.getStatus());
        assertEquals(List.of("A", "D"), ids(result));
        assertEquals(20, result.getCost());
        assertEquals(1, result.getEdges().size());
        assertEquals(1, result.getSteps().get(result.getSteps().size() - 1).getCost());
    }

    @Test
    void traversalOrderIsDeterministicAndIncludesDisconnectedComponents() {
        Graph graph = graph(GraphType.UNDIRECTED, "Z", "D", "C", "B", "A");
        edge(graph, "A", "C", 1);
        edge(graph, "A", "B", 1);
        edge(graph, "B", "D", 1);
        BFS bfs = new BFS();

        assertEquals(List.of("A", "B", "C", "D", "Z"), ids(bfs.execute(graph)));
        assertEquals(List.of("A", "B", "C", "D"), ids(bfs.execute(graph, "A")));
        assertEquals(4, bfs.execute(graph, "A").getSteps().size());
    }

    @Test
    void reportsInvalidAndUnreachableInputsAndSupportsTrivialRoute() {
        Graph graph = graph(GraphType.DIRECTED, "A", "B");
        edge(graph, "A", "B", 1);
        BFS bfs = new BFS();

        assertEquals(AlgorithmResult.Status.INVALID, bfs.execute(null).getStatus());
        assertEquals(AlgorithmResult.Status.INVALID, bfs.execute(graph, "missing", "B").getStatus());
        assertEquals(AlgorithmResult.Status.INVALID, bfs.execute(graph, "A", null).getStatus());
        AlgorithmResult unreachable = bfs.execute(graph, "B", "A");
        assertEquals(AlgorithmResult.Status.UNREACHABLE, unreachable.getStatus());
        assertTrue(unreachable.getPath().isEmpty());
        assertEquals(Double.POSITIVE_INFINITY, unreachable.getCost());
        AlgorithmResult same = bfs.execute(graph, "A", "A");
        assertEquals(AlgorithmResult.Status.START_EQUALS_GOAL, same.getStatus());
        assertEquals(List.of("A"), ids(same));
        assertEquals(0, same.getCost());
    }

    @Test
    void searchResultsAreImmutableAndDetachedFromLaterGraphEdits() {
        Graph graph = graph(GraphType.UNDIRECTED, "A", "B");
        edge(graph, "A", "B", 1);

        AlgorithmResult result = new BFS().execute(graph, "A", "B");

        graph.removeNode("B");
        assertEquals(List.of("A", "B"), ids(result));
        assertEquals(1, result.getCost());
        assertThrows(UnsupportedOperationException.class, () -> result.getPath().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.getEdges().clear());
    }

    private static Graph graph(GraphType type, String... ids) {
        Graph graph = new Graph(type);
        for (String id : ids) graph.addNode(new Node(id, id, 0, 0));
        return graph;
    }

    private static void edge(Graph graph, String source, String destination, double weight) {
        graph.addEdge(new Edge(graph.getNode(source), graph.getNode(destination), weight));
    }

    private static List<String> ids(AlgorithmResult result) {
        return result.getPath().stream().map(Node::getId).toList();
    }
}
