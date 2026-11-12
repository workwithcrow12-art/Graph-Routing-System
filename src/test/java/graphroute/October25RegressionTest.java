package graphroute;

import graphroute.analysis.AnalysisWorkspace;
import graphroute.io.OsmImporter;
import graphroute.model.*;
import graphroute.routing.RoutingService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class October25RegressionTest {
    private static Graph roads(GraphType type) {
        Graph graph = new Graph(type);
        graph.addNode(new Node("a", "A", 0, -1));
        graph.addNode(new Node("b", "B", 0, 1));
        graph.addEdge(new Edge(graph.getNode("a"), graph.getNode("b"), 300, "First"));
        return graph;
    }

    @Test void endpointRemovalRejectsParallelRoadsWithoutMutatingEitherDirection() {
        for (GraphType type : GraphType.values()) {
            Graph graph = roads(type);
            graph.addEdge(new Edge(graph.getNode("a"), graph.getNode("b"), 400, "Second"));
            long revision = graph.getRevision();
            assertThrows(IllegalArgumentException.class, () -> graph.removeEdge("a", "b"));
            assertEquals(revision, graph.getRevision());
            assertEquals(2, graph.edgeCount());
            assertEquals(2, graph.removeEdges("a", "b"));
            assertEquals(0, graph.edgeCount());
        }
    }

    @Test void selfLoopRemovalCountsLogicalUndirectedRoads() {
        Graph graph = roads(GraphType.UNDIRECTED);
        Edge loop = new Edge(graph.getNode("a"), graph.getNode("a"), 0, "Loop");
        graph.addEdge(loop);
        assertTrue(graph.removeEdge("a", "a"));
        graph.addEdge(loop);
        graph.addEdge(loop);
        assertThrows(IllegalArgumentException.class, () -> graph.removeEdge("a", "a"));
        assertEquals(2, graph.removeEdges("a", "a"));
        assertEquals(1, graph.edgeCount());
    }

    @Test void ambiguousWorkspaceEditPreservesRevisionAndPreciseSelectorWorks() {
        AnalysisWorkspace workspace = new AnalysisWorkspace(roads(GraphType.UNDIRECTED));
        var view = workspace.view("custom");
        var first = view.edges().getFirst();
        var extra = new AnalysisWorkspace.EdgeDto(first.source(), first.target(), first.weight() + 1, "Parallel");
        view = workspace.edit(new AnalysisWorkspace.Edit("addEdge", view.revision(), null, extra, null));
        long revision = view.revision();
        var vague = new AnalysisWorkspace.EdgeDto(first.source(), first.target(), (Double) null, null);
        assertThrows(IllegalArgumentException.class, () -> workspace.edit(
                new AnalysisWorkspace.Edit("removeEdge", revision, null, vague, null)));
        assertEquals(revision, workspace.view("custom").revision());
        var edited = workspace.edit(new AnalysisWorkspace.Edit("removeEdge", revision, null, extra, null));
        assertEquals(revision + 1, edited.revision());
        assertFalse(edited.edges().stream().anyMatch(edge -> edge.roadName().equals("Parallel")));
    }

    @Test void viewportIncludesCrossingGeometryAndEndpointsOutsideTheBox() {
        RoutingService service = new RoutingService(roads(GraphType.DIRECTED));
        var map = service.map(new RoutingService.Bounds(-.1, -.1, .1, .1));
        assertEquals(1, map.roads().size());
        assertEquals(2, map.places().size());
        assertEquals(service.map().roads().getFirst().id(), map.roads().getFirst().id());
        assertTrue(service.map(new RoutingService.Bounds(30, 30, 31, 31)).roads().isEmpty());
        assertEquals(1, service.map().roads().size());
        assertThrows(IllegalArgumentException.class, () -> service.map(new RoutingService.Bounds(1, 0, -1, 1)));
        assertThrows(IllegalArgumentException.class, () -> service.map(new RoutingService.Bounds(0, 0, Double.NaN, 1)));
    }

    private static Graph read(String text) throws Exception {
        return new OsmImporter().load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String extract(String nodeTags, String wayTags) {
        return """
            <osm><node id="1" lat="21" lon="105">%s</node>
            <node id="2" lat="21" lon="105.001"/>
            <node id="3" lat="21" lon="105.0015"/>
            <node id="4" lat="21" lon="106"/>
            <node id="5" lat="21" lon="106.001"/>
            <way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/>%s</way>
            <way id="11"><nd ref="2"/><nd ref="3"/><tag k="highway" v="service"/></way>
            <way id="12"><nd ref="4"/><nd ref="5"/><tag k="highway" v="service"/></way></osm>
            """.formatted(nodeTags, wayTags);
    }

    @Test void importerUsesBlankNameFallbacksAndLabelsNearbyJunctionsHonestly() throws Exception {
        Graph graph = read(extract("", "<tag k='name' v=' '/><tag k='ref' v='QL1'/>"));
        assertEquals("QL1", graph.getNeighbors("osm:1").getFirst().getRoadName());
        assertEquals("Near QL1 (OSM junction 3)", graph.getNode("osm:3").getName());
        assertEquals("OSM junction 4", graph.getNode("osm:4").getName());
        assertTrue(graph.getNeighbors("osm:3").getFirst().getRoadName().isBlank(),
                "Nearby context must not invent an official road name");
    }

    @Test void importerUsesVietnameseNamesAndNodeAddresses() throws Exception {
        Graph graph = read(extract("<tag k='addr:housenumber' v='12'/><tag k='addr:street' v='Phố Huế'/>",
                "<tag k='name:vi' v='Đường Việt'/>"));
        assertEquals("12 Phố Huế", graph.getNode("osm:1").getName());
        assertEquals("Đường Việt", graph.getNeighbors("osm:1").getFirst().getRoadName());
    }

    @Test void importerBoundsNewFallbackTagsToo() {
        assertThrows(graphroute.io.GraphSerializer.GraphDataException.class, () -> read(extract("",
                "<tag k='addr:street' v='" + "x".repeat(OsmImporter.MAX_NAME_CHARS + 1) + "'/>")));
    }
}
