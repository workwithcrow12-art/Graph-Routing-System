package graphroute;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.shortestpath.*;
import graphroute.io.*;
import graphroute.model.*;
import graphroute.routing.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProblemReportRegressionTest {
    private static Graph graph(GraphType type, String... ids) {
        Graph graph = new Graph(type);
        for (int i = 0; i < ids.length; i++) graph.addNode(new Node(ids[i], "Same name", 0, i * .0001));
        return graph;
    }
    private static void road(Graph graph, String a, String b, double km, String name) {
        graph.addEdge(new Edge(graph.getNode(a), graph.getNode(b), km, name));
    }

    @Test void searchFiltersPublicModesAndPaginatesWithoutLosingMatches() {
        Graph graph = graph(GraphType.UNDIRECTED, "a", "b", "c", "d");
        road(graph, "a", "b", 1, "Main");
        graph.addEdge(new Edge(graph.getNode("b"), graph.getNode("c"), 1, "Path",
                List.of(new Edge.Point(0, .0001), new Edge.Point(0, .0002)), Map.of(Edge.Mode.WALKING, 5.0), "path", Map.of()));
        var service = new RoutingService(graph);
        assertEquals(2, service.search("same", 100).size());
        assertEquals(3, service.search("same", 100, 0, "walking").total());
        assertEquals(4, service.search("same", 100, 0, "all").total());
        var first = service.search("same", 1, 0, "walking");
        var second = service.search("same", 1, 1, "walking");
        assertEquals(3, first.total());
        assertNotEquals(first.places().getFirst().id(), second.places().getFirst().id());
        assertNotEquals(first.places().getFirst().displayName(), second.places().getFirst().displayName());
        assertTrue(first.places().getFirst().modes().contains("driving"));
        assertThrows(RoutingException.class, () -> service.search("same", 10, -1, "all"));
    }

    @Test void nearestPrefersMutuallyReachableMainComponentAndAllModeCanInspectOtherNodes() {
        Graph graph = graph(GraphType.DIRECTED, "a", "b", "c", "d", "e");
        road(graph, "a", "b", 1, "Main"); road(graph, "b", "c", 1, "Main"); road(graph, "c", "a", 1, "Main");
        road(graph, "d", "e", 1, "Isolated"); road(graph, "e", "d", 1, "Isolated");
        var service = new RoutingService(graph);
        assertEquals("c", service.nearest(0, .0004, 1000).place().id());
        assertEquals("e", service.nearest(0, .0004, 1000, "all").place().id());
        assertEquals(List.of("a", "b", "c"), service.search("same", 100).stream()
                .filter(p -> p.mainComponentModes().contains("driving")).map(RoutingService.Place::id).toList());
    }

    @Test void instructionsMergeAndRoundedDurationsSumToTotal() {
        Graph graph = graph(GraphType.DIRECTED, "a", "b", "c", "d");
        road(graph, "a", "b", .005, "Main"); road(graph, "b", "c", .005, "Main"); road(graph, "c", "d", .005, "Other");
        var route = new RoutingService(graph).route(new RouteRequest("a", "d")).routes().getFirst();
        assertEquals(3, route.directions().size());
        assertEquals("c", route.directions().getFirst().to());
        assertEquals(10, route.directions().getFirst().distanceMeters(), 1e-10);
        assertEquals(2, route.durationSeconds());
        assertEquals(route.durationSeconds(), route.directions().stream().mapToLong(RoutingService.Direction::durationSeconds).sum());
    }

    @Test void removesOneParallelRoadAndItsReverseIncludingSelfLoops() {
        Graph graph = graph(GraphType.UNDIRECTED, "a", "b");
        road(graph, "a", "b", 1, "One"); road(graph, "a", "b", 2, "Two");
        Edge two = graph.getNeighbors("a").get(1);
        assertTrue(graph.removeEdge(two));
        assertEquals(1, graph.edgeCount());
        assertEquals("One", graph.getNeighbors("b").getFirst().getRoadName());
        road(graph, "a", "a", 0, "Loop");
        assertTrue(graph.removeEdge("a", "a"));
        assertFalse(graph.hasEdge("a", "a"));
        assertEquals(1, graph.removeEdges("a", "b"));
        assertEquals(0, graph.edgeCount());
    }

    @Test void editApiSelectsOneParallelRoadByNameAndWeight() {
        var workspace = new graphroute.analysis.AnalysisWorkspace(new GraphFileManager().loadThanhXuanGraph());
        var view = workspace.view("custom");
        var original = view.edges().getFirst();
        var parallel = new graphroute.analysis.AnalysisWorkspace.EdgeDto(original.source(), original.target(), original.weight() + 1, "Parallel selector");
        view = workspace.edit(new graphroute.analysis.AnalysisWorkspace.Edit("addEdge", view.revision(), null, parallel, null));
        int arcs = view.edges().size();
        view = workspace.edit(new graphroute.analysis.AnalysisWorkspace.Edit("removeEdge", view.revision(), null, parallel, null));
        assertEquals(arcs - 2, view.edges().size());
        assertTrue(view.edges().stream().anyMatch(e -> e.source().equals(original.source()) && e.target().equals(original.target()) && e.weight().equals(original.weight())));
        assertFalse(view.edges().stream().anyMatch(e -> e.roadName().equals("Parallel selector")));
    }

    @Test void motorwayLinksAreOneWayAndUnsupportedHighwayTypesAreExcluded() throws Exception {
        String xml = "<osm><node id='1' lat='0' lon='0'/><node id='2' lat='0' lon='.01'/>"
                + "<way id='10'><nd ref='1'/><nd ref='2'/><tag k='highway' v='%s'/></way></osm>";
        Graph link = new OsmImporter().load(new ByteArrayInputStream(xml.formatted("motorway_link").getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, link.edgeCount());
        assertTrue(link.hasEdge("osm:1", "osm:2"));
        assertFalse(link.hasEdge("osm:2", "osm:1"));
        assertEquals("0.0000000,0.0000000,0.0000000,0.0100000", link.getProvenance().get("dataBounds"));
        for (String type : List.of("bus_guideway", "raceway", "elevator", "construction", "unknown")) {
            assertTrue(new OsmImporter().load(new ByteArrayInputStream(xml.formatted(type).getBytes(StandardCharsets.UTF_8))).isEmpty(), type);
        }
    }

    @Test void missingDefaultDatasetFailsClearly() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(new ClassLoader(null) { });
            assertTrue(assertThrows(GraphSerializer.MissingGraphResourceException.class,
                    () -> new GraphFileManager().loadDefaultGraph()).getMessage().contains("Missing bundled OSM"));
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }

    @Test void bellmanFordAcceptsSignedAnalysisCostsAndDetectsOnlyReachableNegativeCycles() {
        Graph graph = graph(GraphType.DIRECTED, "a", "b", "c", "x");
        road(graph, "a", "b", 1, "ab"); road(graph, "b", "c", 1, "bc"); road(graph, "a", "c", 1, "ac");
        road(graph, "x", "x", 1, "cycle");
        var bf = new BellmanFord();
        var result = bf.execute(graph, "a", "c", e -> switch(e.getRoadName()) { case "bc" -> -3; case "cycle" -> -1; default -> 1; }, 10);
        assertEquals(AlgorithmResult.Status.SUCCESS, result.getStatus());
        assertEquals(-2, result.getTotalCost());
        assertEquals(List.of("ab", "bc"), result.getEdges().stream().map(Edge::getRoadName).toList());
        road(graph, "c", "b", 1, "cb");
        assertEquals(AlgorithmResult.Status.NEGATIVE_CYCLE, bf.execute(graph, "a", "c", e -> e.getRoadName().equals("bc") ? -3 : 1, 0).getStatus());
        assertEquals(AlgorithmResult.Status.NEGATIVE_CYCLE, bf.execute(graph, "a", "a", e -> e.getRoadName().equals("bc") ? -3 : 1, 0).getStatus());
    }

    @Test void libraryYenHasNoImplicitRoutingQuotaButExplicitRouteQuotaStillApplies() {
        int length = 550;
        Graph graph = new Graph(GraphType.DIRECTED);
        for (int i = 0; i <= length; i++) graph.addNode(new Node("n" + i, "Node " + i, 0, 0));
        for (int i = 0; i < length; i++) {
            road(graph, "n" + i, "n" + (i + 1), 1, "Main");
            road(graph, "n" + i, "n" + (i + 1), 2, "Parallel");
        }
        var yen = new YenKShortestPaths(new PreparedShortestPath(graph, false));
        assertThrows(SearchBudget.LimitExceededException.class, () -> yen.execute("n0", "n550", 2, e -> true, SearchBudget.routing()));
        var results = yen.execute("n0", "n550", 2);
        assertEquals(2, results.size());
        assertEquals(550, results.getFirst().getTotalCost());
        assertEquals(551, results.get(1).getTotalCost());
    }
}
