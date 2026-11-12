package graphroute;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.ShortestPathAlgorithm;
import graphroute.algorithm.shortestpath.AStar;
import graphroute.algorithm.shortestpath.BellmanFord;
import graphroute.algorithm.shortestpath.Dijkstra;
import graphroute.algorithm.shortestpath.FloydWarshall;
import graphroute.io.GraphFileManager;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ShortestPathAlgorithmsTest {
    static Stream<ShortestPathAlgorithm> algorithms() {
        return Stream.of(new Dijkstra(), new AStar(), new BellmanFord(), new FloydWarshall());
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void findsMinimumWeightRatherThanFewestHops(ShortestPathAlgorithm algorithm) {
        Graph graph = graph(GraphType.DIRECTED, "a", "b", "c", "d");
        edge(graph, "a", "d", 9);
        edge(graph, "a", "b", 1);
        edge(graph, "b", "c", 2);
        edge(graph, "c", "d", 1);

        AlgorithmResult result = algorithm.execute(graph, "a", "d");

        assertEquals(AlgorithmResult.Status.SUCCESS, result.getStatus());
        assertEquals(List.of("a", "b", "c", "d"), ids(result));
        assertEquals(4, result.getTotalCost());
        assertValidPath(graph, result, "a", "d");
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void respectsDirectedEdgesAndDisconnectedNodes(ShortestPathAlgorithm algorithm) {
        Graph graph = graph(GraphType.DIRECTED, "a", "b", "isolated");
        edge(graph, "a", "b", 3);

        assertEquals(3, algorithm.execute(graph, "a", "b").getTotalCost());
        for (String destination : List.of("a", "isolated")) {
            AlgorithmResult result = algorithm.execute(graph, "b", destination);
            assertEquals(AlgorithmResult.Status.UNREACHABLE, result.getStatus());
            assertFalse(result.isSuccess());
            assertTrue(result.getPath().isEmpty());
            assertEquals(Double.POSITIVE_INFINITY, result.getTotalCost());
        }
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void handlesZeroWeightCyclesAndParallelEdges(ShortestPathAlgorithm algorithm) {
        Graph graph = graph(GraphType.UNDIRECTED, "a", "b", "c");
        edge(graph, "a", "b", 8);
        edge(graph, "a", "b", 0);
        edge(graph, "b", "b", 0);
        edge(graph, "b", "c", 2);
        edge(graph, "a", "c", 5);

        AlgorithmResult result = algorithm.execute(graph, "a", "c");

        assertEquals(2, result.getTotalCost());
        assertEquals(List.of("a", "b", "c"), ids(result));
        assertValidPath(graph, result, "a", "c");
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void returnsExplicitStatusForSamePlaceAndInvalidInputs(ShortestPathAlgorithm algorithm) {
        Graph graph = graph(GraphType.UNDIRECTED, "a");
        AlgorithmResult same = algorithm.execute(graph, "a", "a");
        assertEquals(AlgorithmResult.Status.START_EQUALS_GOAL, same.getStatus());
        assertTrue(same.isSuccess());
        assertEquals(List.of("a"), ids(same));
        assertEquals(0, same.getTotalCost());

        assertEquals(AlgorithmResult.Status.INVALID, algorithm.execute(null, "a", "a").getStatus());
        assertEquals(AlgorithmResult.Status.INVALID, algorithm.execute(graph, null, "a").getStatus());
        assertEquals(AlgorithmResult.Status.INVALID, algorithm.execute(graph, "a", null).getStatus());
        assertEquals(AlgorithmResult.Status.INVALID, algorithm.execute(graph, "a", "missing").getStatus());
        assertEquals(AlgorithmResult.Status.INVALID,
                algorithm.execute(new Graph(), "missing", "missing").getStatus());
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void requestsDoNotMutateGraphOrRetainDistances(ShortestPathAlgorithm algorithm) {
        Graph graph = graph(GraphType.UNDIRECTED, "a", "b", "c");
        edge(graph, "a", "b", 1);
        edge(graph, "b", "c", 2);
        long revision = graph.getRevision();
        List<Node> nodes = List.copyOf(graph.getAllNodes());
        List<Edge> edges = graph.getAllEdges();

        AlgorithmResult first = algorithm.execute(graph, "a", "c");
        AlgorithmResult reverse = algorithm.execute(graph, "c", "a");
        assertEquals(revision, graph.getRevision());
        assertEquals(nodes, List.copyOf(graph.getAllNodes()));
        assertEquals(edges, graph.getAllEdges());
        graph.removeEdge("b", "c");
        AlgorithmResult disconnected = algorithm.execute(graph, "a", "c");

        assertEquals(List.of("a", "b", "c"), ids(first));
        assertEquals(List.of("c", "b", "a"), ids(reverse));
        assertEquals(AlgorithmResult.Status.UNREACHABLE, disconnected.getStatus());
        assertThrows(UnsupportedOperationException.class, () -> first.getPath().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.getEdges().clear());
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void matchesExhaustiveSimplePathOracleOnSeededGraphs(ShortestPathAlgorithm algorithm) {
        Random random = new Random(240918);
        for (int sample = 0; sample < 12; sample++) {
            Graph graph = graph(sample % 2 == 0 ? GraphType.DIRECTED : GraphType.UNDIRECTED,
                    "a", "b", "c", "d", "e", "f");
            for (Node source : graph.getAllNodes()) {
                for (Node destination : graph.getAllNodes()) {
                    if (!source.equals(destination) && random.nextDouble() < 0.23) {
                        edge(graph, source.getId(), destination.getId(), random.nextInt(8) / 4.0);
                    }
                }
            }
            verifyEveryPair(algorithm, graph);
        }
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void routesEveryBundledPlacePairUsingActualRoadWeights(ShortestPathAlgorithm algorithm) {
        Graph graph = new GraphFileManager().loadThanhXuanGraph();
        assertFalse(graph.isEmpty());
        verifyEveryPair(algorithm, graph);
    }

    private static void verifyEveryPair(ShortestPathAlgorithm algorithm, Graph graph) {
        for (Node source : graph.getAllNodes()) {
            for (Node destination : graph.getAllNodes()) {
                String context = algorithm.getClass().getSimpleName() + ": "
                        + source.getId() + " -> " + destination.getId();
                double expected = exhaustiveCost(graph, source.getId(), destination.getId(), new HashSet<>());
                AlgorithmResult result = algorithm.execute(graph, source.getId(), destination.getId());
                if (Double.isFinite(expected)) {
                    assertTrue(result.isSuccess(), context);
                    assertEquals(expected, result.getTotalCost(), 1e-9, context);
                    assertValidPath(graph, result, source.getId(), destination.getId());
                } else {
                    assertEquals(AlgorithmResult.Status.UNREACHABLE, result.getStatus(), context);
                }
            }
        }
    }

    private static double exhaustiveCost(Graph graph, String current, String goal, Set<String> visited) {
        if (current.equals(goal)) {
            return 0;
        }
        visited.add(current);
        double best = Double.POSITIVE_INFINITY;
        for (Edge edge : graph.getNeighbors(current)) {
            String neighbor = edge.getDestination().getId();
            if (!visited.contains(neighbor)) {
                best = Math.min(best, edge.getWeight() + exhaustiveCost(graph, neighbor, goal, visited));
            }
        }
        visited.remove(current);
        return best;
    }

    private static void assertValidPath(Graph graph, AlgorithmResult result, String start, String goal) {
        List<Node> path = result.getPath();
        assertFalse(path.isEmpty());
        assertEquals(start, path.getFirst().getId());
        assertEquals(goal, path.getLast().getId());
        assertEquals(path.size(), new HashSet<>(path).size(), "A shortest route cannot contain a cycle");
        assertEquals(path.size() - 1, result.getEdges().size());
        double cost = 0;
        for (int index = 0; index < path.size(); index++) {
            assertSame(graph.getNode(path.get(index).getId()), path.get(index));
            if (index + 1 < path.size()) {
                String next = path.get(index + 1).getId();
                cost += graph.getNeighbors(path.get(index).getId()).stream()
                        .filter(edge -> edge.getDestination().getId().equals(next))
                        .mapToDouble(Edge::getWeight).min().orElseThrow();
                assertEquals(path.get(index).getId(), result.getEdges().get(index).getSource().getId());
                assertEquals(next, result.getEdges().get(index).getDestination().getId());
            }
        }
        assertEquals(cost, result.getTotalCost(), 1e-9);
        assertEquals(cost, result.getEdges().stream().mapToDouble(Edge::getWeight).sum(), 1e-9);
    }

    private static Graph graph(GraphType type, String... ids) {
        Graph graph = new Graph(type);
        for (int index = 0; index < ids.length; index++) {
            graph.addNode(new Node(ids[index], ids[index], 20.99 + index * 0.01, 105.80 + index * 0.02));
        }
        return graph;
    }

    private static void edge(Graph graph, String source, String destination, double weight) {
        graph.addEdge(new Edge(graph.getNode(source), graph.getNode(destination), weight, source + destination));
    }

    private static List<String> ids(AlgorithmResult result) {
        return result.getPath().stream().map(Node::getId).toList();
    }
}
