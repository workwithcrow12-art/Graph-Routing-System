package graphroute.algorithm;

import graphroute.model.Graph;

/** Minimum spanning forest of an undirected graph. */
@FunctionalInterface
public interface SpanningTreeAlgorithm extends GraphAlgorithm {
    AlgorithmResult execute(Graph graph);
}
