package graphroute.algorithm;

import graphroute.model.Graph;

/** A weighted shortest-route algorithm. Implementations do not mutate their input. */
@FunctionalInterface
public interface ShortestPathAlgorithm extends GraphAlgorithm {
    AlgorithmResult execute(Graph graph, String startId, String goalId);
}
