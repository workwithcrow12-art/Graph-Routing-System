package graphroute;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.shortestpath.AStar;
import graphroute.algorithm.shortestpath.Dijkstra;
import graphroute.algorithm.shortestpath.PreparedShortestPath;
import graphroute.algorithm.shortestpath.YenKShortestPaths;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PreparedShortestPathTest {
    @Test
    void preparedGraphSnapshotsMutableTopologyAndCachesUntilRevisionChanges() {
        Graph graph = graph();
        AStar astar = new AStar();
        Dijkstra dijkstra = new Dijkstra();
        PreparedShortestPath prepared = astar.prepare(graph);
        PreparedShortestPath plain = dijkstra.prepare(graph);
        assertSame(prepared, astar.prepare(graph));
        assertSame(plain, dijkstra.prepare(graph));
        assertNotSame(graph, prepared.graph());
        assertTrue(prepared.graph().isFrozen());
        assertThrows(IllegalStateException.class, () -> prepared.graph().removeNode("b"));
        graph.removeEdge("b", "c");
        assertEquals(2, prepared.execute("a", "c").getTotalCost());
        assertEquals(5, astar.execute(graph, "a", "c").getTotalCost());
        assertEquals(5, dijkstra.execute(graph, "a", "c").getTotalCost());
        assertNotSame(prepared, astar.prepare(graph));
        assertNotSame(plain, dijkstra.prepare(graph));
        Graph frozen = graph.freeze();
        assertSame(frozen, new PreparedShortestPath(frozen, true).graph());
    }

    @Test
    void customCostsAreSampledExactlyOnceAndNeverRescannedForYenQueries() {
        Graph graph = graph().freeze();
        AtomicInteger costCalls = new AtomicInteger();
        PreparedShortestPath prepared = new PreparedShortestPath(graph, true, edge -> {
            costCalls.incrementAndGet();
            return edge.getRoadName().equals("direct") ? 0.5 : edge.getWeight();
        });
        assertEquals(graph.getAllEdges().size(), costCalls.get());
        for (int repeat = 0; repeat < 20; repeat++) {
            assertEquals(0.5, prepared.execute("a", "c").getTotalCost());
            assertEquals(List.of(0.5, 2.0), new YenKShortestPaths(prepared).execute("a", "c", 3)
                    .stream().map(AlgorithmResult::getTotalCost).toList());
        }
        assertEquals(graph.getAllEdges().size(), costCalls.get());
        for (double invalid : List.of(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> new PreparedShortestPath(graph, true, edge -> invalid));
        }
    }

    @Test
    void restrictionsUseIdentityAndDoNotChangePreparedGraphOrLaterRequests() {
        Graph graph = graph();
        Edge original = graph.getNeighbors("a").getFirst();
        Edge parallel = new Edge(original.getSource(), original.getDestination(), original.getWeight(), original.getRoadName());
        graph.addEdge(parallel);
        PreparedShortestPath prepared = new PreparedShortestPath(graph, true);
        AlgorithmResult alternate = prepared.search("a", "c", Set.of(), Set.of(original), edge -> true, 0).result();
        assertSame(parallel, alternate.getEdges().getFirst());
        assertEquals(5, prepared.search("a", "c", Set.of("b"), Set.of(), edge -> true, 0).result().getTotalCost());
        assertEquals(AlgorithmResult.Status.UNREACHABLE,
                prepared.search("a", "c", Set.of("a"), Set.of(), edge -> true, 0).result().getStatus());
        assertEquals(AlgorithmResult.Status.UNREACHABLE,
                prepared.search("a", "c", Set.of(), Set.of(), edge -> false, 0).result().getStatus());
        assertEquals(2, prepared.execute("a", "c").getTotalCost());
        assertEquals(4, prepared.graph().edgeCount());
        assertThrows(IllegalArgumentException.class, () -> prepared.cost(new Edge(original.getSource(), original.getDestination(), 1)));
    }

    @Test
    void repeatedReferenceToTheSameArcSamplesItsCustomCostOnlyOnce() {
        Graph graph = graph();
        Edge repeated = graph.getNeighbors("a").getFirst();
        graph.addEdge(repeated);
        AtomicInteger calls = new AtomicInteger();
        PreparedShortestPath prepared = new PreparedShortestPath(graph, true, edge -> {
            calls.incrementAndGet();
            return edge.getWeight();
        });
        assertEquals(3, calls.get());
        assertEquals(2, prepared.execute("a", "c").getTotalCost());
        assertEquals(2, new YenKShortestPaths(prepared).execute("a", "c", 3).size());
    }

    @Test
    void traceLimitsPreserveMetricsAndConcurrentRequestsRemainIndependent() throws Exception {
        PreparedShortestPath prepared = new PreparedShortestPath(graph(), true);
        PreparedShortestPath.SearchReport full = prepared.search("a", "c", Integer.MAX_VALUE);
        for (int limit : List.of(0, 1, 2)) {
            PreparedShortestPath.SearchReport bounded = prepared.search("a", "c", limit);
            assertEquals(full.result().getTotalCost(), bounded.result().getTotalCost());
            assertEquals(Math.min(limit, full.result().getSteps().size()), bounded.result().getSteps().size());
            assertEquals(full.expandedNodes(), bounded.expandedNodes());
            assertEquals(full.examinedEdges(), bounded.examinedEdges());
            assertEquals(full.result().getTraceStepCount(), bounded.result().getTraceStepCount());
            assertEquals(full.result().getVisitedNodeCount(), bounded.result().getVisitedNodeCount());
        }
        var samePlace = prepared.search("a", "a", 0).result();
        assertEquals(1, samePlace.getTraceStepCount());
        assertEquals(1, samePlace.getVisitedNodeCount());
        assertTrue(samePlace.getSteps().isEmpty());
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Callable<AlgorithmResult>> requests = java.util.stream.IntStream.range(0, 40)
                    .mapToObj(index -> (java.util.concurrent.Callable<AlgorithmResult>) () -> index % 2 == 0
                            ? prepared.search("a", "c", 0).result() : prepared.search("c", "a", 0).result()).toList();
            var results = executor.invokeAll(requests);
            for (int index = 0; index < results.size(); index++) {
                assertEquals(index % 2 == 0 ? AlgorithmResult.Status.SUCCESS : AlgorithmResult.Status.UNREACHABLE,
                        results.get(index).get().getStatus());
            }
        }
    }

    private static Graph graph() {
        Graph graph = new Graph(GraphType.DIRECTED);
        for (int index = 0; index < 3; index++) {
            String id = Character.toString('a' + index);
            graph.addNode(new Node(id, id, 21, 105.8 + index * 0.001));
        }
        graph.addEdge(new Edge(graph.getNode("a"), graph.getNode("b"), 1, "first"));
        graph.addEdge(new Edge(graph.getNode("b"), graph.getNode("c"), 1, "second"));
        graph.addEdge(new Edge(graph.getNode("a"), graph.getNode("c"), 5, "direct"));
        return graph;
    }
}
