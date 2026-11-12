package graphroute.algorithm.traversal;

import graphroute.algorithm.TraversalAlgorithm;
import graphroute.algorithm.AlgorithmResult;
import graphroute.model.Graph;
import graphroute.algorithm.shortestpath.SearchBudget;

/** Iterative depth-first traversal; returned routes are reachable paths, not shortest paths. */
public class DFS implements TraversalAlgorithm {
    /** Budgeted analysis: omit startId to visit all components, or goalId for one component. */
    public AlgorithmResult execute(Graph graph, String startId, String goalId, SearchBudget budget) {
        return TraversalSearch.run(graph, startId, goalId, startId == null, true, budget);
    }

    /** Visit all components in node-id order. */
    @Override
    public AlgorithmResult execute(Graph graph) {
        return TraversalSearch.run(graph, null, null, true, true);
    }

    /** Visit the component reachable from startId. Step costs are tree depths. */
    public AlgorithmResult execute(Graph graph, String startId) {
        return TraversalSearch.run(graph, startId, null, false, true);
    }

    @Override
    public AlgorithmResult execute(Graph graph, String startId, String goalId) {
        return goalId == null ? TraversalSearch.invalid()
                : TraversalSearch.run(graph, startId, goalId, false, true);
    }
}
