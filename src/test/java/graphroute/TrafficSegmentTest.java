package graphroute;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphroute.io.GraphFileManager;
import graphroute.model.*;
import graphroute.routing.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrafficSegmentTest {
    private static final Instant NOW = Instant.parse("2026-10-23T06:00:00Z");

    private static RoutingService service() {
        Graph graph = new Graph(GraphType.DIRECTED);
        Node a = new Node("a", "A", 21, 105), b = new Node("b", "B", 21, 105.001);
        graph.addNode(a); graph.addNode(b);
        // Equal-valued arcs must retain distinct identities, even on the same OSM way.
        for (int i = 0; i < 2; i++) graph.addEdge(new Edge(a, b, 1, "Parallel",
                List.of(new Edge.Point(21, 105), new Edge.Point(21, 105.001)),
                Map.of(Edge.Mode.DRIVING, 30.0), "same-way", Map.of()));
        return new RoutingService(graph, "parallel", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TrafficSnapshot snapshot(TrafficSnapshot.Update... updates) {
        return new TrafficSnapshot("test observation", NOW.toString(), NOW.plusSeconds(600).toString(), List.of(updates));
    }

    private static TrafficSnapshot.Update update(String id, Double speed, boolean closed) {
        return new TrafficSnapshot.Update("a", "b", "same-way", "driving", speed, closed, id);
    }

    @Test void parallelArcsHaveDistinctMapIdsMatchingRouteSegmentsAndIndependentTraffic() {
        var service = service();
        var ids = service.map().roads().stream().map(RoutingService.Road::id).toList();
        assertEquals(2, new HashSet<>(ids).size());
        var alternatives = service.route(new RouteRequest("a", "b", List.of(), "driving", "dijkstra", 2, List.of())).routes();
        assertEquals(new HashSet<>(ids), new HashSet<>(alternatives.stream().map(r -> r.segments().getFirst().id()).toList()));
        service.updateTraffic(snapshot(update(ids.get(0), null, true), update(ids.get(1), 10.0, false)));
        var route = service.route(new RouteRequest("a", "b")).routes().getFirst();
        assertEquals(ids.get(1), route.segments().getFirst().id());
        assertEquals(360, route.durationSeconds());
        service.updateTraffic(snapshot(update(ids.get(0), null, true), update(ids.get(1), null, true)));
        assertEquals("NO_ROUTE", assertThrows(RoutingException.class, () -> service.route(new RouteRequest("a", "b"))).getCode());
    }

    @Test void legacyGroupSelectorStillUpdatesEveryParallelArc() {
        var service = service();
        service.updateTraffic(snapshot(new TrafficSnapshot.Update("a", "b", "same-way", "driving", null, true)));
        assertEquals("NO_ROUTE", assertThrows(RoutingException.class, () -> service.route(new RouteRequest("a", "b"))).getCode());
    }

    @Test void duplicateAndOverlappingSelectorsReportTheirIndexAndPreserveAcceptedState() {
        var service = service();
        String id = service.map().roads().getFirst().id();
        var exact = update(id, 10.0, false);
        var group = update(null, 5.0, false);
        var accepted = service.updateTraffic(snapshot(exact));
        for (var updates : List.of(List.of(exact, exact), List.of(group, group), List.of(group, exact), List.of(exact, group))) {
            var error = assertThrows(RoutingException.class, () -> service.updateTraffic(snapshot(updates.toArray(TrafficSnapshot.Update[]::new))));
            assertEquals("INVALID_REQUEST", error.getCode());
            assertTrue(error.getMessage().contains("updates[1]"), error.getMessage());
            assertTrue(error.getMessage().contains("segmentId"), error.getMessage());
            assertEquals(accepted, service.trafficStatus());
        }
    }

    @Test void invalidArcIdsAndInconsistentMetadataCannotReplaceAcceptedTraffic() {
        var service = service();
        String id = service.map().roads().getFirst().id();
        var accepted = service.updateTraffic(snapshot(update(id, 10.0, false)));
        var invalid = List.of(update("arc:missing", 5.0, false), update(" ", 5.0, false),
                new TrafficSnapshot.Update("b", "a", "same-way", "driving", 5.0, false, id),
                new TrafficSnapshot.Update("a", "b", "other-way", "driving", 5.0, false, id),
                new TrafficSnapshot.Update("a", "b", "same-way", "walking", 5.0, false, id));
        for (var update : invalid) {
            var error = assertThrows(RoutingException.class, () -> service.updateTraffic(snapshot(update)));
            assertEquals("INVALID_REQUEST", error.getCode());
            assertTrue(error.getMessage().contains("updates[0]"), error.getMessage());
            assertEquals(accepted, service.trafficStatus());
        }
    }

    @Test void optionalArcIdPreservesLegacyJsonAndRoundTripsExactSelectors() throws Exception {
        var json = new ObjectMapper();
        var legacy = json.readValue("""
                {"from":"a","to":"b","sourceWayId":"same-way","mode":"driving","speedKph":10,"closed":false}
                """, TrafficSnapshot.Update.class);
        assertNull(legacy.segmentId());
        assertEquals(update(null, 10.0, false), legacy);
        var exact = update("arc:0", 10.0, false);
        assertEquals(exact, json.readValue(json.writeValueAsBytes(exact), TrafficSnapshot.Update.class));
    }

    @Test void fullBundledDrivingSnapshotAcceptsDistinctArcsEvenWhenLegacySelectorsCollide() {
        var service = new RoutingService(new GraphFileManager().loadDefaultGraph(), "OSM", Clock.fixed(NOW, ZoneOffset.UTC));
        var roads = service.map().roads().stream().filter(r -> r.speedsKph().containsKey(Edge.Mode.DRIVING)).toList();
        assertFalse(roads.isEmpty());
        assertEquals(roads.size(), roads.stream().map(RoutingService.Road::id).distinct().count());
        assertTrue(roads.stream().map(r -> List.of(r.source(), r.target(), r.sourceWayId())).distinct().count() < roads.size(),
                "The bundled fixture must exercise colliding legacy selectors");
        var updates = roads.stream().map(r -> new TrafficSnapshot.Update(r.source(), r.target(), r.sourceWayId(),
                "driving", 8.0, false, r.id())).toArray(TrafficSnapshot.Update[]::new);
        assertEquals(roads.size(), service.updateTraffic(snapshot(updates)).updates());
    }
}
