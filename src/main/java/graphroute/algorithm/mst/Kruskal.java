package graphroute.algorithm.mst;

import graphroute.algorithm.SpanningTreeAlgorithm;
import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.AlgorithmStep;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.Node;
import graphroute.algorithm.shortestpath.SearchBudget;

import java.util.ArrayList;
import java.util.List;

/** Kruskal's minimum spanning tree, or a minimum spanning forest when disconnected. */
public class Kruskal implements SpanningTreeAlgorithm {
    @Override
    public AlgorithmResult execute(Graph graph) {
        return execute(graph, SearchBudget.unbounded());
    }

    public AlgorithmResult execute(Graph graph, SearchBudget budget) {
        budget.check();
        if (!SpanningForest.isValid(graph, budget)) return SpanningForest.invalid();
        List<Node> nodes = SpanningForest.nodes(graph);
        // Every undirected edge has a reversed copy. Keep one orientation and
        // preserve parallel roads with different weights.
        List<Edge> candidates = graph.getAllEdges().stream()
                .peek(edge -> budget.work())
                .filter(edge -> edge.getSource().getId().compareTo(edge.getDestination().getId()) < 0)
                .sorted(SpanningForest.EDGE_ORDER).toList();
        UnionFind sets = new UnionFind(nodes);
        List<Edge> selected = new ArrayList<>();
        List<AlgorithmStep> steps = new ArrayList<>();
        double cost = 0;
        for (Edge edge : candidates) {
            budget.work();
            if (!sets.union(edge.getSource().getId(), edge.getDestination().getId())) continue;
            budget.path(1);
            selected.add(edge);
            cost += edge.getWeight();
            steps.add(new AlgorithmStep(graph.getNode(edge.getDestination().getId()), cost));
            if (selected.size() == nodes.size() - 1) break;
        }
        return SpanningForest.result(nodes, selected, cost, steps);
    }
}
