package graphroute;

import graphroute.algorithm.shortestpath.PreparedShortestPath;
import graphroute.benchmark.RoutingBenchmark;
import graphroute.model.Edge;
import graphroute.model.Graph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LargeGraphRoutingTest {
    @Test
    void preparedAstarMatchesDijkstraOnTenThousandNodesWithLessFrontierWork() {
        Graph graph = RoutingBenchmark.grid(100);
        assertEquals(10_000, graph.nodeCount());
        assertEquals(39_600, graph.getAllEdges().size());
        PreparedShortestPath astar = new PreparedShortestPath(graph, true);
        PreparedShortestPath dijkstra = new PreparedShortestPath(graph, false);
        for (List<String> pair : List.of(List.of("50:0", "50:99"), List.of("0:40", "99:40"),
                List.of("12:17", "80:91"), List.of("99:99", "0:0"))) {
            var actual = astar.search(pair.getFirst(), pair.getLast(), 0);
            var expected = dijkstra.search(pair.getFirst(), pair.getLast(), 0);
            assertTrue(actual.result().isSuccess());
            assertEquals(expected.result().getTotalCost(), actual.result().getTotalCost(), 1e-9);
            assertEquals(actual.result().getTotalCost(), actual.result().getEdges().stream().mapToDouble(Edge::getWeight).sum(), 1e-9);
            assertTrue(actual.result().getSteps().isEmpty());
            if (pair.equals(List.of("50:0", "50:99"))) {
                // Work counts are reproducible; no machine-dependent elapsed-time assertion.
                assertTrue(actual.examinedEdges() * 10 < expected.examinedEdges(),
                        () -> "A* examined " + actual.examinedEdges() + " arcs versus " + expected.examinedEdges());
                assertEquals(100, actual.result().getPath().size());
            }
        }
        var restricted = astar.search("50:0", "50:99", java.util.Set.of(), java.util.Set.of(),
                edge -> !edge.getDestination().getId().equals("50:50"), 0);
        var restrictedOracle = dijkstra.search("50:0", "50:99", java.util.Set.of(), java.util.Set.of(),
                edge -> !edge.getDestination().getId().equals("50:50"), 0);
        assertEquals(restrictedOracle.result().getTotalCost(), restricted.result().getTotalCost(), 1e-9);
        assertFalse(restricted.result().getPath().stream().anyMatch(node -> node.getId().equals("50:50")));
    }
}
