package graphroute.algorithm.shortestpath;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.AlgorithmStep;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Reusable, thread-safe shortest-path index over a frozen graph. Validation,
 * stable arc ordering and the geographic lower bound are prepared once. A
 * query only visits its reachable search frontier; restrictions never copy the graph.
 */
public final class PreparedShortestPath {
    private final Graph graph;
    private final double heuristicScale;
    private final Map<String, List<Edge>> outgoing;
    private final Map<Edge, Integer> edgeIndices;
    private final Map<Edge, Double> costs;

    public record SearchReport(AlgorithmResult result, long expandedNodes, long examinedEdges) { }

    public PreparedShortestPath(Graph graph, boolean useHeuristic) {
        this(graph, useHeuristic, Edge::getWeight);
    }

    /** Cost functions are sampled once; subsequent traffic updates require a new prepared index. */
    public PreparedShortestPath(Graph graph, boolean useHeuristic, ToDoubleFunction<Edge> costFunction) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(costFunction, "costFunction");
        this.graph = graph.isFrozen() ? graph : graph.snapshot();
        Map<Edge, Integer> indices = new IdentityHashMap<>();
        Map<Edge, Double> edgeCosts = new IdentityHashMap<>();
        Map<String, List<Edge>> adjacency = new HashMap<>();
        Comparator<Edge> order = Comparator.comparing((Edge edge) -> edge.getDestination().getId())
                .thenComparingDouble(Edge::getWeight).thenComparing(Edge::getRoadName);
        for (Node node : this.graph.getAllNodes().stream().sorted(Comparator.comparing(Node::getId)).toList()) {
            List<Edge> edges = this.graph.getNeighbors(node.getId()).stream().sorted(order).toList();
            for (Edge edge : edges) {
                if (!indices.containsKey(edge)) {
                    double cost = costFunction.applyAsDouble(edge);
                    if (!Double.isFinite(cost) || cost < 0) {
                        throw new IllegalArgumentException("Shortest paths require finite nonnegative edge weights");
                    }
                    indices.put(edge, indices.size());
                    edgeCosts.put(edge, cost);
                }
            }
            adjacency.put(node.getId(), edges);
        }
        this.edgeIndices = Collections.unmodifiableMap(indices);
        this.costs = Collections.unmodifiableMap(edgeCosts);
        this.outgoing = Map.copyOf(adjacency);
        this.heuristicScale = useHeuristic ? ShortestPathSupport.heuristicScale(this.graph, this::cost) : 0;
    }

    /** The exact frozen graph whose immutable nodes and edges are returned in results. */
    public Graph graph() { return graph; }

    /** Stable within this prepared graph, including distinct parallel edges with equal values. */
    public int edgeIndex(Edge edge) {
        Integer index = edgeIndices.get(edge);
        if (index == null) throw new IllegalArgumentException("Edge does not belong to the prepared graph");
        return index;
    }

    public double cost(Edge edge) {
        Double cost = costs.get(edge);
        if (cost == null) throw new IllegalArgumentException("Edge does not belong to the prepared graph");
        return cost;
    }

    public AlgorithmResult execute(String startId, String goalId) {
        return search(startId, goalId, Integer.MAX_VALUE).result();
    }

    public AlgorithmResult execute(String startId, String goalId, Set<String> bannedNodes,
                                   Set<Edge> bannedEdges, Predicate<Edge> allowedEdges) {
        return search(startId, goalId, bannedNodes, bannedEdges, allowedEdges, Integer.MAX_VALUE).result();
    }

    public SearchReport search(String startId, String goalId, int traceLimit) {
        return search(startId, goalId, Set.of(), Set.of(), edge -> true, traceLimit);
    }

    /**
     * Banned edges use object identity, so closing one parallel road leaves the
     * others available. traceLimit=0 avoids trace allocation for internal spur searches.
     */
    public SearchReport search(String startId, String goalId, Set<String> bannedNodes,
                               Set<Edge> bannedEdges, Predicate<Edge> allowedEdges, int traceLimit) {
        return search(startId, goalId, bannedNodes, bannedEdges, allowedEdges, traceLimit, SearchBudget.unbounded());
    }

    public SearchReport search(String startId, String goalId, Set<String> bannedNodes,
                               Set<Edge> bannedEdges, Predicate<Edge> allowedEdges, int traceLimit,
                               SearchBudget budget) {
        Objects.requireNonNull(budget, "budget").check();
        Objects.requireNonNull(bannedNodes, "bannedNodes");
        Objects.requireNonNull(bannedEdges, "bannedEdges");
        Objects.requireNonNull(allowedEdges, "allowedEdges");
        if (traceLimit < 0) throw new IllegalArgumentException("traceLimit must not be negative");
        if (startId == null || goalId == null || !graph.hasNode(startId) || !graph.hasNode(goalId)) {
            return report(ShortestPathSupport.empty(AlgorithmResult.Status.INVALID, List.of()), 0, 0);
        }
        if (bannedNodes.contains(startId) || bannedNodes.contains(goalId)) {
            return report(ShortestPathSupport.empty(AlgorithmResult.Status.UNREACHABLE, List.of()), 0, 0);
        }
        if (startId.equals(goalId)) {
            budget.path(1);
            Node node = graph.getNode(startId);
            List<AlgorithmStep> steps = traceLimit == 0 ? List.of() : List.of(new AlgorithmStep(node, 0));
            return report(new AlgorithmResult(AlgorithmResult.Status.START_EQUALS_GOAL,
                    List.of(node), List.of(), 0, steps), 1, 0);
        }
        Set<Edge> closedEdges = Collections.newSetFromMap(new IdentityHashMap<>());
        closedEdges.addAll(bannedEdges);
        Node goal = graph.getNode(goalId);
        Map<String, Double> distances = new HashMap<>();
        Map<String, Edge> previous = new HashMap<>();
        List<AlgorithmStep> steps = new ArrayList<>();
        PriorityQueue<Entry> queue = new PriorityQueue<>(Comparator.comparingDouble(Entry::priority)
                .thenComparing(Comparator.comparingDouble(Entry::cost).reversed())
                .thenComparing(Entry::nodeId));
        distances.put(startId, 0.0);
        queue.add(new Entry(startId, 0, heuristic(graph.getNode(startId), goal)));
        long expanded = 0;
        long examined = 0;
        while (!queue.isEmpty()) {
            budget.work();
            Entry entry = queue.remove();
            if (entry.cost() != distances.getOrDefault(entry.nodeId(), Double.POSITIVE_INFINITY)) continue;
            expanded++;
            Node current = graph.getNode(entry.nodeId());
            if (steps.size() < traceLimit) steps.add(new AlgorithmStep(current, entry.cost()));
            if (entry.nodeId().equals(goalId)) {
                return report(result(startId, goalId, previous, entry.cost(), steps, budget), expanded, examined);
            }
            for (Edge edge : outgoing.get(entry.nodeId())) {
                budget.work();
                examined++;
                String next = edge.getDestination().getId();
                if (bannedNodes.contains(next) || closedEdges.contains(edge) || !allowedEdges.test(edge)) continue;
                double candidate = entry.cost() + cost(edge);
                if (candidate < distances.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    distances.put(next, candidate);
                    previous.put(next, edge);
                    queue.add(new Entry(next, candidate, candidate + heuristic(graph.getNode(next), goal)));
                }
            }
        }
        return report(ShortestPathSupport.empty(AlgorithmResult.Status.UNREACHABLE, steps), expanded, examined);
    }

    private AlgorithmResult result(String start, String goal, Map<String, Edge> previous,
                                   double cost, List<AlgorithmStep> steps, SearchBudget budget) {
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        String current = goal;
        budget.path(1);
        nodes.add(graph.getNode(current));
        while (!current.equals(start)) {
            budget.work();
            budget.path(1);
            Edge edge = previous.get(current);
            if (edge == null || edges.size() >= graph.nodeCount()) {
                return ShortestPathSupport.empty(AlgorithmResult.Status.INVALID, steps);
            }
            edges.add(edge);
            current = edge.getSource().getId();
            nodes.add(graph.getNode(current));
        }
        Collections.reverse(nodes);
        Collections.reverse(edges);
        return new AlgorithmResult(AlgorithmResult.Status.SUCCESS, nodes, edges, cost, steps);
    }

    private double heuristic(Node node, Node goal) {
        return heuristicScale == 0 ? 0 : heuristicScale * ShortestPathSupport.geographicDistance(node, goal);
    }

    private static SearchReport report(AlgorithmResult result, long expanded, long examined) {
        // The geographic heuristic is consistent, so each settled vertex is counted once.
        // Keep complete statistics even when callers retain only a trace prefix (or none).
        AlgorithmResult measured = new AlgorithmResult(result.getStatus(), result.getPath(), result.getEdges(),
                result.getTotalCost(), result.getSteps(), Math.toIntExact(expanded), expanded);
        return new SearchReport(measured, expanded, examined);
    }

    private record Entry(String nodeId, double cost, double priority) { }
}
