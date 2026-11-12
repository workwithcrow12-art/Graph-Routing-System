package graphroute.algorithm.shortestpath;

import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SearchBudgetTest {
    @Test void parallelChainStopsAtPathAllocationBudgetAndLaterRequestStillWorks() {
        Graph graph = new Graph(GraphType.DIRECTED);
        for (int i = 0; i <= 32; i++) graph.addNode(new Node("" + i, "" + i, 0, 0));
        for (int i = 0; i < 32; i++) {
            for (int arc = 0; arc < 2; arc++) {
                graph.addEdge(new Edge(graph.getNode("" + i), graph.getNode("" + (i + 1)), 1, "parallel"));
            }
        }
        var yen = new YenKShortestPaths(new PreparedShortestPath(graph, false));
        var limited = new SearchBudget(100_000, 100, 10_000, Duration.ofSeconds(10));
        var failure = assertThrows(SearchBudget.LimitExceededException.class,
                () -> yen.execute("0", "32", 2, e -> true, limited));
        assertTrue(failure.getMessage().contains("path allocation"));
        var fresh = new SearchBudget(100_000, 100, 1_000_000, Duration.ofSeconds(10));
        var routes = yen.execute("0", "32", 2, e -> true, fresh);
        assertEquals(2, routes.size());
        assertEquals(32, routes.get(0).getTotalCost());
        assertEquals(32, routes.get(1).getTotalCost());
    }

    @Test void zeroSpurBudgetAllowsPrimaryButRejectsAlternativesWithoutPartialSuccess() {
        var graph = new Graph(GraphType.DIRECTED);
        graph.addNode(new Node("a", "a", 0, 0));
        graph.addNode(new Node("b", "b", 0, 0));
        graph.addEdge(new Edge(graph.getNode("a"), graph.getNode("b"), 1));
        var yen = new YenKShortestPaths(new PreparedShortestPath(graph, false));
        assertEquals(1, yen.execute("a", "b", 1, e -> true,
                new SearchBudget(100, 0, 4096, Duration.ofSeconds(10))).size());
        assertThrows(SearchBudget.LimitExceededException.class, () -> yen.execute("a", "b", 2, e -> true,
                new SearchBudget(100, 0, 4096, Duration.ofSeconds(10))));
    }

    @Test void deadlineUsesMonotonicElapsedTimeAndChecksInterruptionWithoutClearingIt() {
        AtomicLong clock = new AtomicLong(100);
        var budget = new SearchBudget(10, 1, 4096, Duration.ofNanos(20), clock::get);
        clock.set(119);
        budget.check();
        clock.set(120);
        assertThrows(SearchBudget.LimitExceededException.class, budget::check);
        var fresh = SearchBudget.routing();
        try {
            Thread.currentThread().interrupt();
            assertThrows(java.util.concurrent.CancellationException.class, fresh::check);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
