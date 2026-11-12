package graphroute.analysis;

import graphroute.io.SampleGraphs;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import graphroute.routing.RoutingException;

import java.util.*;

/** Independent, bounded educational workspace; editing never mutates the routing network. */
public final class AnalysisWorkspace {
    public record NodeDto(String id, String name, Double latitude, Double longitude) {
        public NodeDto(String id, String name, double latitude, double longitude) {
            this(id, name, Double.valueOf(latitude), Double.valueOf(longitude));
        }
    }
    public record EdgeDto(String source, String target, Double weight, String roadName) {
        public EdgeDto(String source, String target, double weight, String roadName) {
            this(source, target, Double.valueOf(weight), roadName);
        }
    }
    public record View(String graph, String graphType, long revision, List<NodeDto> nodes, List<EdgeDto> edges) { }
    public record Descriptor(String id, String graphType, int nodes, int arcs, boolean editable,
                             List<AlgorithmService.Availability> algorithms) { }
    public record Edit(String operation, Long expectedRevision, NodeDto node, EdgeDto edge, String nodeId) { }
    private record State(Graph graph, AlgorithmService service, long revision) { }
    private record Selection(String graph, List<String> nodeIds, boolean undirected, long revision) { }
    private final State loaded;
    private final State sample;
    private volatile State custom;
    private final Map<Selection, AlgorithmService> selections = new LinkedHashMap<>(16, .75f, true);

    public AnalysisWorkspace(Graph graph) {
        loaded = state(graph, 0);
        sample = state(SampleGraphs.thanhXuan(), 0);
        custom = sample;
    }

    private static State state(Graph graph, long revision) {
        Graph snapshot = graph.snapshot();
        return new State(snapshot, new AlgorithmService(snapshot), revision);
    }

    public List<Descriptor> graphs() {
        State editing = custom;
        return List.of(descriptor("loaded", loaded, false), descriptor("sample", sample, false),
                descriptor("custom", editing, true));
    }

    public View view(String id) {
        String name = name(id);
        State state = select(name);
        return new View(name, state.graph().getType().name(), state.revision(),
                state.graph().getAllNodes().stream().map(n -> new NodeDto(n.getId(), n.getName(), n.getLat(), n.getLng())).toList(),
                state.graph().getAllEdges().stream().map(e -> new EdgeDto(e.getSource().getId(),
                        e.getDestination().getId(), e.getWeight(), e.getRoadName())).toList());
    }

    public AlgorithmResponse execute(AlgorithmRequest request) {
        if (request == null) throw new IllegalArgumentException("Algorithm request is required");
        String name = name(request.graph());
        State state = select(name);
        boolean undirected = Boolean.TRUE.equals(request.undirected());
        if (request.nodeIds() == null && !undirected) return state.service().execute(request);
        List<String> ids = null;
        if (request.nodeIds() != null) {
            if (request.nodeIds().isEmpty() || request.nodeIds().size() > 300)
                throw new IllegalArgumentException("nodeIds subgraph requires 1..300 unique existing IDs");
            Set<String> unique = new HashSet<>();
            for (String id : request.nodeIds())
                if (id == null || !state.graph().hasNode(id) || !unique.add(id))
                    throw new IllegalArgumentException("nodeIds must contain unique existing IDs");
            ids = unique.stream().sorted().toList();
        }
        if (ids == null && (state.graph().nodeCount() > AlgorithmService.MAX_GRAPH_NODES
                || state.graph().getAllEdges().size() > AlgorithmService.MAX_GRAPH_ARCS))
            throw new IllegalArgumentException("Select a nodeIds subgraph before projecting this large network");
        Selection key = new Selection(name, ids, undirected, state.revision());
        AlgorithmService service;
        synchronized (selections) {
            service = selections.get(key);
            if (service == null) {
                service = new AlgorithmService(project(state.graph(), ids, undirected));
                selections.put(key, service);
                if (selections.size() > 8) selections.remove(selections.keySet().iterator().next());
            }
        }
        return service.execute(request);
    }

