package graphroute.algorithm.shortestpath;

import graphroute.algorithm.ShortestPathAlgorithm;
import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.BoundedTrace;
import graphroute.model.Edge;
import graphroute.model.Graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.function.ToDoubleFunction;

/** Bellman-Ford with optional signed analysis costs, independent of physical road distances. */
public class BellmanFord implements ShortestPathAlgorithm {
    @Override
    public AlgorithmResult execute(Graph graph, String startId, String goalId) {
        return execute(graph, startId, goalId, Integer.MAX_VALUE);
    }

    /** Bounds retained trace allocations, without limiting relaxation or changing route results. */
    public AlgorithmResult execute(Graph graph, String startId, String goalId, int maxTraceSteps) {
        return execute(graph, startId, goalId, Edge::getWeight, maxTraceSteps);
    }

    public AlgorithmResult execute(Graph graph, String startId, String goalId, int maxTraceSteps, SearchBudget budget) {
        return execute(graph, startId, goalId, Edge::getWeight, maxTraceSteps, budget);
    }

    /** Signed costs permit negative edges without giving physical roads negative distances.
     * A reachable negative cycle returns NEGATIVE_CYCLE with no claimed shortest path.
     */
    public AlgorithmResult execute(Graph graph, String startId, String goalId,
                                   ToDoubleFunction<Edge> cost, int maxTraceSteps) {
        return execute(graph, startId, goalId, cost, maxTraceSteps, SearchBudget.unbounded());
    }

    public AlgorithmResult execute(Graph graph, String startId, String goalId,
                                   ToDoubleFunction<Edge> cost, int maxTraceSteps, SearchBudget budget) {
        budget.check();
        BoundedTrace trace = new BoundedTrace(maxTraceSteps);
        if (graph == null || startId == null || goalId == null || !graph.hasNode(startId) || !graph.hasNode(goalId)) {
            return trace.finish(ShortestPathSupport.empty(AlgorithmResult.Status.INVALID, List.of()));
        }
        Map<Edge, Double> costs = new IdentityHashMap<>();
        for (Edge edge : graph.getAllEdges()) {
            budget.work();
            double value = cost.applyAsDouble(edge);
            if (!Double.isFinite(value)) return trace.finish(ShortestPathSupport.empty(AlgorithmResult.Status.INVALID, List.of()));
            costs.put(edge, value);
        }
        Map<String, Double> distances = new HashMap<>();
        Map<String, Edge> previous = new HashMap<>();
        List<Edge> edges = new ArrayList<>(graph.getAllEdges());
        edges.sort(Comparator.comparing((Edge edge) -> edge.getSource().getId())
                .thenComparing(edge -> edge.getDestination().getId())
                .thenComparingDouble(Edge::getWeight));
        distances.put(startId, 0.0);
        trace.record(graph.getNode(startId), 0);

        for (int pass = 1; pass < graph.nodeCount(); pass++) {
            boolean changed = false;
            for (Edge edge : edges) {
                budget.work();
                String sourceId = edge.getSource().getId();
                String destinationId = edge.getDestination().getId();
                double candidate = distances.getOrDefault(sourceId, Double.POSITIVE_INFINITY) + costs.get(edge);
                if (candidate < distances.getOrDefault(destinationId, Double.POSITIVE_INFINITY)) {
                    distances.put(destinationId, candidate);
                    previous.put(destinationId, edge);
                    trace.record(graph.getNode(destinationId), candidate);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        for (Edge edge : edges) {
            budget.work();
            if (distances.getOrDefault(edge.getSource().getId(), Double.POSITIVE_INFINITY) + costs.get(edge)
                    < distances.getOrDefault(edge.getDestination().getId(), Double.POSITIVE_INFINITY)) {
                return trace.finish(ShortestPathSupport.empty(AlgorithmResult.Status.NEGATIVE_CYCLE, trace.steps()));
            }
        }
        double total = distances.getOrDefault(goalId, Double.POSITIVE_INFINITY);
        if (!Double.isFinite(total)) return trace.finish(ShortestPathSupport.empty(AlgorithmResult.Status.UNREACHABLE, trace.steps()));
        List<Edge> pathEdges = new ArrayList<>();
        String current = goalId;
        while (!current.equals(startId)) {
            budget.work();
            budget.path(1);
            Edge edge = previous.get(current);
            if (edge == null || pathEdges.size() >= graph.nodeCount()) return trace.finish(ShortestPathSupport.empty(AlgorithmResult.Status.INVALID, trace.steps()));
            pathEdges.add(edge);
            current = edge.getSource().getId();
        }
        Collections.reverse(pathEdges);
        List<graphroute.model.Node> path = new ArrayList<>();
        path.add(graph.getNode(startId));
        pathEdges.forEach(edge -> path.add(edge.getDestination()));
        return trace.finish(new AlgorithmResult(startId.equals(goalId) ? AlgorithmResult.Status.START_EQUALS_GOAL : AlgorithmResult.Status.SUCCESS,
                path, pathEdges, total, trace.steps()));
    }
}
