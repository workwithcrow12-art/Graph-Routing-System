package graphroute;

import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GraphTest {
    @Test
    void undirectedRoadsAreTraversableBothWaysButCountOnce() {
        Graph graph = graph(GraphType.UNDIRECTED);
        graph.addEdge(new Edge(graph.getNode("A"), graph.getNode("B"), 1.5, "Main Street"));

        assertEquals(1, graph.edgeCount());
        assertEquals(2, graph.getAllEdges().size());
        assertTrue(graph.hasEdge("A", "B"));
        assertTrue(graph.hasEdge("B", "A"));
        Edge reverse = graph.getNeighbors("B").get(0);
        assertEquals(1.5, reverse.getWeight());
        assertEquals("Main Street", reverse.getRoadName());
    }

    @Test
    void directedRoadsPreserveOneWayRestrictions() {
        Graph graph = graph(GraphType.DIRECTED);
        graph.addEdge(new Edge(graph.getNode("A"), graph.getNode("B"), 1));

        assertEquals(1, graph.edgeCount());
        assertTrue(graph.hasEdge("A", "B"));
        assertFalse(graph.hasEdge("B", "A"));
        assertTrue(graph.getNeighbors("B").isEmpty());
    }

    @Test
    void removingIntersectionRemovesIncomingOutgoingParallelAndLoopEdges() {
        Graph graph = graph(GraphType.UNDIRECTED);
        graph.addEdge(new Edge(graph.getNode("A"), graph.getNode("B"), 1));
        graph.addEdge(new Edge(graph.getNode("A"), graph.getNode("B"), 2));
        graph.addEdge(new Edge(graph.getNode("B"), graph.getNode("B"), 0));

        assertEquals(3, graph.edgeCount());
        assertTrue(graph.removeNode("B"));
        assertEquals(0, graph.edgeCount());
        assertEquals(1, graph.nodeCount());
        assertTrue(graph.getNeighbors("A").isEmpty());
        assertFalse(graph.removeNode("missing"));
    }

    @Test
    void explicitBulkRemovalRemovesBothDirectionsAndAllParallelCopies() {
        Graph graph = graph(GraphType.UNDIRECTED);
        graph.addEdge(new Edge(graph.getNode("A"), graph.getNode("B"), 1));
        graph.addEdge(new Edge(graph.getNode("A"), graph.getNode("B"), 2));

        assertEquals(2, graph.removeEdges("A", "B"));
        assertEquals(0, graph.edgeCount());
        assertFalse(graph.hasEdge("B", "A"));
        assertFalse(graph.removeEdge("A", "B"));
    }

    @Test
    void duplicateIdsDoNotReplaceNodesAndCollectionsCannotBypassGraphMethods() {
        Graph graph = graph(GraphType.DIRECTED);
        Node original = graph.getNode("A");
        assertThrows(IllegalArgumentException.class, () -> graph.addNode(new Node("A", "Replacement", 10, 10)));
        graph.addEdge(new Edge(original, graph.getNode("B"), 0));

        assertSame(original, graph.getNode("A"));
        assertEquals(2, graph.nodeCount());
        assertThrows(UnsupportedOperationException.class, () -> graph.getAllNodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.getAllEdges().clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.getNeighbors("A").clear());
        assertThrows(IllegalArgumentException.class,
                () -> graph.addEdge(new Edge(original, new Node("missing", "", 0, 0), 1)));
    }

    @Test
    void snapshotIsIndependentAndFrozen() {
        Graph graph = graph(GraphType.UNDIRECTED);
        graph.addEdge(new Edge(graph.getNode("A"), graph.getNode("B"), 2));
        Graph snapshot = graph.snapshot();
        graph.removeNode("B");
        assertEquals(2, snapshot.nodeCount());
        assertEquals(1, snapshot.edgeCount());
        assertEquals(2, snapshot.getNeighbors("A").get(0).getWeight());
        assertTrue(snapshot.isFrozen());
        assertSame(snapshot, snapshot.snapshot());
        assertThrows(IllegalStateException.class, () -> snapshot.removeNode("A"));
        assertThrows(IllegalStateException.class, () -> snapshot.removeEdge("A", "B"));
        assertThrows(IllegalStateException.class, () -> snapshot.setProvenance(java.util.Map.of()));
    }

    @Test
    void roadCostsMustBeFiniteAndNonnegativeOnCreation() {
        Node a = new Node("A", "", 0, 0);
        Node b = new Node("B", "", 0, 0);
        Edge road = new Edge(a, b, 0);
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Edge(a, b, invalid));
        }
        assertEquals(0, road.getWeight());
    }

    private static Graph graph(GraphType type) {
        Graph graph = new Graph(type);
        graph.addNode(new Node("A", "Alpha", 0, 0));
        graph.addNode(new Node("B", "Beta", 0, 0));
        return graph;
    }
}
