package graphroute.algorithm;

import graphroute.model.Node;
import graphroute.model.Edge;
import java.util.*;

/**
 * An algorithm's output. Path is a route for point-to-point searches, traversal
 * order for traversals, and the covered vertices for spanning forests. The lists
 * and their node/edge values are immutable snapshots of this execution.
 */
public final class AlgorithmResult {
    public enum Status { SUCCESS, START_EQUALS_GOAL, UNREACHABLE, INVALID, PARTIAL, NEGATIVE_CYCLE }
    private final Status status;
    private final List<Node> path;
    private final List<Edge> edges;
    private final double totalCost;
    private final List<AlgorithmStep> steps;
    private final int traceStepCount;
    private final long visitedNodeCount;
    public AlgorithmResult(Status status, List<Node> path, double totalCost, List<AlgorithmStep> steps) {
        this(status, path, Collections.emptyList(), totalCost, steps);
    }
    public AlgorithmResult(Status status, List<Node> path, List<Edge> edges,
                           double totalCost, List<AlgorithmStep> steps) {
        this(status, path, edges, totalCost, steps, steps.size(),
                steps.stream().map(step -> step.getNode().getId()).distinct().count());
    }
    /** Total trace statistics may exceed the retained prefix when recording is bounded. */
    public AlgorithmResult(Status status, List<Node> path, List<Edge> edges,
                           double totalCost, List<AlgorithmStep> steps,
                           int traceStepCount, long visitedNodeCount) {
        if (traceStepCount < steps.size() || visitedNodeCount < 0 || visitedNodeCount > traceStepCount) {
            throw new IllegalArgumentException("Invalid trace statistics");
        }
        this.status = Objects.requireNonNull(status, "status");
        this.path = List.copyOf(path);
        this.edges = List.copyOf(edges);
        this.totalCost = totalCost;
        this.steps = List.copyOf(steps);
        this.traceStepCount = traceStepCount;
        this.visitedNodeCount = visitedNodeCount;
    }
    public Status getStatus() { return status; }
    public boolean isSuccess() { return status == Status.SUCCESS || status == Status.START_EQUALS_GOAL; }
    public List<Node> getPath() { return path; }
    public List<Edge> getEdges() { return edges; }
    public double getTotalCost() { return totalCost; }
    public double getCost() { return totalCost; }
    public List<AlgorithmStep> getSteps() { return steps; }
    public int getTraceStepCount() { return traceStepCount; }
    public long getVisitedNodeCount() { return visitedNodeCount; }
}
