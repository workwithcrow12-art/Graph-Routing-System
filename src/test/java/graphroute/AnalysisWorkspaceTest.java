package graphroute;

import graphroute.analysis.*;
import graphroute.algorithm.AlgorithmResult;
import graphroute.io.GraphFileManager;
import graphroute.model.*;
import graphroute.routing.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisWorkspaceTest {
    @Test void missingEdgeEndpointsIdentifyTheFieldAndLeaveTheGraphUnchanged() {
        var workspace = new AnalysisWorkspace(new GraphFileManager().loadThanhXuanGraph());
        var before = workspace.view("custom");
        String valid = before.nodes().getFirst().id();
        for (var edge : List.of(new AnalysisWorkspace.EdgeDto("missing-source", valid, 1, ""),
                new AnalysisWorkspace.EdgeDto(valid, "missing-target", 1, ""),
                new AnalysisWorkspace.EdgeDto(null, valid, 1, ""),
                new AnalysisWorkspace.EdgeDto(valid, " ", 1, ""))) {
            String field = valid.equals(edge.source()) ? "target" : "source";
            var error = assertThrows(IllegalArgumentException.class, () -> workspace.edit(
                    new AnalysisWorkspace.Edit("addEdge", before.revision(), null, edge, null)));
            assertTrue(error.getMessage().contains("edge." + field), error.getMessage());
            String invalidId = field.equals("source") ? edge.source() : edge.target();
            if (invalidId != null && !invalidId.isBlank()) assertTrue(error.getMessage().contains(invalidId));
            assertEquals(before, workspace.view("custom"));
        }
    }

    @Test void allEightAlgorithmsAreUsableWithDefaultOsmLoadedAndSampleSelected() {
        var workspace = new AnalysisWorkspace(new GraphFileManager().loadDefaultGraph());
        var sample = workspace.view("sample");
        assertEquals(8, sample.nodes().size());
        for (var algorithm : AlgorithmService.algorithms()) {
            boolean tree = algorithm.requiresUndirectedGraph();
            var result = workspace.execute(new AlgorithmRequest(algorithm.id(),
                    tree ? null : sample.nodes().getFirst().id(), tree ? null : sample.nodes().getLast().id(),
                    2, "sample", null, null));
            assertEquals(AlgorithmResult.Status.SUCCESS, result.status(), algorithm.id());
            assertFalse(result.message().isBlank());
        }
        var osm = workspace.view("loaded");
        for (String algorithm : List.of("floyd-warshall", "bellman-ford", "prim", "kruskal")) {
            boolean tree = algorithm.equals("prim") || algorithm.equals("kruskal");
            var result = workspace.execute(new AlgorithmRequest(algorithm,
                    tree ? null : osm.nodes().getFirst().id(), tree ? null : osm.nodes().getLast().id()));
            assertEquals(AlgorithmResult.Status.INVALID, result.status());
            assertTrue(result.message().contains("sample"));
        }
    }

    @Test void subgraphPreservesDirectionUnlessProjectionIsExplicit() {
        Graph graph = new Graph(GraphType.DIRECTED);
        for (String id : List.of("a", "b", "c")) graph.addNode(new Node(id, id, 21, 105));
        graph.addEdge(new Edge(graph.getNode("a"), graph.getNode("b"), 3));
        graph.addEdge(new Edge(graph.getNode("b"), graph.getNode("a"), 1));
        graph.addEdge(new Edge(graph.getNode("b"), graph.getNode("c"), 2));
        var workspace = new AnalysisWorkspace(graph);
        var direct = workspace.execute(new AlgorithmRequest("floyd-warshall", "a", "b", 0, "loaded", List.of("a", "b"), false));
        assertEquals(3, direct.totalCost());
        assertEquals(2, direct.metrics().graphNodes());
        assertEquals(AlgorithmResult.Status.INVALID, workspace.execute(new AlgorithmRequest("prim", null, null, 0,
                "loaded", List.of("a", "b"), false)).status());
        var mst = workspace.execute(new AlgorithmRequest("prim", null, null, 0, "loaded", List.of("b", "a"), true));
        assertEquals(1, mst.totalCost());
        assertThrows(IllegalArgumentException.class, () -> workspace.execute(new AlgorithmRequest("bfs", null, null, 0,
                "loaded", List.of("a", "a"), false)));
        assertThrows(IllegalArgumentException.class, () -> workspace.execute(new AlgorithmRequest("bfs", null, null, 0,
                "missing", null, false)));
    }

    @Test void customEditsAreAtomicRevisionCheckedAndInvalidateAnalysisCaches() {
        var workspace = new AnalysisWorkspace(new GraphFileManager().loadThanhXuanGraph());
        var original = workspace.view("custom");
        String start = original.nodes().getFirst().id();
        var added = workspace.edit(new AnalysisWorkspace.Edit("addNode", 0L,
                new AnalysisWorkspace.NodeDto("new", "New junction", 21, 105), null, null));
        assertEquals(9, added.nodes().size());
        assertEquals(8, workspace.view("loaded").nodes().size());
        assertEquals(8, workspace.view("sample").nodes().size());
        var noPath = workspace.execute(new AlgorithmRequest("dijkstra", start, "new", 0, "custom", null, null));
        assertEquals(AlgorithmResult.Status.UNREACHABLE, noPath.status());
        workspace.edit(new AnalysisWorkspace.Edit("addEdge", 1L, null,
                new AnalysisWorkspace.EdgeDto(start, "new", .2, "Connection"), null));
        assertEquals(.2, workspace.execute(new AlgorithmRequest("dijkstra", start, "new", 0, "custom", null, null)).totalCost());
        assertEquals("REVISION_CONFLICT", assertThrows(RoutingException.class, () -> workspace.edit(
                new AnalysisWorkspace.Edit("removeNode", 1L, null, null, "new"))).getCode());
        assertThrows(IllegalArgumentException.class, () -> workspace.edit(new AnalysisWorkspace.Edit("addEdge", 2L,
                null, new AnalysisWorkspace.EdgeDto("missing", "new", 1, ""), null)));
        assertEquals(2, workspace.view("custom").revision());
        workspace.edit(new AnalysisWorkspace.Edit("removeNode", 2L, null, null, "new"));
        assertEquals(original.edges().size(), workspace.view("custom").edges().size());
        assertEquals(8, workspace.view("custom").nodes().size());
    }

    @Test void analysisLimitsDoNotRejectRoutingConstructionOrQueries() {
        Graph large = new Graph(GraphType.DIRECTED);
        Node a = new Node("a", "A", 21, 105), b = new Node("b", "B", 21, 105.001);
        large.addNode(a); large.addNode(b);
        Edge repeated = new Edge(a, b, 1);
        for (int i = 0; i <= AlgorithmService.MAX_GRAPH_ARCS; i++) large.addEdge(repeated);
        large.freeze();
        var analysis = new AlgorithmService(large);
        assertEquals(AlgorithmResult.Status.INVALID, analysis.execute(new AlgorithmRequest("bfs", null, null)).status());
        var routing = new RoutingService(large);
        assertEquals(1000, routing.route(new RouteRequest("a", "b")).routes().getFirst().distanceMeters());
    }
}
