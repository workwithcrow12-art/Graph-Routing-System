package graphroute.analysis;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.AlgorithmStep;
import graphroute.algorithm.mst.Kruskal;
import graphroute.algorithm.mst.Prim;
import graphroute.algorithm.shortestpath.BellmanFord;
import graphroute.algorithm.shortestpath.FloydWarshall;
import graphroute.algorithm.shortestpath.PreparedShortestPath;
import graphroute.algorithm.shortestpath.SearchBudget;
import graphroute.routing.RoutingException;
import graphroute.algorithm.traversal.BFS;
import graphroute.algorithm.traversal.DFS;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Framework-neutral execution facade for educational analysis and future clients.
 * Owns a frozen graph snapshot, so source edits and concurrent requests cannot
 * change an execution's topology. Expensive algorithms have explicit work bounds.
 */
public final class AlgorithmService {
    public static final int DEFAULT_TRACE_LIMIT = 1000;
    public static final int MAX_TRACE_STEPS = 10000;
    public static final int MAX_GRAPH_NODES = 100000;
    public static final int MAX_GRAPH_ARCS = 500000;
    public static final int MAX_FLOYD_WARSHALL_NODES = 300;
    public static final long MAX_BELLMAN_FORD_RELAXATIONS = 25000000L;

    private static final List<AlgorithmDescriptor> ALGORITHMS = List.of(
            descriptor("DIJKSTRA", "Dijkstra", "SHORTEST_PATH", "Route distance from start"),
            descriptor("ASTAR", "A*", "SHORTEST_PATH", "Route distance from start"),
            descriptor("BELLMAN_FORD", "Bellman-Ford", "SHORTEST_PATH", "Improved route distance from start"),
            descriptor("FLOYD_WARSHALL", "Floyd-Warshall", "SHORTEST_PATH", "Distance to the current intermediate node"),
            descriptor("BFS", "Breadth-first search", "TRAVERSAL", "Hop depth in the traversal tree"),
            descriptor("DFS", "Depth-first search", "TRAVERSAL", "Depth in the traversal tree"),
            descriptor("PRIM", "Prim", "SPANNING_TREE", "Cumulative selected edge weight"),
            descriptor("KRUSKAL", "Kruskal", "SPANNING_TREE", "Cumulative selected edge weight"));

    private final Graph graph;
    private final int arcCount;
    private volatile PreparedShortestPath astar;
    private volatile PreparedShortestPath dijkstra;
    private AlgorithmResult prim;
    private AlgorithmResult kruskal;

    public AlgorithmService(Graph graph) {
        Objects.requireNonNull(graph, "graph");
        this.graph = graph.snapshot();
        this.arcCount = this.graph.getAllEdges().size();
    }

    public static List<AlgorithmDescriptor> algorithms() {
        return ALGORITHMS;
    }

    /** Graph-specific support, independent of a particular start/goal or trace request. */
    public record Availability(String algorithm, boolean available, String message) { }

    public List<Availability> availability() {
        return ALGORITHMS.stream().map(descriptor -> {
            String reason = rejectionReason(descriptor);
            return new Availability(descriptor.id(), reason == null,
                    reason == null ? "Supported on this graph." : reason);
        }).toList();
    }

    private String rejectionReason(AlgorithmDescriptor descriptor) {
        String name = descriptor.id();
        if (graph.nodeCount() > MAX_GRAPH_NODES || arcCount > MAX_GRAPH_ARCS)
            return "Analysis supports at most " + MAX_GRAPH_NODES + " nodes and "
                    + MAX_GRAPH_ARCS + " arcs. Choose graph=sample or an explicit nodeIds subgraph.";
        if (name.equals("FLOYD_WARSHALL") && graph.nodeCount() > MAX_FLOYD_WARSHALL_NODES)
            return "Floyd-Warshall is limited to " + MAX_FLOYD_WARSHALL_NODES
                    + " nodes. Choose graph=sample or an explicit nodeIds subgraph.";
        if (name.equals("BELLMAN_FORD")
                && (long) Math.max(0, graph.nodeCount() - 1) * arcCount > MAX_BELLMAN_FORD_RELAXATIONS)
            return "Bellman-Ford exceeds the analysis work limit. Choose graph=sample, a nodeIds subgraph, Dijkstra or A*.";
        if (descriptor.requiresUndirectedGraph() && graph.getType() == graphroute.model.GraphType.DIRECTED)
            return descriptor.name() + " requires an undirected graph. Choose graph=sample or explicitly set undirected=true.";
        return null;
    }