    /** Applies one optimistic edit to the custom graph, then atomically publishes a new snapshot. */
    public synchronized View edit(Edit edit) {
        if (edit == null || edit.operation() == null || edit.expectedRevision() == null)
            throw new IllegalArgumentException("operation and expectedRevision are required");
        State previous = custom;
        if (edit.expectedRevision() != previous.revision())
            throw new RoutingException("REVISION_CONFLICT", "Reload graph=custom and retry with its current revision");
        Graph next = copy(previous.graph());
        switch (edit.operation()) {
            case "addNode" -> {
                if (edit.node() == null) throw new IllegalArgumentException("node is required");
                NodeDto node = edit.node();
                if (node.latitude() == null || node.longitude() == null)
                    throw new IllegalArgumentException("node.latitude and node.longitude are required");
                if (node.id() == null || next.hasNode(node.id())) throw new IllegalArgumentException("Node ID must be new");
                next.addNode(new Node(node.id(), node.name(), node.latitude(), node.longitude()));
            }
            case "removeNode" -> {
                if (edit.nodeId() == null || !next.removeNode(edit.nodeId())) throw new IllegalArgumentException("Unknown nodeId");
            }
            case "addEdge" -> {
                if (edit.edge() == null) throw new IllegalArgumentException("edge is required");
                EdgeDto edge = edit.edge();
                if (edge.weight() == null) throw new IllegalArgumentException("edge.weight is required");
                requireEndpoint(next, "source", edge.source());
                requireEndpoint(next, "target", edge.target());
                next.addEdge(new Edge(next.getNode(edge.source()), next.getNode(edge.target()), edge.weight(), edge.roadName()));
            }
            case "removeEdge" -> {
                if (edit.edge() == null) throw new IllegalArgumentException("edge is required");
                EdgeDto selector = edit.edge();
                List<Edge> matches = next.getNeighbors(selector.source()).stream()
                        .filter(edge -> edge.getDestination().getId().equals(selector.target()))
                        .filter(edge -> selector.weight() == null || Double.compare(edge.getWeight(), selector.weight()) == 0)
                        .filter(edge -> selector.roadName() == null || edge.getRoadName().equals(selector.roadName()))
                        .toList();
                if (matches.isEmpty()) throw new IllegalArgumentException("Unknown edge selector");
                int arcsPerRoad = next.getType() == GraphType.UNDIRECTED && Objects.equals(selector.source(), selector.target()) ? 2 : 1;
                if (matches.size() > arcsPerRoad)
                    throw new IllegalArgumentException("Ambiguous edge selector; supply unique weight/roadName or use removeEdges");
                next.removeEdge(matches.getFirst());
            }
            case "removeEdges" -> {
                if (edit.edge() == null || next.removeEdges(edit.edge().source(), edit.edge().target()) == 0)
                    throw new IllegalArgumentException("Unknown edge endpoints");
            }
            case "reset" -> next = copy(sample.graph());
            default -> throw new IllegalArgumentException("operation must be addNode, removeNode, addEdge, removeEdge, removeEdges or reset");
        }
        if (next.nodeCount() > 300 || next.getAllEdges().size() > 2000)
            throw new IllegalArgumentException("Custom analysis graph is limited to 300 nodes and 2000 arcs");
        custom = state(next, previous.revision() + 1);
        return view("custom");
    }

    private State select(String id) {
        return switch (id) {
            case "loaded" -> loaded;
            case "sample" -> sample;
            case "custom" -> custom;
            default -> throw new IllegalArgumentException("graph must be loaded, sample or custom");
        };
    }
    private static void requireEndpoint(Graph graph, String field, String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("edge." + field + " is required");
        if (!graph.hasNode(id)) throw new IllegalArgumentException("Unknown edge." + field + " node: " + id);
    }
    private static String name(String id) { return id == null ? "loaded" : id; }
    private static Descriptor descriptor(String id, State state, boolean editable) {
        return new Descriptor(id, state.graph().getType().name(), state.graph().nodeCount(), state.graph().getAllEdges().size(), editable, state.service().availability());
    }

    private static Graph copy(Graph graph) {
        Graph result = new Graph(graph.getType());
        graph.getAllNodes().forEach(result::addNode);
        // Pair reverse values with multiplicity, preserving parallel roads.
        Map<Edge, Integer> pending = new HashMap<>();
        for (Edge edge : graph.getAllEdges()) {
            if (graph.getType() == GraphType.UNDIRECTED && consumeReverse(pending, edge)) continue;
            result.addEdge(edge);
            if (graph.getType() == GraphType.UNDIRECTED) pending.merge(edge.reverse(), 1, Integer::sum);
        }
        result.setProvenance(graph.getProvenance());
        return result;
    }
    private static boolean consumeReverse(Map<Edge, Integer> pending, Edge edge) {
        Integer count = pending.get(edge);
        if (count == null) return false;
        if (count == 1) pending.remove(edge); else pending.put(edge, count - 1);
        return true;
    }

    private static Graph project(Graph source, List<String> selected, boolean undirected) {
        Set<String> ids = selected == null ? source.getAllNodes().stream().map(Node::getId).collect(java.util.stream.Collectors.toSet())
                : Set.copyOf(selected);
        Graph result = new Graph(undirected ? GraphType.UNDIRECTED : source.getType());
        source.getAllNodes().stream().filter(n -> ids.contains(n.getId())).forEach(result::addNode);
        Map<List<String>, Edge> minimum = new LinkedHashMap<>();
        Map<Edge, Integer> pending = new HashMap<>();
        for (String id : ids) for (Edge edge : source.getNeighbors(id)) {
            if (!ids.contains(edge.getDestination().getId())) continue;
            if (!undirected) {
                if (result.getType() == GraphType.UNDIRECTED && consumeReverse(pending, edge)) continue;
                result.addEdge(edge);
                if (result.getType() == GraphType.UNDIRECTED) pending.merge(edge.reverse(), 1, Integer::sum);
                continue;
            }
            List<String> pair = java.util.stream.Stream.of(id, edge.getDestination().getId()).sorted().toList();
            minimum.merge(pair, edge, (a, b) -> a.getWeight() <= b.getWeight() ? a : b);
        }
        minimum.values().forEach(result::addEdge);
        return result.freeze();
    }
}
