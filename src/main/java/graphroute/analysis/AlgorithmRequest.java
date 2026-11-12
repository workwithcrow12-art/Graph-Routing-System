package graphroute.analysis;

/** Transport-neutral analysis input. Null trace limit uses the service default; zero disables output tracing. */
public record AlgorithmRequest(String algorithm, String startId, String goalId, Integer maxTraceSteps,
                               String graph, java.util.List<String> nodeIds, Boolean undirected) {
    public AlgorithmRequest(String algorithm, String startId, String goalId, Integer maxTraceSteps) {
        this(algorithm, startId, goalId, maxTraceSteps, null, null, null);
    }
    public AlgorithmRequest(String algorithm, String startId, String goalId) {
        this(algorithm, startId, goalId, null);
    }
}