    public AlgorithmResponse execute(AlgorithmRequest request) {
        return execute(request, SearchBudget.analysis());
    }

    public AlgorithmResponse execute(AlgorithmRequest request, SearchBudget budget) {
        Objects.requireNonNull(budget, "budget");
        if (request == null) throw new IllegalArgumentException("Algorithm request is required.");
        String name = normalizeAlgorithm(request.algorithm());
        AlgorithmDescriptor descriptor = ALGORITHMS.stream().filter(item -> item.id().equals(name))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown algorithm: " + request.algorithm()));
        int limit = request.maxTraceSteps() == null ? DEFAULT_TRACE_LIMIT : request.maxTraceSteps();
        if (limit < 0 || limit > MAX_TRACE_STEPS) {
            throw new IllegalArgumentException("maxTraceSteps must be between 0 and " + MAX_TRACE_STEPS + ".");
        }
        String start = nodeId(request.startId(), "startId");
        String goal = nodeId(request.goalId(), "goalId");
        validateEndpoints(descriptor, start, goal);
        String reason = rejectionReason(descriptor);
        if (reason != null) return rejected(name, reason);

        long began = System.nanoTime();
        AlgorithmResult result;
        try {
            budget.check();
            result = switch (name) {
                case "DIJKSTRA" -> prepared(false).search(start, goal, java.util.Set.of(), java.util.Set.of(), e -> true, limit, budget).result();
                case "ASTAR" -> prepared(true).search(start, goal, java.util.Set.of(), java.util.Set.of(), e -> true, limit, budget).result();
                case "BELLMAN_FORD" -> new BellmanFord().execute(graph, start, goal, limit, budget);
                case "FLOYD_WARSHALL" -> new FloydWarshall().execute(graph, start, goal, budget);
                case "BFS" -> new BFS().execute(graph, start, goal, budget);
                case "DFS" -> new DFS().execute(graph, start, goal, budget);
                case "PRIM" -> forest(true, budget);
                case "KRUSKAL" -> forest(false, budget);
                default -> throw new IllegalStateException("Unhandled registered algorithm: " + name);
            };
            budget.check();
        } catch (SearchBudget.LimitExceededException exception) {
            throw new RoutingException("ANALYSIS_LIMIT_EXCEEDED", exception.getMessage());
        }
        long elapsed = Math.max(0, System.nanoTime() - began);
        List<AlgorithmResponse.EdgeDto> edges = result.getEdges().stream().map(AlgorithmService::edge).toList();
        List<AlgorithmResponse.TraceStep> trace = new ArrayList<>();
        int stepCount = result.getTraceStepCount();
        for (int i = 0; i < Math.min(limit, result.getSteps().size()); i++) {
            AlgorithmStep step = result.getSteps().get(i);
            AlgorithmResponse.EdgeDto selected = descriptor.category().equals("SPANNING_TREE")
                    && i < edges.size() ? edges.get(i) : null;
            trace.add(new AlgorithmResponse.TraceStep(i, event(name), step.getNode().getId(),
                    finite(step.getCost()), selected));
        }
        long visited = descriptor.category().equals("SPANNING_TREE")
                ? result.getPath().size() : result.getVisitedNodeCount();
        AlgorithmResponse.Metrics metrics = new AlgorithmResponse.Metrics(graph.nodeCount(), graph.edgeCount(),
                arcCount, visited, edges.size(), stepCount, elapsed, stepCount > limit);
        return new AlgorithmResponse(name, result.getStatus(), result.getPath().stream().map(Node::getId).toList(),
                edges, finite(result.getTotalCost()), trace, metrics, switch (result.getStatus()) {
                    case INVALID -> "The selected graph is incompatible with this algorithm.";
                    case UNREACHABLE -> "No path connects startId and goalId in the selected graph.";
                    case PARTIAL -> "The graph is disconnected; a minimum spanning forest was returned.";
                    case NEGATIVE_CYCLE -> "A negative cycle is reachable from startId; no finite shortest path is available.";
                    default -> "Completed.";
                });
    }

