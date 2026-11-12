package graphroute.algorithm;

import graphroute.model.Node;

public final class AlgorithmStep {
    private final Node node;
    private final double cost;
    public AlgorithmStep(Node node, double cost) { this.node = java.util.Objects.requireNonNull(node, "node"); this.cost = cost; }
    public Node getNode() { return node; }
    public double getCost() { return cost; }
}
