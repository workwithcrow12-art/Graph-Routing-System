package graphroute.algorithm.mst;

import graphroute.algorithm.SpanningTreeAlgorithm;
import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.AlgorithmStep;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.Node;
import graphroute.algorithm.shortestpath.SearchBudget;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** Prim's minimum spanning forest. Each disconnected component is processed. */
public class Prim implements SpanningTreeAlgorithm {
    @Override
    public AlgorithmResult execute(Graph graph) {
        return execute(graph, SearchBudget.unbounded());
    }

    public AlgorithmResult execute(Graph graph, SearchBudget budget) {
        budget.check();
        if (!SpanningForest.isValid(graph, budget)) return SpanningForest.invalid();
        List<Node> nodes = SpanningForest.nodes(graph);
        List<Edge> selected = new ArrayList<>();
        List<AlgorithmStep> steps = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        PriorityQueue<Edge> frontier = new PriorityQueue<>(SpanningForest.EDGE_ORDER);
        double cost = 0;
        for (Node root : nodes) {
            budget.work();
            if (!visited.add(root.getId())) continue;
            frontier.addAll(graph.getNeighbors(root.getId()));
            while (!frontier.isEmpty()) {
                budget.work();
                Edge edge = frontier.remove();
                String destination = edge.getDestination().getId();
                if (!visited.add(destination)) continue;
                budget.path(1);
                selected.add(edge);
                cost += edge.getWeight();
                steps.add(new AlgorithmStep(graph.getNode(destination), cost));
                for (Edge next : graph.getNeighbors(destination)) {
                    budget.work();
                    if (!visited.contains(next.getDestination().getId())) frontier.add(next);
                }
            }
        }
        return SpanningForest.result(nodes, selected, cost, steps);
    }
}
