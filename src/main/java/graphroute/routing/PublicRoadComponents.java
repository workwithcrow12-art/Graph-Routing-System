package graphroute.routing;

import graphroute.model.Edge;
import graphroute.model.Graph;
import java.util.*;

/** Iterative Kosaraju traversal; safe for networks deeper than the Java call stack. */
final class PublicRoadComponents {
    private record Visit(String id, boolean finished) { }

    static Set<String> largest(Graph graph, Edge.Mode mode) {
        Map<String, List<String>> forward = new TreeMap<>(), reverse = new HashMap<>();
        for (Edge edge : graph.getAllEdges()) {
            if (!edge.allows(mode) || edge.getAccess(mode) != Edge.Access.PUBLIC) continue;
            String from = edge.getSource().getId(), to = edge.getDestination().getId();
            forward.computeIfAbsent(from, key -> new ArrayList<>()).add(to);
            forward.computeIfAbsent(to, key -> new ArrayList<>());
            reverse.computeIfAbsent(to, key -> new ArrayList<>()).add(from);
        }
        Set<String> seen = new HashSet<>();
        List<String> order = new ArrayList<>();
        Deque<Visit> visits = new ArrayDeque<>();
        for (String root : forward.keySet()) {
            visits.push(new Visit(root, false));
            while (!visits.isEmpty()) {
                Visit visit = visits.pop();
                if (visit.finished()) { order.add(visit.id()); continue; }
                if (!seen.add(visit.id())) continue;
                visits.push(new Visit(visit.id(), true));
                for (String next : forward.get(visit.id())) visits.push(new Visit(next, false));
            }
        }
        seen.clear();
        Set<String> largest = Set.of();
        Deque<String> pending = new ArrayDeque<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            String root = order.get(i);
            if (!seen.add(root)) continue;
            Set<String> component = new HashSet<>();
            pending.push(root);
            while (!pending.isEmpty()) {
                String id = pending.pop();
                component.add(id);
                for (String next : reverse.getOrDefault(id, List.of())) if (seen.add(next)) pending.push(next);
            }
            if (component.size() > largest.size()) largest = component;
        }
        return Set.copyOf(largest);
    }
}
