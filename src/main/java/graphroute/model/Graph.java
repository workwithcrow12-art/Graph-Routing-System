package graphroute.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Road-network builder. freeze/snapshot provide immutable topology for concurrent routing. */
public final class Graph {
    private final GraphType type;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, List<Edge>> adjacency = new LinkedHashMap<>();
    private Map<String, String> provenance = Map.of();
    private boolean frozen;
    private long revision;
    private List<Edge> frozenEdges;

    public Graph(GraphType type) { this.type = type == null ? GraphType.UNDIRECTED : type; }
    public Graph() { this(GraphType.UNDIRECTED); }

    public void addNode(Node node) {
        requireMutable();
        Objects.requireNonNull(node, "node");
        Node existing = nodes.get(node.getId());
        if (existing != null) {
            if (!sameNode(existing, node)) throw new IllegalArgumentException("Conflicting node id: " + node.getId());
            return;
        }
        nodes.put(node.getId(), node);
        adjacency.put(node.getId(), new ArrayList<>());
        revision++;
    }

    public boolean removeNode(String id) {
        requireMutable();
        if (!nodes.containsKey(id)) return false;
        nodes.remove(id);
        adjacency.remove(id);
        adjacency.values().forEach(edges -> edges.removeIf(edge -> edge.getDestination().getId().equals(id)));
        revision++;
        return true;
    }

    public Node getNode(String id) { return nodes.get(id); }
    public boolean hasNode(String id) { return nodes.containsKey(id); }
    public Collection<Node> getAllNodes() { return Collections.unmodifiableCollection(nodes.values()); }
    public int nodeCount() { return nodes.size(); }

    public void addEdge(Edge edge) {
        requireMutable();
        Objects.requireNonNull(edge, "edge");
        if (!sameNode(nodes.get(edge.getSource().getId()), edge.getSource())
                || !sameNode(nodes.get(edge.getDestination().getId()), edge.getDestination())) {
            throw new IllegalArgumentException("Edge endpoints must match nodes registered in the graph");
        }
        adjacency.get(edge.getSource().getId()).add(edge);
        if (type == GraphType.UNDIRECTED) adjacency.get(edge.getDestination().getId()).add(edge.reverse());
        revision++;
    }

    /** Endpoint-only removal requires one logical road; use a selector or removeEdges for parallels. */
    public boolean removeEdge(String sourceId, String destinationId) {
        requireMutable();
        List<Edge> matches = getNeighbors(sourceId).stream()
                .filter(edge -> edge.getDestination().getId().equals(destinationId)).toList();
        int arcsPerRoad = type == GraphType.UNDIRECTED && Objects.equals(sourceId, destinationId) ? 2 : 1;
        if (matches.size() > arcsPerRoad)
            throw new IllegalArgumentException("Ambiguous parallel edges; select an Edge or use removeEdges");
        Edge selected = matches.isEmpty() ? null : matches.getFirst();
        return selected != null && removeEdge(selected);
    }

    /** Removes one matching road, including exactly one reverse arc for an undirected graph. */
    public boolean removeEdge(Edge edge) {
        requireMutable();
        Objects.requireNonNull(edge, "edge");
        List<Edge> edges = adjacency.get(edge.getSource().getId());
        boolean removed = edges != null && edges.remove(edge);
        if (type == GraphType.UNDIRECTED) {
            List<Edge> reverse = adjacency.get(edge.getDestination().getId());
            if (removed && reverse != null) reverse.remove(edge.reverse());
        }
        if (removed) revision++;
        return removed;
    }

    /** Explicit bulk removal for clients that want to remove every parallel road. */
    public int removeEdges(String sourceId, String destinationId) {
        requireMutable();
        int removed = 0;
        for (Edge edge : List.copyOf(getNeighbors(sourceId))) {
            if (edge.getDestination().getId().equals(destinationId) && removeEdge(edge)) removed++;
        }
        return removed;
    }

    public List<Edge> getNeighbors(String id) {
        List<Edge> edges = adjacency.get(id);
        return edges == null ? List.of() : Collections.unmodifiableList(edges);
    }

    public boolean hasEdge(String sourceId, String destinationId) {
        return getNeighbors(sourceId).stream().anyMatch(edge -> edge.getDestination().getId().equals(destinationId));
    }

    public List<Edge> getAllEdges() {
        if (frozen) return frozenEdges;
        return adjacency.values().stream().flatMap(Collection::stream).toList();
    }

    public int edgeCount() {
        int arcs = adjacency.values().stream().mapToInt(List::size).sum();
        return type == GraphType.UNDIRECTED ? arcs / 2 : arcs;
    }

    public GraphType getType() { return type; }
    public boolean isEmpty() { return nodes.isEmpty(); }
    public long getRevision() { return revision; }
    public boolean isFrozen() { return frozen; }
    public Map<String, String> getProvenance() { return provenance; }

    public void setProvenance(Map<String, String> provenance) {
        requireMutable();
        this.provenance = Map.copyOf(Objects.requireNonNull(provenance));
        revision++;
    }

    /** Freezes this builder permanently; all returned values and collections are immutable. */
    public Graph freeze() {
        if (!frozen) {
            frozenEdges = getAllEdges();
            frozen = true;
        }
        return this;
    }

    /** Copies topology while retaining safe immutable Node/Edge identities. */
    public Graph snapshot() {
        if (frozen) return this;
        Graph copy = new Graph(type);
        copy.nodes.putAll(nodes);
        adjacency.forEach((id, edges) -> copy.adjacency.put(id, new ArrayList<>(edges)));
        copy.provenance = provenance;
        copy.revision = revision;
        return copy.freeze();
    }

    private void requireMutable() {
        if (frozen) throw new IllegalStateException("Graph is frozen");
    }

    private static boolean sameNode(Node a, Node b) {
        return a != null && a.getId().equals(b.getId()) && a.getName().equals(b.getName())
                && Double.compare(a.getLat(), b.getLat()) == 0 && Double.compare(a.getLng(), b.getLng()) == 0;
    }

    @Override public String toString() {
        return "Graph{type=" + type + ", nodes=" + nodeCount() + ", edges=" + edgeCount() + ", frozen=" + frozen + "}";
    }
}
