package graphroute;

import graphroute.io.OsmImporter;
import graphroute.model.*;
import graphroute.routing.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutingAccessTest {
    private static Graph osm(String tags) throws Exception {
        return new OsmImporter().load(new ByteArrayInputStream(("""
                <osm><node id="1" lat="21" lon="105"/><node id="2" lat="21" lon="105.001"/>
                <node id="3" lat="21" lon="105.002"/><node id="4" lat="21" lon="105.003"/>
                <way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="service"/>%s</way>
                <way id="11"><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/></way>
                <way id="12"><nd ref="3"/><nd ref="4"/><tag k="highway" v="residential"/></way>
                </osm>
                """).formatted(tags).getBytes(StandardCharsets.UTF_8)));
    }
    private static RouteRequest route(String from, String to, String mode, List<String> ways) {
        return new RouteRequest(from, to, null, mode, null, 3, null, null, ways);
    }

    @Test void restrictedWaysRequireBothAuthorizationAndAStopOnThatWay() throws Exception {
        for (String access : List.of("destination", "delivery", "customers")) {
            Graph graph = osm("<tag k=\"access\" v=\"" + access + "\"/>");
            assertEquals(Edge.Access.valueOf(access.toUpperCase(Locale.ROOT)),
                    graph.getNeighbors("osm:1").getFirst().getAccess(Edge.Mode.DRIVING));
            var service = new RoutingService(graph);
            assertEquals("NO_ROUTE", assertThrows(RoutingException.class,
                    () -> service.route(route("osm:1", "osm:4", "driving", null))).getCode());
            assertTrue(service.route(route("osm:1", "osm:4", "driving", List.of("10"))).routes().getFirst().distanceMeters() > 0);
            assertTrue(service.route(route("osm:4", "osm:1", "driving", List.of("10"))).routes().getFirst().distanceMeters() > 0);
            assertThrows(RoutingException.class, () -> service.route(route("osm:3", "osm:4", "driving", List.of("10"))));
            assertThrows(RoutingException.class, () -> service.route(route("osm:1", "osm:4", "driving", List.of("missing"))));
        }
    }

    @Test void motorcycleAccessAndOnewayOverridesAreIndependentFromMotorcar() throws Exception {
        Graph graph = osm("<tag k=\"motorcar\" v=\"no\"/><tag k=\"motorcycle\" v=\"yes\"/>"
                + "<tag k=\"oneway\" v=\"yes\"/><tag k=\"oneway:motorcycle\" v=\"no\"/>");
        var service = new RoutingService(graph);
        assertThrows(RoutingException.class, () -> service.route(route("osm:1", "osm:4", "driving", null)));
        assertFalse(service.route(route("osm:1", "osm:4", "motorcycling", null)).routes().isEmpty());
        assertFalse(service.route(route("osm:4", "osm:1", "motorcycling", null)).routes().isEmpty());
        Graph privateGraph = osm("<tag k=\"access\" v=\"private\"/>");
        assertFalse(privateGraph.hasNode("osm:1"));
        Graph override = osm("<tag k=\"access\" v=\"destination\"/><tag k=\"motorcycle\" v=\"yes\"/>");
        assertEquals(Edge.Access.PUBLIC, override.getNeighbors("osm:1").getFirst().getAccess(Edge.Mode.MOTORCYCLING));
        assertEquals(Edge.Access.DESTINATION, override.getNeighbors("osm:1").getFirst().getAccess(Edge.Mode.DRIVING));
    }

    @Test void nearestModeAvoidsJunctionsOnInaccessibleRoads() throws Exception {
        var service = new RoutingService(osm("<tag k=\"motorcar\" v=\"no\"/><tag k=\"motorcycle\" v=\"yes\"/>"));
        assertEquals("osm:1", service.nearest(21, 105, 2000, "motorcycling").place().id());
        assertEquals("osm:2", service.nearest(21, 105, 2000, "driving").place().id());
        assertThrows(RoutingException.class, () -> service.nearest(21, 105, 2000, "unknown"));
    }

    @Test void parallelWayAlternativesExplicitlyExposeTheirEdgeIdentity() {
        Graph graph = new Graph(GraphType.DIRECTED);
        Node a = new Node("a", "A", 21, 105), b = new Node("b", "B", 21, 105.01);
        graph.addNode(a); graph.addNode(b);
        graph.addEdge(new Edge(a, b, 1, "First"));
        graph.addEdge(new Edge(a, b, 2, "Parallel"));
        var plan = new RoutingService(graph).route(route("a", "b", "driving", null));
        assertEquals("directed-edge-sequence", plan.alternativeIdentity());
        assertEquals(2, plan.routes().size());
        assertTrue(plan.routes().get(1).sameNodeSequenceAsPrimary());
        assertNotEquals(plan.routes().get(0).segments().getFirst().id(), plan.routes().get(1).segments().getFirst().id());
        assertEquals("Parallel", plan.routes().get(1).segments().getFirst().roadName());
    }
}
