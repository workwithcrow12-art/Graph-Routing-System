package graphroute.analysis;

/** Discoverable execution capabilities and interpretation of result/trace costs. */
public record AlgorithmDescriptor(String id, String name, String category,
                                  boolean requiresStart, boolean requiresGoal,
                                  boolean requiresUndirectedGraph, int maxNodes,
                                  String resultMeaning, String traceCostMeaning) { }
