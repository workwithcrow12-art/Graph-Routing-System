package graphroute;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.shortestpath.BellmanFord;
import graphroute.analysis.AlgorithmDescriptor;
import graphroute.analysis.AlgorithmRequest;
import graphroute.analysis.AlgorithmResponse;
import graphroute.analysis.AlgorithmService;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AlgorithmServiceTest {
    @Test
    void advertisesAllCapabilitiesWithTheirRequiredInputsAndLimits() {
        List<AlgorithmDescriptor> descriptors = AlgorithmService.algorithms();
        assertEquals(Set.of("DIJKSTRA", "ASTAR", "BELLMAN_FORD", "FLOYD_WARSHALL",
                "BFS", "DFS", "PRIM", "KRUSKAL"), descriptors.stream()
                .map(AlgorithmDescriptor::id).collect(Collectors.toSet()));
        for (AlgorithmDescriptor descriptor : descriptors) {
            assertEquals(descriptor.category().equals("SHORTEST_PATH"), descriptor.requiresStart());
            assertEquals(descriptor.requiresStart(), descriptor.requiresGoal());
            assertEquals(descriptor.category().equals("SPANNING_TREE"), descriptor.requiresUndirectedGraph());
            assertFalse(descriptor.resultMeaning().isBlank());
            assertFalse(descriptor.traceCostMeaning().isBlank());
            if (descriptor.id().equals("FLOYD_WARSHALL")) assertEquals(300, descriptor.maxNodes());
        }
        assertThrows(UnsupportedOperationException.class, descriptors::clear);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dijkstra", "astar", "A*", "a-star", "bellman-ford", "BellmanFord", "floyd-warshall", "FloydWarshall"})
    void weightedAlgorithmsReturnShortestRouteAndCosts(String name) {
        AlgorithmResponse response = new AlgorithmService(weightedGraph()).execute(new AlgorithmRequest(name, "A", "D"));
        assertEquals(AlgorithmResult.Status.SUCCESS, response.status());
        assertEquals(List.of("A", "B", "C", "D"), response.nodeIds());
        assertEquals(6, response.totalCost());
        assertEquals(6, response.edges().stream().mapToDouble(AlgorithmResponse.EdgeDto::weight).sum());
        assertEquals(4, response.metrics().graphNodes());
        assertEquals(4, response.metrics().graphEdges());
        assertEquals(8, response.metrics().graphArcs());
        assertEquals(3, response.metrics().resultEdges());
        assertTrue(response.metrics().elapsedNanos() >= 0);
        assertFalse(response.metrics().traceTruncated());
    }

    @Test
    void traversalTraceUsesDepthAndReportsWeightedCostOfItsDiscoveredRoute() {
        AlgorithmService service = new AlgorithmService(weightedGraph());
        AlgorithmResponse bfs = service.execute(new AlgorithmRequest("BFS", "A", "D"));
        AlgorithmResponse dfs = service.execute(new AlgorithmRequest("DFS", "A", "D"));
        assertEquals(List.of("A", "D"), bfs.nodeIds());
        assertEquals(20, bfs.totalCost());
        assertEquals(1, bfs.trace().getLast().cost());
        assertEquals(List.of("A", "B", "C", "D"), dfs.nodeIds());
        assertEquals(6, dfs.totalCost());
        assertEquals(3, dfs.trace().getLast().cost());
        assertTrue(bfs.trace().stream().allMatch(step -> step.event().equals("VISIT")));
        assertEquals(4, service.execute(new AlgorithmRequest("bfs", null, null)).nodeIds().size());
        assertEquals(4, service.execute(new AlgorithmRequest("dfs", "A", null)).nodeIds().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prim", "kruskal"})
    void spanningForestTracePairsEachSelectedEdgeWithCumulativeCost(String algorithm) {
        AlgorithmResponse response = new AlgorithmService(weightedGraph()).execute(new AlgorithmRequest(algorithm, null, null));
        assertEquals(AlgorithmResult.Status.SUCCESS, response.status());
        assertEquals(6, response.totalCost());
        assertEquals(4, response.metrics().visitedNodes(), "Forest coverage includes the root without a selected incoming edge");
        double cumulativeCost = 0;
        for (int index = 0; index < response.edges().size(); index++) {
            cumulativeCost += response.edges().get(index).weight();
            assertEquals(response.edges().get(index), response.trace().get(index).edge());
            assertEquals(cumulativeCost, response.trace().get(index).cost());
            assertEquals("SELECT_EDGE", response.trace().get(index).event());
        }
        Graph isolated = new Graph(GraphType.UNDIRECTED);
        isolated.addNode(new Node("alone", "Isolated vertex", 21, 105.8));
        var singleton = new AlgorithmService(isolated).execute(new AlgorithmRequest(algorithm, null, null, 0));
        assertEquals(1, singleton.metrics().visitedNodes());
        assertEquals(0, singleton.metrics().traceStepCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dijkstra", "astar", "bellman-ford", "floyd-warshall", "bfs", "dfs", "prim", "kruskal"})
    void traceLimitDoesNotChangeResultsAndReportsTheFullEventCount(String algorithm) {
        AlgorithmService service = new AlgorithmService(weightedGraph());
        boolean tree = algorithm.equals("prim") || algorithm.equals("kruskal");
        AlgorithmRequest request = new AlgorithmRequest(algorithm, tree ? null : "A", tree ? null : "D");
        AlgorithmResponse full = service.execute(request);
        for (int limit : new int[]{0, 1, 2}) {
            AlgorithmResponse bounded = service.execute(new AlgorithmRequest(algorithm, request.startId(), request.goalId(), limit));
            assertEquals(full.nodeIds(), bounded.nodeIds());
            assertEquals(full.edges(), bounded.edges());
            assertEquals(full.totalCost(), bounded.totalCost());
            assertEquals(full.trace().subList(0, Math.min(limit, full.trace().size())), bounded.trace());
            assertEquals(full.metrics().traceStepCount(), bounded.metrics().traceStepCount());
            assertEquals(full.metrics().visitedNodes(), bounded.metrics().visitedNodes());
            assertEquals(full.trace().size() > limit, bounded.metrics().traceTruncated());
        }
    }

    @Test
    void requestValidationRejectsMissingUnknownAndIncompatibleInputs() {
        AlgorithmService service = new AlgorithmService(weightedGraph());
        List<AlgorithmRequest> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(new AlgorithmRequest(null, "A", "D"));
        invalid.add(new AlgorithmRequest("  ", "A", "D"));
        invalid.add(new AlgorithmRequest("unknown", "A", "D"));
        invalid.add(new AlgorithmRequest("dijkstra", null, "D"));
        invalid.add(new AlgorithmRequest("astar", "A", null));
        invalid.add(new AlgorithmRequest("bfs", null, "D"));
        invalid.add(new AlgorithmRequest("prim", "A", null));
        invalid.add(new AlgorithmRequest("kruskal", null, "D"));
        invalid.add(new AlgorithmRequest("dfs", "missing", null));
        invalid.add(new AlgorithmRequest("bfs", " ", null));
        invalid.add(new AlgorithmRequest("dijkstra", "A", "missing"));
        invalid.add(new AlgorithmRequest("bfs", "A", null, -1));
        invalid.add(new AlgorithmRequest("bfs", "A", null, AlgorithmService.MAX_TRACE_STEPS + 1));
        for (AlgorithmRequest request : invalid) {
            assertThrows(IllegalArgumentException.class, () -> service.execute(request), String.valueOf(request));
        }
    }

    @Test
    void unreachableRouteUsesJsonNullForNonfiniteCost() throws Exception {
        Graph graph = new Graph(GraphType.DIRECTED);
        node(graph, "A");
        node(graph, "B");
        AlgorithmResponse response = new AlgorithmService(graph).execute(new AlgorithmRequest("dijkstra", "A", "B"));
        assertEquals(AlgorithmResult.Status.UNREACHABLE, response.status());
        assertNull(response.totalCost());
        assertTrue(response.nodeIds().isEmpty());
        String json = new ObjectMapper().writeValueAsString(response);
        assertTrue(json.contains("\"totalCost\":null"));
        assertFalse(json.contains("Infinity"));
    }

    @Test
    void serviceSnapshotsTopologyAndProducesImmutableDetachedTransportValues() {
        Graph graph = weightedGraph();
        AlgorithmService service = new AlgorithmService(graph);
        AlgorithmResponse original = service.execute(new AlgorithmRequest("dijkstra", "A", "D"));
        graph.removeNode("B");
        graph.removeNode("D");
        AlgorithmResponse later = service.execute(new AlgorithmRequest("dijkstra", "A", "D"));
        assertEquals(original.nodeIds(), later.nodeIds());
        assertEquals(original.edges(), later.edges());
        assertEquals(original.trace(), later.trace());
        assertThrows(UnsupportedOperationException.class, () -> original.nodeIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> original.edges().clear());
        assertThrows(UnsupportedOperationException.class, () -> original.trace().clear());
        var json = new ObjectMapper().valueToTree(original);
        assertEquals("A", json.path("edges").get(0).path("sourceId").asText());
        assertFalse(json.path("edges").get(0).has("source"));
        assertEquals("A", json.path("trace").get(0).path("nodeId").asText());
        assertFalse(json.path("trace").get(0).has("node"));
    }

    @Test
    void concurrentMixedExecutionsRemainIndependent() throws Exception {
        AlgorithmService service = new AlgorithmService(weightedGraph());
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Callable<AlgorithmResponse>> tasks = new ArrayList<>();
            for (int iteration = 0; iteration < 48; iteration++) {
                String algorithm = List.of("dijkstra", "astar", "bellman-ford", "floyd-warshall", "bfs", "dfs").get(iteration % 6);
                tasks.add(() -> service.execute(new AlgorithmRequest(algorithm, "A", "D", 1)));
            }
            for (var future : executor.invokeAll(tasks)) {
                AlgorithmResponse response = future.get();
                assertEquals(response.algorithm().equals("BFS") ? 20.0 : 6.0, response.totalCost());
                assertEquals(1, response.trace().size());
                assertTrue(response.metrics().traceTruncated());
            }
        }
    }

    @Test
    void expensiveAlgorithmsRejectOversizedWorkBeforeAllocatingTheirWorkspaces() {
        Graph floydGraph = new Graph(GraphType.DIRECTED);
        for (int i = 0; i <= AlgorithmService.MAX_FLOYD_WARSHALL_NODES; i++) node(floydGraph, "n" + i);
        AlgorithmService floyd = new AlgorithmService(floydGraph);
        var refusal = floyd.execute(new AlgorithmRequest("floyd-warshall", "n0", "n1"));
        assertEquals(AlgorithmResult.Status.INVALID, refusal.status());
        assertTrue(refusal.message().contains("limited"));
        Graph bellmanGraph = new Graph(GraphType.DIRECTED);
        for (int i = 0; i < 5100; i++) node(bellmanGraph, "n" + i);
        for (int i = 1; i < 5100; i++) edge(bellmanGraph, "n" + (i - 1), "n" + i, 1);
        AlgorithmService bellman = new AlgorithmService(bellmanGraph);
        var bellmanRefusal = bellman.execute(new AlgorithmRequest("bellman-ford", "n0", "n5099"));
        assertEquals(AlgorithmResult.Status.INVALID, bellmanRefusal.status());
        assertTrue(bellmanRefusal.message().contains("work limit"));
        assertEquals(5099, bellman.execute(new AlgorithmRequest("dijkstra", "n0", "n5099", 0)).totalCost());
    }

    @Test
    void bellmanFordBoundsRecordedObjectsDuringExecutionWithoutLosingStatistics() {
        Graph graph = new Graph(GraphType.DIRECTED);
        node(graph, "START");
        node(graph, "GOAL");
        for (int i = 0; i < 100; i++) {
            String id = String.format("N%03d", i);
            node(graph, id);
            edge(graph, "START", id, 0);
            edge(graph, id, "GOAL", 100 - i);
        }
        AlgorithmResult bounded = new BellmanFord().execute(graph, "START", "GOAL", 2);
        assertEquals(2, bounded.getSteps().size());
        assertEquals(201, bounded.getTraceStepCount());
        assertEquals(102, bounded.getVisitedNodeCount());
        assertEquals(1, bounded.getCost());
        AlgorithmResult noTrace = new BellmanFord().execute(graph, "START", "GOAL", 0);
        assertTrue(noTrace.getSteps().isEmpty());
        assertEquals(bounded.getTraceStepCount(), noTrace.getTraceStepCount());
        assertEquals(bounded.getPath(), noTrace.getPath());
    }

    @Test
    void directedGraphsAreNotSilentlyConvertedForSpanningTrees() {
        Graph directed = new Graph(GraphType.DIRECTED);
        node(directed, "A");
        node(directed, "B");
        edge(directed, "A", "B", 1);
        AlgorithmService service = new AlgorithmService(directed);
        assertEquals(AlgorithmResult.Status.INVALID, service.execute(new AlgorithmRequest("prim", null, null)).status());
        assertEquals(AlgorithmResult.Status.INVALID, service.execute(new AlgorithmRequest("kruskal", null, null)).status());
    }

    private static Graph weightedGraph() {
        Graph graph = new Graph(GraphType.UNDIRECTED);
        for (String id : List.of("A", "B", "C", "D")) node(graph, id);
        edge(graph, "A", "B", 1);
        edge(graph, "B", "C", 2);
        edge(graph, "C", "D", 3);
        edge(graph, "A", "D", 20);
        return graph;
    }

    private static void node(Graph graph, String id) { graph.addNode(new Node(id, id, 0, 0)); }
    private static void edge(Graph graph, String source, String destination, double weight) {
        graph.addEdge(new Edge(graph.getNode(source), graph.getNode(destination), weight, source + "-" + destination));
    }
}
