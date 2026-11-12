package graphroute.algorithm.shortestpath;

import graphroute.algorithm.AlgorithmResult;
import graphroute.model.Edge;
import graphroute.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/** Yen's algorithm for the first three edge-distinct, loopless routes in nondecreasing cost order. */
public final class YenKShortestPaths {
    private final PreparedShortestPath prepared;

    public YenKShortestPaths(PreparedShortestPath prepared) {
        this.prepared = Objects.requireNonNull(prepared, "prepared");
    }

    public List<AlgorithmResult> execute(String startId, String goalId, int count) {
        return execute(startId, goalId, count, edge -> true);
    }

    public List<AlgorithmResult> execute(String startId, String goalId, int count, Predicate<Edge> allowedEdges) {
        return execute(startId, goalId, count, allowedEdges, SearchBudget.unbounded());
    }

    public List<AlgorithmResult> execute(String startId, String goalId, int count,
                                         Predicate<Edge> allowedEdges, SearchBudget budget) {
        if (count < 1 || count > 3) throw new IllegalArgumentException("count must be between 1 and 3");
        Objects.requireNonNull(allowedEdges, "allowedEdges");
        Objects.requireNonNull(budget, "budget").check();
        AlgorithmResult first = prepared.search(startId, goalId, Set.of(), Set.of(), allowedEdges, 0, budget).result();
        if (first.getStatus() == AlgorithmResult.Status.INVALID) {
            throw new IllegalArgumentException("Start and goal must exist in the prepared graph");
        }
        if (!first.isSuccess()) return List.of();
        if (count == 1) return List.of(first);

        List<AlgorithmResult> accepted = new ArrayList<>();
        accepted.add(first);
        Set<List<Integer>> seen = new HashSet<>();
        seen.add(key(first));
        PriorityQueue<Candidate> candidates = new PriorityQueue<>(Comparator
                .comparingDouble((Candidate candidate) -> candidate.result().getTotalCost())
                .thenComparing(Candidate::key, YenKShortestPaths::compareKeys));

        while (accepted.size() < count) {
            AlgorithmResult previous = accepted.getLast();
            double rootCost = 0;
            Set<String> bannedNodes = new HashSet<>();
            for (int spurIndex = 0; spurIndex < previous.getEdges().size(); spurIndex++) {
                budget.spur();
                String spurNode = previous.getPath().get(spurIndex).getId();
                Set<Edge> bannedEdges = Collections.newSetFromMap(new IdentityHashMap<>());
                for (AlgorithmResult route : accepted) {
                    if (route.getEdges().size() > spurIndex && sameRoot(route, previous, spurIndex, budget)) {
                        bannedEdges.add(route.getEdges().get(spurIndex));
                    }
                }
                AlgorithmResult spur = prepared.search(spurNode, goalId, bannedNodes, bannedEdges, allowedEdges, 0, budget).result();
                if (spur.isSuccess()) {
                    // Charge before copying full paths or allocating a signature, including duplicates.
                    budget.path((long) spurIndex + spur.getPath().size());
                    List<Node> nodes = new ArrayList<>(previous.getPath().subList(0, spurIndex));
                    nodes.addAll(spur.getPath());
                    List<Edge> edges = new ArrayList<>(previous.getEdges().subList(0, spurIndex));
                    edges.addAll(spur.getEdges());
                    AlgorithmResult candidate = new AlgorithmResult(AlgorithmResult.Status.SUCCESS,
                            nodes, edges, rootCost + spur.getTotalCost(), List.of());
                    List<Integer> signature = key(candidate);
                    if (seen.add(signature)) candidates.add(new Candidate(candidate, signature));
                }
                bannedNodes.add(spurNode); // A spur cannot revisit any earlier root vertex.
                rootCost += prepared.cost(previous.getEdges().get(spurIndex));
            }
            if (candidates.isEmpty()) break;
            accepted.add(candidates.remove().result());
        }
        budget.check();
        return List.copyOf(accepted);
    }

    private static boolean sameRoot(AlgorithmResult first, AlgorithmResult second, int edgeCount, SearchBudget budget) {
        for (int index = 0; index < edgeCount; index++) {
            budget.work();
            if (first.getEdges().get(index) != second.getEdges().get(index)) return false;
        }
        return true;
    }

    private List<Integer> key(AlgorithmResult route) {
        return route.getEdges().stream().map(prepared::edgeIndex).toList();
    }

    private static int compareKeys(List<Integer> first, List<Integer> second) {
        for (int index = 0; index < Math.min(first.size(), second.size()); index++) {
            int compared = Integer.compare(first.get(index), second.get(index));
            if (compared != 0) return compared;
        }
        return Integer.compare(first.size(), second.size());
    }

    private record Candidate(AlgorithmResult result, List<Integer> key) { }
}
