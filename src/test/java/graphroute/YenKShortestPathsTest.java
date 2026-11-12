package graphroute;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.shortestpath.PreparedShortestPath;
import graphroute.algorithm.shortestpath.YenKShortestPaths;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class YenKShortestPathsTest {
    @Test
    void matchesExhaustiveEdgeDistinctLooplessOracleOnSeededMultigraphs() {
        Random random = new Random(20261019);
        for (int sample = 0; sample < 60; sample++) {
            Graph graph = graph(sample % 2 == 0 ? GraphType.DIRECTED : GraphType.UNDIRECTED, 6);
            for (int from = 0; from < 6; from++) {
                for (int to = 0; to < 6; to++) {
                    if (random.nextDouble() < 0.20) {
                        edge(graph, from, to, random.nextInt(17) / 4.0, "road-" + from + "-" + to);
                        if (random.nextDouble() < 0.20) {
                            edge(graph, from, to, random.nextInt(17) / 4.0, "parallel");
                        }
                    }
                }
            }
            for (boolean heuristic : List.of(false, true)) {
                PreparedShortestPath prepared = new PreparedShortestPath(graph, heuristic);
                Predicate<Edge> allowed = candidate -> !candidate.getRoadName().equals("road-2-3");
                for (String goal : List.of("1", "3", "5")) {
                    assertMatchesOracle(prepared, "0", goal, allowed);
                }
            }
        }
    }

    @Test
    void retainsPendingCandidatesAcrossSpursAndUsesNonuniformCustomCosts() {
        Graph graph = graph(GraphType.DIRECTED, 5);
        edge(graph, 0, 1, 1, "slow");
        edge(graph, 1, 4, 1, "slow");
        edge(graph, 0, 2, 3, "fast");
        edge(graph, 2, 4, 3, "fast");
        edge(graph, 0, 3, 4, "fast");
        edge(graph, 3, 4, 4, "fast");
        edge(graph, 2, 3, 0.5, "fast");
        edge(graph, 1, 2, 0, "slow");
        PreparedShortestPath prepared = new PreparedShortestPath(graph, true,
                candidate -> candidate.getWeight() / (candidate.getRoadName().equals("fast") ? 10 : 1));

        List<AlgorithmResult> routes = new YenKShortestPaths(prepared).execute("0", "4", 3);

        assertEquals(3, routes.size());
        double[] expected = {0.6, 0.75, 0.8};
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], routes.get(index).getTotalCost(), 1e-12);
        }
        assertMatchesOracle(prepared, "0", "4", candidate -> true);
    }

    @Test
    void equalParallelEdgesRemainDistinctAndZeroCyclesAreNeverReturned() {
        Graph graph = graph(GraphType.DIRECTED, 3);
        Edge first = edge(graph, 0, 1, 0, "same");
        Edge second = edge(graph, 0, 1, 0, "same");
        edge(graph, 1, 0, 0, "back");
        edge(graph, 1, 1, 0, "self");
        edge(graph, 1, 2, 1, "finish");
        edge(graph, 0, 2, 5, "direct");
        PreparedShortestPath prepared = new PreparedShortestPath(graph, true);

        List<AlgorithmResult> routes = new YenKShortestPaths(prepared).execute("0", "2", 3);

        assertEquals(first, second, "These distinct arcs deliberately have equal values");
        assertNotEquals(prepared.edgeIndex(first), prepared.edgeIndex(second));
        assertEquals(List.of(1.0, 1.0, 5.0), routes.stream().map(AlgorithmResult::getTotalCost).toList());
        assertNotSame(routes.get(0).getEdges().getFirst(), routes.get(1).getEdges().getFirst());
        routes.forEach(route -> assertEquals(route.getPath().size(), new HashSet<>(route.getPath()).size()));
        assertMatchesOracle(prepared, "0", "2", candidate -> candidate != first);
    }

    @Test
    void handlesDisconnectedSingleRouteSameEndpointAndInputValidation() {
        Graph graph = graph(GraphType.DIRECTED, 3);
        edge(graph, 0, 1, 2, "only");
        YenKShortestPaths yen = new YenKShortestPaths(new PreparedShortestPath(graph, true));
        assertTrue(yen.execute("0", "2", 3).isEmpty());
        assertTrue(yen.execute("0", "1", 3, candidate -> false).isEmpty());
        assertEquals(1, yen.execute("0", "1", 3).size());
        List<AlgorithmResult> same = yen.execute("1", "1", 3);
        assertEquals(1, same.size());
        assertEquals(AlgorithmResult.Status.START_EQUALS_GOAL, same.getFirst().getStatus());
        assertEquals(0, same.getFirst().getTotalCost());
        assertThrows(IllegalArgumentException.class, () -> yen.execute("0", "missing", 3));
        assertThrows(IllegalArgumentException.class, () -> yen.execute("0", "1", 0));
        assertThrows(IllegalArgumentException.class, () -> yen.execute("0", "1", 4));
        assertThrows(UnsupportedOperationException.class, () -> same.clear());
    }

    private static void assertMatchesOracle(PreparedShortestPath prepared, String start, String goal,
                                            Predicate<Edge> allowed) {
        List<OracleRoute> oracle = new ArrayList<>();
        enumerate(prepared, start, goal, allowed, new HashSet<>(), new ArrayList<>(), 0, oracle);
        oracle.sort(java.util.Comparator.comparingDouble(OracleRoute::cost));
        List<AlgorithmResult> actual = new YenKShortestPaths(prepared).execute(start, goal, 3, allowed);
        assertEquals(Math.min(3, oracle.size()), actual.size());
        Set<List<Integer>> signatures = new HashSet<>();
        Set<List<Integer>> possible = new HashSet<>(oracle.stream().map(OracleRoute::edges).toList());
        for (int index = 0; index < actual.size(); index++) {
            AlgorithmResult route = actual.get(index);
            List<Integer> signature = route.getEdges().stream().map(prepared::edgeIndex).toList();
            assertTrue(signatures.add(signature), "Every result must be edge-distinct");
            assertTrue(possible.contains(signature), "Every result must be an allowed loopless path");
            assertEquals(oracle.get(index).cost(), route.getTotalCost(), 1e-9);
            assertEquals(route.getTotalCost(), route.getEdges().stream().mapToDouble(prepared::cost).sum(), 1e-9);
            assertEquals(start, route.getPath().getFirst().getId());
            assertEquals(goal, route.getPath().getLast().getId());
        }
    }

    private static void enumerate(PreparedShortestPath prepared, String current, String goal,
                                  Predicate<Edge> allowed, Set<String> visited, List<Integer> path,
                                  double cost, List<OracleRoute> routes) {
        if (current.equals(goal)) {
            routes.add(new OracleRoute(cost, List.copyOf(path)));
            return;
        }
        visited.add(current);
        for (Edge edge : prepared.graph().getNeighbors(current)) {
            String next = edge.getDestination().getId();
            if (allowed.test(edge) && !visited.contains(next)) {
                path.add(prepared.edgeIndex(edge));
                enumerate(prepared, next, goal, allowed, visited, path, cost + prepared.cost(edge), routes);
                path.removeLast();
            }
        }
        visited.remove(current);
    }

    private static Graph graph(GraphType type, int count) {
        Graph graph = new Graph(type);
        for (int index = 0; index < count; index++) {
            graph.addNode(new Node(Integer.toString(index), "Place " + index, 21 + index * 0.001, 105.8));
        }
        return graph;
    }

    private static Edge edge(Graph graph, int from, int to, double weight, String name) {
        Edge edge = new Edge(graph.getNode(Integer.toString(from)), graph.getNode(Integer.toString(to)), weight, name);
        graph.addEdge(edge);
        return edge;
    }

    private record OracleRoute(double cost, List<Integer> edges) { }
}
