package graphroute.algorithm.traversal;

import graphroute.algorithm.TraversalAlgorithm;
import graphroute.algorithm.AlgorithmResult;
import graphroute.model.Graph;
import graphroute.algorithm.shortestpath.SearchBudget;

/** Breadth-first traversal and minimum-hop routes; route cost sums the actual edge weights. */
public class BFS implements TraversalAlgorithm {
    /** Budgeted analysis: omit startId to visit all components, or goalId for one component. */
    public AlgorithmResult execute(Graph graph, String startId, String goalId, SearchBudget budget) {
        return TraversalSearch.run(graph, startId, goalId, startId == null, false, budget);
    }

    /** Visit all components in node-id order. */
    @Override
    public AlgorithmResult execute(Graph graph) {
        return TraversalSearch.run(graph, null, null, true, false);
    }

    /** Visit the component reachable from startId. Step costs are hop counts. */
    public AlgorithmResult execute(Graph graph, String startId) {
        return TraversalSearch.run(graph, startId, null, false, false);
    }

    @Override
    public AlgorithmResult execute(Graph graph, String startId, String goalId) {
        return goalId == null ? TraversalSearch.invalid()
                : TraversalSearch.run(graph, startId, goalId, false, false);
    }
}
