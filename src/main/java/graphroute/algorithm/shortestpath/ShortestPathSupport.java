package graphroute.algorithm.shortestpath;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.AlgorithmStep;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

final class ShortestPathSupport {
    private ShortestPathSupport() { }

    static AlgorithmResult preflight(Graph graph, String startId, String goalId) {
        if (graph == null || startId == null || goalId == null
                || !graph.hasNode(startId) || !graph.hasNode(goalId)) {
            return empty(AlgorithmResult.Status.INVALID, List.of());
        }
        for (Edge edge : graph.getAllEdges()) {
            if (!Double.isFinite(edge.getWeight()) || edge.getWeight() < 0) {
                return empty(AlgorithmResult.Status.INVALID, List.of());
            }
        }
        if (startId.equals(goalId)) {
            Node start = graph.getNode(startId);
            return new AlgorithmResult(AlgorithmResult.Status.START_EQUALS_GOAL,
                    List.of(start), 0, List.of(new AlgorithmStep(start, 0)));
        }
        return null;
    }

    static AlgorithmResult empty(AlgorithmResult.Status status, List<AlgorithmStep> steps) {
        return new AlgorithmResult(status, List.of(), Double.POSITIVE_INFINITY, steps);
    }

    static AlgorithmResult success(Graph graph, List<Node> path, double cost, List<AlgorithmStep> steps) {
        List<Edge> edges = new ArrayList<>();
        for (int index = 0; index + 1 < path.size(); index++) {
            String destinationId = path.get(index + 1).getId();
            Edge edge = graph.getNeighbors(path.get(index).getId()).stream()
                    .filter(candidate -> candidate.getDestination().getId().equals(destinationId))
                    .min(Comparator.comparingDouble(Edge::getWeight).thenComparing(Edge::getRoadName))
                    .orElseThrow();
            edges.add(edge);
        }
        return new AlgorithmResult(AlgorithmResult.Status.SUCCESS, path, edges, cost, steps);
    }

    static double geographicDistance(Node first, Node second) {
        double latitudeDelta = Math.toRadians(second.getLat() - first.getLat());
        double longitudeDelta = Math.toRadians(second.getLng() - first.getLng());
        double sinLatitude = Math.sin(latitudeDelta / 2);
        double sinLongitude = Math.sin(longitudeDelta / 2);
        double a = sinLatitude * sinLatitude + Math.cos(Math.toRadians(first.getLat()))
                * Math.cos(Math.toRadians(second.getLat())) * sinLongitude * sinLongitude;
        // Roundoff near antipodal points can otherwise make sqrt(1 - a) NaN.
        a = Math.max(0, Math.min(1, a));
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static double heuristicScale(Graph graph, ToDoubleFunction<Edge> cost) {
        for (Node node : graph.getAllNodes()) {
            if (!Double.isFinite(node.getLat()) || !Double.isFinite(node.getLng())
                    || Math.abs(node.getLat()) > 90 || Math.abs(node.getLng()) > 180) {
                return 0;
            }
        }
        double scale = Double.POSITIVE_INFINITY;
        for (Edge edge : graph.getAllEdges()) {
            double distance = geographicDistance(graph.getNode(edge.getSource().getId()),
                    graph.getNode(edge.getDestination().getId()));
            if (distance > 0) {
                scale = Math.min(scale, cost.applyAsDouble(edge) / distance);
            }
        }
        // Every edge must cost at least scale * straightLine(edge endpoints).
        // A small margin protects this lower bound against floating-point rounding.
        return Double.isFinite(scale) ? scale * (1 - 1e-12) : 0;
    }
}
