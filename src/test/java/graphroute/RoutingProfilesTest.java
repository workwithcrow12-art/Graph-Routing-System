package graphroute;

import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import graphroute.routing.RouteRequest;
import graphroute.routing.RoutingException;
import graphroute.routing.RoutingService;
import graphroute.routing.TrafficSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RoutingProfilesTest {
    private static final Instant NOW = Instant.parse("2026-10-19T06:00:00Z");

    @Test
    void modeAccessAndDirectionsProduceDifferentNetworks() {
        RoutingService service = new RoutingService(network());
        assertEquals(List.of("a", "z"), ids(route(service, "driving", "shortest")));
        assertEquals(List.of("a", "w", "z"), ids(route(service, "walking", "shortest")));
        assertEquals(List.of("a", "c", "z"), ids(route(service, "cycling", "shortest")));
        assertEquals(1000, route(service, "walking", "shortest").distanceMeters());
        assertEquals(1400, route(service, "cycling", "shortest").distanceMeters());
        assertEquals(720, route(service, "walking", "shortest").durationSeconds());
        assertEquals(360, route(service, "cycling", "shortest").durationSeconds());
        var reverseWalk = service.route(request("z", "a", "walking", "shortest", "astar", 1));
        assertEquals(List.of("z", "w", "a"), ids(reverseWalk.routes().getFirst()));
        assertEquals("NO_ROUTE", assertThrows(RoutingException.class,
                () -> service.route(request("z", "a", "driving", "shortest", "astar", 1))).getCode());
    }

    @Test
    void fastestUsesPerRoadSpeedAndPreservesKilometresInDistanceOutput() {
        RoutingService service = new RoutingService(network());
        var shortest = route(service, "driving", "shortest");
        var fastest = route(service, "driving", "fastest");
        assertEquals(List.of("a", "z"), ids(shortest));
        assertEquals(2000, shortest.distanceMeters());
        assertEquals(360, shortest.durationSeconds());
        assertEquals(List.of("a", "b", "z"), ids(fastest));
        assertEquals(3000, fastest.distanceMeters());
        assertEquals(120, fastest.durationSeconds());
        var dijkstra = service.route(request("a", "z", "driving", "fastest", "dijkstra", 3)).routes();
        var astar = service.route(request("a", "z", "driving", "fastest", "astar", 3)).routes();
        assertEquals(astar, dijkstra);
        assertEquals(List.of(120L, 360L), astar.stream().map(RoutingService.Route::durationSeconds).toList());
        assertEquals(1, astar.getFirst().sharedDistanceRatio());
        assertEquals(0, astar.get(1).sharedDistanceRatio());
    }

    @Test
    void routeRetainsIntermediateShapeCoordinatesAndJoinsLegsWithoutDuplicatedJunctions() {
        RoutingService service = new RoutingService(network());
        var direct = route(service, "driving", "shortest");
        assertEquals(List.of(List.of(105.8, 21.0), List.of(105.801, 21.003), List.of(105.804, 21.002)),
                direct.geometry().coordinates());
        var throughB = route(service, "driving", "fastest");
        assertEquals(5, throughB.geometry().coordinates().size());
        assertEquals(1, throughB.geometry().coordinates().stream().filter(point -> point.equals(List.of(105.802, 21.001))).count());
        assertEquals(throughB.durationSeconds(), throughB.directions().stream().mapToLong(RoutingService.Direction::durationSeconds).sum());
        assertEquals(throughB.distanceMeters(), throughB.directions().stream().mapToDouble(RoutingService.Direction::distanceMeters).sum());
        assertThrows(UnsupportedOperationException.class, () -> direct.geometry().coordinates().clear());
        assertEquals(3, service.map().roads().stream().filter(road -> road.sourceWayId().equals("direct")).findFirst().orElseThrow().geometry().size());
        assertTrue(service.map().bounds().north() >= 21.003, "Bounds must include road shape points beyond junction bounds");
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void repeatedJunctionCoordinatesDoNotIntroduceTurns(boolean repeatBeforeJunction, boolean repeatAfterJunction) {
        Graph graph = new Graph(GraphType.DIRECTED);
        graph.addNode(new Node("a", "Start", 0, 105));
        graph.addNode(new Node("b", "Junction", 0, 105.001));
        graph.addNode(new Node("z", "Destination", 0, 105.002));
        var junction = new Edge.Point(0, 105.001);
        add(graph, "a", "b", 0.2, Edge.Mode.DRIVING, 30, "East Road",
                repeatBeforeJunction ? new Edge.Point[]{junction, junction} : new Edge.Point[0]);
        add(graph, "b", "z", 0.2, Edge.Mode.DRIVING, 30, "East Road",
                repeatAfterJunction ? new Edge.Point[]{junction, junction} : new Edge.Point[0]);

        var directions = route(new RoutingService(graph), "driving", "shortest").directions();

        assertEquals(List.of("DEPART", "ARRIVE"), directions.stream().map(RoutingService.Direction::maneuver).toList());
        assertEquals("z", directions.getFirst().to());
        assertEquals(400, directions.getFirst().distanceMeters());
    }

    @Test
    void zeroLengthSegmentKeepsThePreviousHeadingForTheNextTurn() {
        Graph graph = new Graph(GraphType.DIRECTED);
        graph.addNode(new Node("a", "Start", 0, 105));
        graph.addNode(new Node("b", "Junction", 0, 105.001));
        graph.addNode(new Node("c", "Same position", 0, 105.001));
        graph.addNode(new Node("z", "Destination", -0.001, 105.001));
        add(graph, "a", "b", 0.2, Edge.Mode.DRIVING, 30, "East Road");
        add(graph, "b", "c", 0, Edge.Mode.DRIVING, 30, "East Road");
        add(graph, "c", "z", 0.2, Edge.Mode.DRIVING, 30, "South Road");

        var directions = route(new RoutingService(graph), "driving", "shortest").directions();

        assertEquals(List.of("DEPART", "TURN_RIGHT", "ARRIVE"),
                directions.stream().map(RoutingService.Direction::maneuver).toList());
        assertEquals("c", directions.getFirst().to());
        assertEquals("South Road", directions.get(1).roadName());
    }

    @Test
    void trafficAffectsOnlyItsModeAndAutomaticallyExpiresAtTheBoundary() {
        MutableClock clock = new MutableClock(NOW);
        RoutingService service = new RoutingService(network(), "profiles", clock);
        assertFalse(service.trafficStatus().active());
        var status = service.updateTraffic(snapshot("observed congestion", 60,
                new TrafficSnapshot.Update("a", "b", "fast-first", "driving", 5.0, false)));
        assertTrue(status.active());
        assertEquals(List.of("a", "z"), ids(route(service, "driving", "fastest")));
        assertEquals(360, route(service, "driving", "fastest").durationSeconds());
        assertEquals(720, route(service, "walking", "fastest").durationSeconds());
        clock.set(NOW.plusSeconds(59));
        assertTrue(service.trafficStatus().active());
        clock.set(NOW.plusSeconds(60));
        assertFalse(service.trafficStatus().active());
        assertEquals(List.of("a", "b", "z"), ids(route(service, "driving", "fastest")));
        assertEquals(120, route(service, "driving", "fastest").durationSeconds());
    }

    @Test
    void clearingTrafficRestoresBaseRoutingAndKeepsTheObservationOrder() {
        RoutingService service = new RoutingService(network(), "profiles", Clock.fixed(NOW, ZoneOffset.UTC));
        TrafficSnapshot delayed = new TrafficSnapshot("congestion", NOW.minusSeconds(1).toString(),
                NOW.plusSeconds(60).toString(), List.of(
                new TrafficSnapshot.Update("a", "b", "fast-first", "driving", 5.0, false)));
        assertTrue(service.updateTraffic(delayed).active());
        assertEquals(360, route(service, "driving", "fastest").durationSeconds());

        var cleared = service.updateTraffic(snapshot("cleared", 60));

        assertFalse(cleared.active());
        assertEquals(0, cleared.updates());
        assertEquals(cleared, service.route(request("a", "z", "driving", "fastest", "astar", 1)).traffic());
        assertEquals(120, route(service, "driving", "fastest").durationSeconds());
        assertThrows(RoutingException.class, () -> service.updateTraffic(delayed));
        assertEquals(cleared, service.trafficStatus());
    }

    @Test
    void closedRoadIsExcludedForEveryPreferenceAndFailedBatchIsAtomic() {
        RoutingService service = new RoutingService(network(), "profiles", Clock.fixed(NOW, ZoneOffset.UTC));
        TrafficSnapshot accepted = snapshot("closure", 120,
                new TrafficSnapshot.Update("a", "z", "direct", "driving", null, true));
        service.updateTraffic(accepted);
        for (String preference : List.of("shortest", "fastest")) {
            assertEquals(List.of("a", "b", "z"), ids(route(service, "driving", preference)));
        }
        TrafficSnapshot invalid = snapshot("invalid batch", 120,
                new TrafficSnapshot.Update("a", "b", "fast-first", "driving", null, true),
                new TrafficSnapshot.Update("missing", "z", null, "driving", 5.0, false));
        assertThrows(RoutingException.class, () -> service.updateTraffic(invalid));
        assertEquals("closure", service.trafficStatus().source());
        assertEquals(120, route(service, "driving", "fastest").durationSeconds());
        assertThrows(RoutingException.class, () -> service.updateTraffic(snapshot("overlap", 120,
                new TrafficSnapshot.Update("a", "b", null, "driving", 5.0, false),
                new TrafficSnapshot.Update("a", "b", "fast-first", "driving", 10.0, false))));
        assertEquals("closure", service.trafficStatus().source());
    }

    @Test
    void rejectsStaleObservationsAndClampsTrafficToRoadMaximumSpeed() {
        RoutingService service = new RoutingService(network(), "profiles", Clock.fixed(NOW, ZoneOffset.UTC));
        service.updateTraffic(snapshot("too optimistic", 120,
                new TrafficSnapshot.Update("a", "z", "direct", "driving", 200.0, false)));
        assertEquals(360, route(service, "driving", "shortest").durationSeconds());
        TrafficSnapshot older = new TrafficSnapshot("older", NOW.minusSeconds(1).toString(), NOW.plusSeconds(60).toString(), List.of());
        assertThrows(RoutingException.class, () -> service.updateTraffic(older));
        assertThrows(RoutingException.class, () -> service.updateTraffic(new TrafficSnapshot("expired",
                NOW.minusSeconds(60).toString(), NOW.toString(), List.of())));
        assertEquals("too optimistic", service.trafficStatus().source());
    }

    @Test
    void concurrentQueriesObserveOneCompleteTrafficSnapshot() throws Exception {
        RoutingService service = new RoutingService(network(), "profiles", Clock.fixed(NOW, ZoneOffset.UTC));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> {
                start.await();
                for (int index = 0; index < 60; index++) {
                    service.updateTraffic(index % 2 == 0 ? snapshot("slow", 120,
                            new TrafficSnapshot.Update("a", "b", "fast-first", "driving", 5.0, false))
                            : snapshot("normal", 120));
                }
                return null;
            });
            var reader = executor.submit(() -> {
                start.await();
                for (int index = 0; index < 300; index++) {
                    var plan = service.route(request("a", "z", "driving", "fastest", "astar", 1));
                    boolean slow = plan.traffic().source().equals("slow");
                    assertEquals(slow ? 360 : 120, plan.routes().getFirst().durationSeconds());
                    assertEquals(slow ? List.of("a", "z") : List.of("a", "b", "z"), ids(plan.routes().getFirst()));
                }
                return null;
            });
            start.countDown();
            writer.get();
            reader.get();
        }
    }

    private static RoutingService.Route route(RoutingService service, String mode, String preference) {
        return service.route(request("a", "z", mode, preference, "astar", 1)).routes().getFirst();
    }
    private static RouteRequest request(String from, String to, String mode, String preference, String algorithm, int count) {
        return new RouteRequest(from, to, List.of(), mode, algorithm, count, List.of(), preference);
    }
    private static List<String> ids(RoutingService.Route route) { return route.path().stream().map(RoutingService.Place::id).toList(); }
    private static TrafficSnapshot snapshot(String source, int expiresIn, TrafficSnapshot.Update... updates) {
        return new TrafficSnapshot(source, NOW.toString(), NOW.plusSeconds(expiresIn).toString(), List.of(updates));
    }
    private static Graph network() {
        Graph graph = new Graph(GraphType.DIRECTED);
        graph.addNode(new Node("a", "Start", 21, 105.8));
        graph.addNode(new Node("b", "Fast road junction", 21.001, 105.802));
        graph.addNode(new Node("w", "Footpath junction", 21.0005, 105.803));
        graph.addNode(new Node("c", "Cycleway junction", 21.0015, 105.801));
        graph.addNode(new Node("z", "Destination", 21.002, 105.804));
        add(graph, "a", "z", 2, Edge.Mode.DRIVING, 20, "direct", new Edge.Point(21.003, 105.801));
        add(graph, "a", "b", 1.5, Edge.Mode.DRIVING, 90, "fast-first", new Edge.Point(21.0003, 105.801));
        add(graph, "b", "z", 1.5, Edge.Mode.DRIVING, 90, "fast-second", new Edge.Point(21.0013, 105.803));
        add(graph, "a", "w", 0.5, Edge.Mode.WALKING, 5, "walk-first");
        add(graph, "w", "z", 0.5, Edge.Mode.WALKING, 5, "walk-second");
        add(graph, "z", "w", 0.5, Edge.Mode.WALKING, 5, "walk-second");
        add(graph, "w", "a", 0.5, Edge.Mode.WALKING, 5, "walk-first");
        add(graph, "a", "c", 0.7, Edge.Mode.CYCLING, 14, "cycle-first");
        add(graph, "c", "z", 0.7, Edge.Mode.CYCLING, 14, "cycle-second");
        return graph;
    }
    private static void add(Graph graph, String from, String to, double kilometres, Edge.Mode mode,
                            double speed, String way, Edge.Point... middle) {
        Node source = graph.getNode(from), destination = graph.getNode(to);
        var geometry = new java.util.ArrayList<Edge.Point>();
        geometry.add(new Edge.Point(source.getLat(), source.getLng()));
        geometry.addAll(List.of(middle));
        geometry.add(new Edge.Point(destination.getLat(), destination.getLng()));
        graph.addEdge(new Edge(source, destination, kilometres, way, geometry, Map.of(mode, speed), way, Map.of()));
    }
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant now) { this.now = new AtomicReference<>(now); }
        void set(Instant value) { now.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
