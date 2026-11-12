package graphroute.algorithm.shortestpath;

import graphroute.algorithm.ShortestPathAlgorithm;
import graphroute.algorithm.AlgorithmResult;
import graphroute.model.Graph;

/** Finds a minimum-weight route on a graph with finite, nonnegative edge weights. */
public class Dijkstra implements ShortestPathAlgorithm {
    private final PreparedPathCache cache = new PreparedPathCache();

    public synchronized PreparedShortestPath prepare(Graph graph) {
        return cache.get(graph, false);
    }

    @Override
    public AlgorithmResult execute(Graph graph, String startId, String goalId) {
        if (graph == null) return ShortestPathSupport.empty(AlgorithmResult.Status.INVALID, java.util.List.of());
        return prepare(graph).execute(startId, goalId);
    }
}
