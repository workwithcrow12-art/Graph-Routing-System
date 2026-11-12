package graphroute;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.traversal.DFS;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DFSTest {
    @Test
    void exploresDeepBranchesBeforeSiblingsAndHandlesCycles() {
        Graph graph = new Graph(GraphType.DIRECTED);
        for (String id : List.of("A", "B", "C", "D", "Z")) graph.addNode(new Node(id, id, 0, 0));
        connect(graph, "A", "C");
        connect(graph, "A", "B");
        connect(graph, "B", "D");
        connect(graph, "D", "C");
        connect(graph, "C", "A");

        AlgorithmResult traversal = new DFS().execute(graph);
        AlgorithmResult route = new DFS().execute(graph, "A", "C");

        assertEquals(List.of("A", "B", "D", "C", "Z"), ids(traversal));
        assertEquals(List.of("A", "B", "D", "C"), ids(route));
        assertEquals(3, route.getCost());
        assertEquals(3, route.getEdges().size());
        assertEquals(AlgorithmResult.Status.UNREACHABLE, new DFS().execute(graph, "Z", "A").getStatus());
    }

    @Test
    void longRoutesDoNotDependOnJavaCallStackDepth() {
        Graph graph = new Graph(GraphType.DIRECTED);
        for (int i = 0; i < 12000; i++) graph.addNode(new Node(Integer.toString(i), "", 0, 0));
        for (int i = 1; i < 12000; i++) connect(graph, Integer.toString(i - 1), Integer.toString(i));

        AlgorithmResult result = new DFS().execute(graph, "0", "11999");

        assertEquals(AlgorithmResult.Status.SUCCESS, result.getStatus());
        assertEquals(12000, result.getPath().size());
        assertEquals(11999, result.getCost());
    }

    private static void connect(Graph graph, String from, String to) {
        graph.addEdge(new Edge(graph.getNode(from), graph.getNode(to), 1));
    }

    private static List<String> ids(AlgorithmResult result) {
        return result.getPath().stream().map(Node::getId).toList();
    }
}
