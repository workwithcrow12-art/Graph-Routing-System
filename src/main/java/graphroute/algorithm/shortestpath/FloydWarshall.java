package graphroute.algorithm.shortestpath;

import graphroute.algorithm.ShortestPathAlgorithm;
import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.AlgorithmStep;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes all-pairs distances and returns one requested route. Uses O(V^3) time
 * and O(V^2) memory; Dijkstra and A* are preferable for large road networks.
 */
public class FloydWarshall implements ShortestPathAlgorithm {
    @Override
    public AlgorithmResult execute(Graph graph, String startId, String goalId) {
        return execute(graph, startId, goalId, SearchBudget.unbounded());
    }

    public AlgorithmResult execute(Graph graph, String startId, String goalId, SearchBudget budget) {
        budget.check();
        AlgorithmResult earlyResult = ShortestPathSupport.preflight(graph, startId, goalId);
        if (earlyResult != null) {
            return earlyResult;
        }
        List<Node> nodes = graph.getAllNodes().stream().sorted(Comparator.comparing(Node::getId)).toList();
        int size = nodes.size();
        Map<String, Integer> indices = new HashMap<>();
        double[][] distances = new double[size][size];
        int[][] next = new int[size][size];
        for (int i = 0; i < size; i++) {
            budget.work();
            indices.put(nodes.get(i).getId(), i);
            Arrays.fill(distances[i], Double.POSITIVE_INFINITY);
            Arrays.fill(next[i], -1);
            distances[i][i] = 0;
        }
        for (Edge edge : graph.getAllEdges()) {
            budget.work();
            int source = indices.get(edge.getSource().getId());
            int destination = indices.get(edge.getDestination().getId());
            if (edge.getWeight() < distances[source][destination]) {
                distances[source][destination] = edge.getWeight();
                next[source][destination] = destination;
            }
        }
        int start = indices.get(startId);
        int goal = indices.get(goalId);
        List<AlgorithmStep> steps = new ArrayList<>();
        for (int via = 0; via < size; via++) {
            budget.work();
            for (int source = 0; source < size; source++) {
                budget.work();
                if (!Double.isFinite(distances[source][via])) {
                    continue;
                }
                for (int destination = 0; destination < size; destination++) {
                    budget.work();
                    double candidate = distances[source][via] + distances[via][destination];
                    if (candidate < distances[source][destination]) {
                        distances[source][destination] = candidate;
                        next[source][destination] = next[source][via];
                    }
                }
            }
            if (Double.isFinite(distances[start][via])) {
                steps.add(new AlgorithmStep(nodes.get(via), distances[start][via]));
            }
        }
        if (next[start][goal] == -1) {
            return ShortestPathSupport.empty(AlgorithmResult.Status.UNREACHABLE, steps);
        }
        List<Node> path = new ArrayList<>();
        int current = start;
        path.add(nodes.get(current));
        while (current != goal && path.size() <= size) {
            budget.work();
            budget.path(1);
            current = next[current][goal];
            if (current < 0) {
                return ShortestPathSupport.empty(AlgorithmResult.Status.INVALID, steps);
            }
            path.add(nodes.get(current));
        }
        if (current != goal) {
            return ShortestPathSupport.empty(AlgorithmResult.Status.INVALID, steps);
        }
        return ShortestPathSupport.success(graph, path, distances[start][goal], steps);
    }
}
