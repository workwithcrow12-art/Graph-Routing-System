package graphroute.analysis;

import graphroute.algorithm.AlgorithmResult;

import java.util.List;

/** Serializable, immutable execution output with no graph/model object references. */
public record AlgorithmResponse(String algorithm, AlgorithmResult.Status status,
                                List<String> nodeIds, List<EdgeDto> edges, Double totalCost,
                                List<TraceStep> trace, Metrics metrics, String message) {
    public AlgorithmResponse {
        nodeIds = List.copyOf(nodeIds);
        edges = List.copyOf(edges);
        trace = List.copyOf(trace);
    }

    public record EdgeDto(String sourceId, String destinationId, double weight, String roadName) { }

    /** Cost meaning is described by AlgorithmDescriptor; nonfinite values are serialized as null. */
    public record TraceStep(int index, String event, String nodeId, Double cost, EdgeDto edge) { }

    /** graphEdges uses Graph.edgeCount semantics; graphArcs counts directed adjacency entries.
     * visitedNodes counts explored vertices, or all covered forest vertices for MST (including roots).
     */
    public record Metrics(int graphNodes, int graphEdges, int graphArcs,
                          long visitedNodes, int resultEdges, int traceStepCount,
                          long elapsedNanos, boolean traceTruncated) { }
}
