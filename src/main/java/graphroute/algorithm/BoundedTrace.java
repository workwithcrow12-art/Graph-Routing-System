package graphroute.algorithm;

import graphroute.model.Node;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Retains only a trace prefix while counting all events and distinct visited nodes. */
public final class BoundedTrace {
    private final int limit;
    private final List<AlgorithmStep> steps = new ArrayList<>();
    private final Set<String> visited = new HashSet<>();
    private int count;

    public BoundedTrace(int limit) {
        if (limit < 0) throw new IllegalArgumentException("Trace limit must be nonnegative");
        this.limit = limit;
    }

    public void record(Node node, double cost) {
        count++;
        visited.add(node.getId());
        if (steps.size() < limit) steps.add(new AlgorithmStep(node, cost));
    }

    public List<AlgorithmStep> steps() { return List.copyOf(steps); }

    /** Attach full statistics to an otherwise complete route result. */
    public AlgorithmResult finish(AlgorithmResult result) {
        return new AlgorithmResult(result.getStatus(), result.getPath(), result.getEdges(),
                result.getCost(), steps, count, visited.size());
    }
}
