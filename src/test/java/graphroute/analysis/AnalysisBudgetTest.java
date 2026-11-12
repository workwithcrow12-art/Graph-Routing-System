package graphroute.analysis;

import graphroute.algorithm.shortestpath.SearchBudget;
import graphroute.io.SampleGraphs;
import graphroute.routing.RoutingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisBudgetTest {
    @ParameterizedTest
    @ValueSource(strings = {"bfs", "dfs"})
    void budgetedTraversalRejectsAGoalWithoutAStart(String algorithm) {
        var graph = SampleGraphs.thanhXuan();
        String goal = graph.getAllNodes().iterator().next().getId();
        var budget = SearchBudget.analysis();
        var result = algorithm.equals("bfs")
                ? new graphroute.algorithm.traversal.BFS().execute(graph, null, goal, budget)
                : new graphroute.algorithm.traversal.DFS().execute(graph, null, goal, budget);
        assertEquals(graphroute.algorithm.AlgorithmResult.Status.INVALID, result.getStatus());
    }

    @Test void defaultBudgetStillSupportsTheAdvertisedFloydWarshallGraphSize() {
        var graph = new graphroute.model.Graph();
        for (int i = 0; i < 300; i++)
            graph.addNode(new graphroute.model.Node(String.format("%03d", i), "Junction", 0, 0));
        for (int i = 0; i < 299; i++)
            graph.addEdge(new graphroute.model.Edge(graph.getNode(String.format("%03d", i)),
                    graph.getNode(String.format("%03d", i + 1)), 1));
        var result = new AlgorithmService(graph).execute(new AlgorithmRequest("floyd-warshall", "000", "299", 0));
        assertEquals(graphroute.algorithm.AlgorithmResult.Status.SUCCESS, result.status());
        assertEquals(299, result.totalCost());
        assertEquals(300, result.nodeIds().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dijkstra", "astar", "bfs", "dfs", "bellman-ford", "floyd-warshall", "prim", "kruskal"})
    void everyAlgorithmHonorsTheSuppliedWorkBudget(String algorithm) {
        var graph = SampleGraphs.thanhXuan();
        var ids = graph.getAllNodes().stream().map(n -> n.getId()).toList();
        boolean tree = algorithm.equals("prim") || algorithm.equals("kruskal");
        var request = new AlgorithmRequest(algorithm, tree ? null : ids.getFirst(), tree ? null : ids.getLast(), 0);
        var service = new AlgorithmService(graph);
        var failure = assertThrows(RoutingException.class, () -> service.execute(request,
                new SearchBudget(1, 0, 1024, Duration.ofSeconds(10))));
        assertEquals("ANALYSIS_LIMIT_EXCEEDED", failure.getCode());
        assertFalse(service.execute(request).nodeIds().isEmpty(), "A failed request must not poison a later execution");
    }

    @ParameterizedTest
    @ValueSource(strings = {"dijkstra", "astar", "bfs", "dfs", "bellman-ford", "floyd-warshall", "prim", "kruskal"})
    void cancellationIsHonoredEvenAfterAnAlgorithmResultIsCached(String algorithm) {
        var graph = SampleGraphs.thanhXuan();
        var ids = graph.getAllNodes().stream().map(n -> n.getId()).toList();
        boolean tree = algorithm.equals("prim") || algorithm.equals("kruskal");
        var request = new AlgorithmRequest(algorithm, tree ? null : ids.getFirst(), tree ? null : ids.getLast(), 0);
        var service = new AlgorithmService(graph);
        service.execute(request);
        try {
            Thread.currentThread().interrupt();
            assertThrows(java.util.concurrent.CancellationException.class, () -> service.execute(request));
        } finally { Thread.interrupted(); }
    }

    @Test void explicitAnalysisQuotaHasItsOwnErrorAndCancellationIsDistinct() {
        var graph = SampleGraphs.thanhXuan();
        var ids = graph.getAllNodes().stream().map(n -> n.getId()).toList();
        var service = new AlgorithmService(graph);
        var request = new AlgorithmRequest("dijkstra", ids.getFirst(), ids.getLast(), 0);
        var failure = assertThrows(RoutingException.class, () -> service.execute(request,
                new SearchBudget(1, 0, 1024, Duration.ofSeconds(1))));
        assertEquals("ANALYSIS_LIMIT_EXCEEDED", failure.getCode());
        assertFalse(service.execute(request).nodeIds().isEmpty());
        try {
            Thread.currentThread().interrupt();
            assertThrows(java.util.concurrent.CancellationException.class, () -> service.execute(request));
        } finally { Thread.interrupted(); }
    }
}
