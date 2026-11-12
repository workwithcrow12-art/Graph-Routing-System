package graphroute.algorithm.traversal;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.AlgorithmStep;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.Node;
import graphroute.algorithm.shortestpath.SearchBudget;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared mechanics; the queue discipline determines breadth-first/depth-first order. */
final class TraversalSearch {
    private static final Comparator<Edge> EDGE_ORDER = Comparator
            .comparing((Edge edge) -> edge.getDestination().getId())
            .thenComparingDouble(Edge::getWeight)
            .thenComparing(Edge::getRoadName);

    private TraversalSearch() { }

    static AlgorithmResult run(Graph graph, String startId, String goalId,
                               boolean allComponents, boolean depthFirst) {
        return run(graph, startId, goalId, allComponents, depthFirst, SearchBudget.unbounded());
    }

    static AlgorithmResult run(Graph graph, String startId, String goalId,
                               boolean allComponents, boolean depthFirst, SearchBudget budget) {
        budget.check();
        if (graph == null || graph.isEmpty()
                || (!allComponents && (startId == null || !graph.hasNode(startId)))
                || (goalId != null && (startId == null || !graph.hasNode(goalId)))) {
            return invalid();
        }
        for (Edge edge : graph.getAllEdges()) {
            budget.work();
            if (!Double.isFinite(edge.getWeight()) || edge.getWeight() < 0) return invalid();
        }

        List<Node> roots = allComponents ? graph.getAllNodes().stream()
                .sorted(Comparator.comparing(Node::getId)).toList() : List.of(graph.getNode(startId));
        List<Node> order = new ArrayList<>();
        List<Edge> treeEdges = new ArrayList<>();
        List<AlgorithmStep> steps = new ArrayList<>();
        Map<String, Edge> incoming = new HashMap<>();
        Set<String> visited = new HashSet<>();
        double treeCost = 0;
        for (Node root : roots) {
            budget.work();
            if (visited.contains(root.getId())) continue;
            Deque<Visit> pending = new ArrayDeque<>();
            pending.add(new Visit(root.getId(), null, 0));
            while (!pending.isEmpty()) {
                budget.work();
                Visit next = depthFirst ? pending.removeLast() : pending.removeFirst();
                if (!visited.add(next.nodeId())) continue;
                Node node = graph.getNode(next.nodeId());
                budget.path(1);
                order.add(node);
                steps.add(new AlgorithmStep(node, next.depth()));
                if (next.edge() != null) {
                    incoming.put(next.nodeId(), next.edge());
                    treeEdges.add(next.edge());
                    treeCost += next.edge().getWeight();
                }
                if (next.nodeId().equals(goalId)) {
                    return route(graph, startId, goalId, incoming, steps, budget);
                }
                List<Edge> neighbors = new ArrayList<>(graph.getNeighbors(next.nodeId()));
                neighbors.sort(EDGE_ORDER);
                if (depthFirst) Collections.reverse(neighbors);
                for (Edge edge : neighbors) {
                    budget.work();
                    if (!visited.contains(edge.getDestination().getId())) {
                        pending.addLast(new Visit(edge.getDestination().getId(), edge, next.depth() + 1));
                    }
                }
            }
        }
        if (goalId != null) {
            return new AlgorithmResult(AlgorithmResult.Status.UNREACHABLE, List.of(),
                    Double.POSITIVE_INFINITY, steps);
        }
        if (!Double.isFinite(treeCost)) return invalid();
        return new AlgorithmResult(AlgorithmResult.Status.SUCCESS, order, treeEdges, treeCost, steps);
    }

    static AlgorithmResult invalid() {
        return new AlgorithmResult(AlgorithmResult.Status.INVALID, List.of(),
                Double.POSITIVE_INFINITY, List.of());
    }

    private static AlgorithmResult route(Graph graph, String startId, String goalId,
                                         Map<String, Edge> incoming, List<AlgorithmStep> steps, SearchBudget budget) {
        List<Node> path = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        String current = goalId;
        while (!current.equals(startId)) {
            budget.work();
            budget.path(1);
            path.add(graph.getNode(current));
            Edge edge = incoming.get(current);
            edges.add(edge);
            current = edge.getSource().getId();
        }
        path.add(graph.getNode(startId));
        Collections.reverse(path);
        Collections.reverse(edges);
        double cost = edges.stream().mapToDouble(Edge::getWeight).sum();
        if (!Double.isFinite(cost)) return invalid();
        AlgorithmResult.Status status = startId.equals(goalId)
                ? AlgorithmResult.Status.START_EQUALS_GOAL : AlgorithmResult.Status.SUCCESS;
        return new AlgorithmResult(status, path, edges, cost, steps);
    }

    private record Visit(String nodeId, Edge edge, int depth) { }
}
