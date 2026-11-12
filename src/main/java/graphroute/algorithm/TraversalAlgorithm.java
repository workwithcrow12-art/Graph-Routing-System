package graphroute.algorithm;

import graphroute.model.Graph;

/** Deterministic traversal of a graph, a reachable component, or a route search. */
public interface TraversalAlgorithm extends GraphAlgorithm {
    AlgorithmResult execute(Graph graph);
    AlgorithmResult execute(Graph graph, String startId);
    AlgorithmResult execute(Graph graph, String startId, String goalId);
}
