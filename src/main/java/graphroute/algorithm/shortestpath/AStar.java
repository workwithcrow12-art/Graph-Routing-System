package graphroute.algorithm.shortestpath;

import graphroute.algorithm.ShortestPathAlgorithm;
import graphroute.algorithm.AlgorithmResult;
import graphroute.model.Graph;

/**
 * A* routing with a geographic lower bound calibrated to the graph's edge weights.
 * Raw straight-line kilometres would overestimate costs on graphs whose weights
 * use another unit or whose imported road distances are below that distance.
 */
public class AStar implements ShortestPathAlgorithm {
    private final PreparedPathCache cache = new PreparedPathCache();

    /** Reuses the prepared snapshot until the source graph's structure changes. */
    public synchronized PreparedShortestPath prepare(Graph graph) {
        return cache.get(graph, true);
    }

    @Override
    public AlgorithmResult execute(Graph graph, String startId, String goalId) {
        if (graph == null) return ShortestPathSupport.empty(AlgorithmResult.Status.INVALID, java.util.List.of());
        return prepare(graph).execute(startId, goalId);
    }
}
