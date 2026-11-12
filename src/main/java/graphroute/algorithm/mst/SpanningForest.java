package graphroute.algorithm.mst;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.AlgorithmStep;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import graphroute.algorithm.shortestpath.SearchBudget;

import java.util.Comparator;
import java.util.List;

final class SpanningForest {
    static final Comparator<Edge> EDGE_ORDER = Comparator.comparingDouble(Edge::getWeight)
            .thenComparing(edge -> edge.getSource().getId())
            .thenComparing(edge -> edge.getDestination().getId())
            .thenComparing(Edge::getRoadName);

    private SpanningForest() { }

    static boolean isValid(Graph graph, SearchBudget budget) {
        return graph != null && !graph.isEmpty() && graph.getType() == GraphType.UNDIRECTED
                && graph.getAllEdges().stream()
                .peek(edge -> budget.work())
                .allMatch(edge -> Double.isFinite(edge.getWeight()) && edge.getWeight() >= 0);
    }

    static List<Node> nodes(Graph graph) {
        return graph.getAllNodes().stream().sorted(Comparator.comparing(Node::getId)).toList();
    }

    static AlgorithmResult result(List<Node> nodes, List<Edge> edges, double cost, List<AlgorithmStep> steps) {
        if (!Double.isFinite(cost)) return invalid();
        AlgorithmResult.Status status = edges.size() == nodes.size() - 1
                ? AlgorithmResult.Status.SUCCESS : AlgorithmResult.Status.PARTIAL;
        return new AlgorithmResult(status, nodes, edges, cost, steps);
    }

    static AlgorithmResult invalid() {
        return new AlgorithmResult(AlgorithmResult.Status.INVALID, List.of(),
                Double.POSITIVE_INFINITY, List.of());
    }
}
