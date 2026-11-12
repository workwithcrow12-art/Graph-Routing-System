package graphroute.algorithm.shortestpath;

import graphroute.model.Graph;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small revision-aware LRU; bounds retained snapshots while allowing alternating datasets. */
final class PreparedPathCache {
    private record Key(Graph graph, boolean heuristic) { }
    private record Entry(long revision, PreparedShortestPath path) { }
    private final Map<Key, Entry> entries = new LinkedHashMap<>(8, .75f, true);
    synchronized PreparedShortestPath get(Graph graph, boolean heuristic) {
        if (graph == null) throw new IllegalArgumentException("graph is required");
        Key key = new Key(graph, heuristic);
        Entry entry = entries.get(key);
        if (entry == null || entry.revision() != graph.getRevision()) {
            entry = new Entry(graph.getRevision(), new PreparedShortestPath(graph, heuristic));
            entries.put(key, entry);
            if (entries.size() > 4) entries.remove(entries.keySet().iterator().next());
        }
        return entry.path();
    }
}