    private synchronized PreparedShortestPath prepared(boolean heuristic) {
        if (heuristic) {
            if (astar == null) astar = new PreparedShortestPath(graph, true);
            return astar;
        }
        if (dijkstra == null) dijkstra = new PreparedShortestPath(graph, false);
        return dijkstra;
    }

    private AlgorithmResponse rejected(String name, String message) {
        return new AlgorithmResponse(name, AlgorithmResult.Status.INVALID, List.of(), List.of(), null,
                List.of(), new AlgorithmResponse.Metrics(graph.nodeCount(), graph.edgeCount(), arcCount,
                0, 0, 0, 0, false), message);
    }

    private synchronized AlgorithmResult forest(boolean usePrim, SearchBudget budget) {
        budget.check();
        if (usePrim) {
            if (prim == null) prim = new Prim().execute(graph, budget);
            return prim;
        }
        if (kruskal == null) kruskal = new Kruskal().execute(graph, budget);
        return kruskal;
    }

    private String nodeId(String id, String field) {
        if (id == null) return null;
        if (id.isBlank()) throw new IllegalArgumentException(field + " must not be blank.");
        if (!graph.hasNode(id)) throw new IllegalArgumentException("Unknown " + field + ": " + id);
        return id;
    }

    private static void validateEndpoints(AlgorithmDescriptor descriptor, String start, String goal) {
        if (descriptor.requiresStart() && start == null || descriptor.requiresGoal() && goal == null) {
            throw new IllegalArgumentException(descriptor.name() + " requires startId and goalId.");
        }
        if (descriptor.category().equals("TRAVERSAL") && goal != null && start == null) {
            throw new IllegalArgumentException("A traversal goalId requires startId.");
        }
        if (descriptor.category().equals("SPANNING_TREE") && (start != null || goal != null)) {
            throw new IllegalArgumentException("Spanning tree algorithms operate on the whole graph; omit startId and goalId.");
        }
    }

    private static String normalizeAlgorithm(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("algorithm is required.");
        String normalized = name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "A*", "A_STAR" -> "ASTAR";
            case "BELLMANFORD" -> "BELLMAN_FORD";
            case "FLOYDWARSHALL" -> "FLOYD_WARSHALL";
            default -> normalized;
        };
    }

    private static AlgorithmResponse.EdgeDto edge(Edge edge) {
        return new AlgorithmResponse.EdgeDto(edge.getSource().getId(), edge.getDestination().getId(),
                edge.getWeight(), edge.getRoadName());
    }

    private static Double finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static String event(String name) {
        return switch (name) {
            case "BFS", "DFS" -> "VISIT";
            case "PRIM", "KRUSKAL" -> "SELECT_EDGE";
            case "BELLMAN_FORD" -> "RELAX";
            case "FLOYD_WARSHALL" -> "INTERMEDIATE";
            default -> "SETTLE";
        };
    }

    private static AlgorithmDescriptor descriptor(String id, String name, String category, String traceMeaning) {
        boolean path = category.equals("SHORTEST_PATH");
        boolean tree = category.equals("SPANNING_TREE");
        String resultMeaning = path ? "Shortest weighted route" : tree ? "Minimum spanning forest"
                : "Visit order without goal; minimum-hop route for BFS or discovered route for DFS with goal";
        return new AlgorithmDescriptor(id, name, category, path, path, tree,
                id.equals("FLOYD_WARSHALL") ? MAX_FLOYD_WARSHALL_NODES : MAX_GRAPH_NODES,
                resultMeaning, traceMeaning);
    }
}
