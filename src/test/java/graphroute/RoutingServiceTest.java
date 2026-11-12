package graphroute;

import graphroute.io.GraphFileManager;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import graphroute.routing.RouteRequest;
import graphroute.routing.RoutingException;
import graphroute.routing.RoutingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RoutingServiceTest {
    private static final String ORIGIN = "tx-khuat-duy-tien-nguyen-trai";
    private static final String DESTINATION = "tx-le-van-luong-hoang-dao-thuy";

    private RoutingService sample() { return new RoutingService(new GraphFileManager().loadThanhXuanGraph()); }

    @Test void findsVietnameseNamesWithOrWithoutAccentsAndIds() {
        RoutingService service = sample();
        assertEquals(service.search("nguyễn trãi", 10), service.search("NGUYEN TRAI", 10));
        assertFalse(service.search("hoang dao thuy", 10).isEmpty());
        assertEquals(ORIGIN, service.search(ORIGIN, 1).getFirst().id());
        assertTrue(service.search("no matching place", 10).isEmpty());
        assertTrue(service.search("  ", 10).isEmpty());
        assertThrows(RoutingException.class, () -> service.search("a", 101));
    }

    @Test void findsNearestNodeAndRejectsOutOfCoverageOrInvalidCoordinates() {
        RoutingService service = sample();
        var nearest = service.nearest(20.99351, 105.80436, 100);
        assertEquals(ORIGIN, nearest.place().id());
        assertEquals(0, nearest.distanceMeters(), 0.0001);
        assertEquals("OUTSIDE_COVERAGE", assertThrows(RoutingException.class,
                () -> service.nearest(0, 0, 2000)).getCode());
        assertThrows(RoutingException.class, () -> service.nearest(Double.NaN, 0, 100));
        assertThrows(RoutingException.class, () -> service.nearest(0, 181, 100));
        assertThrows(RoutingException.class, () -> service.nearest(0, 0, 0));
    }

    @Test void providesMapBoundsAndAllDirectedArcs() {
        var map = sample().map();
        assertEquals(8, map.places().size());
        assertEquals(20, map.roads().size());
        assertTrue(map.places().stream().allMatch(p -> p.latitude() >= map.bounds().south()
                && p.latitude() <= map.bounds().north() && p.longitude() >= map.bounds().west()
                && p.longitude() <= map.bounds().east()));
    }

    @Test void producesKnownShortestRouteWithGeometryDirectionsAndEstimates() {
        var route = sample().route(new RouteRequest(ORIGIN, DESTINATION)).routes().getFirst();
        assertEquals(1935, route.distanceMeters(), 1e-9);
        assertEquals(232, route.durationSeconds());
        assertEquals(ORIGIN, route.path().getFirst().id());
        assertEquals(DESTINATION, route.path().getLast().id());
        assertEquals("LineString", route.geometry().type());
        assertEquals(List.of(105.80436, 20.99351), route.geometry().coordinates().getFirst());
        assertEquals("DEPART", route.directions().getFirst().maneuver());
        assertEquals("ARRIVE", route.directions().getLast().maneuver());
        assertEquals(route.distanceMeters(), route.directions().stream().mapToDouble(RoutingService.Direction::distanceMeters).sum(), 1e-9);
    }

    @Test void estimatesTravelModesWithoutChangingDistances() {
        RoutingService service = sample();
        var walk = service.route(request("walking", "astar", 1, List.of(), List.of())).routes().getFirst();
        var cycle = service.route(request("cycling", "dijkstra", 1, List.of(), List.of())).routes().getFirst();
        assertEquals(walk.distanceMeters(), cycle.distanceMeters(), 1e-9);
        assertEquals(1393, walk.durationSeconds());
        assertEquals(464, cycle.durationSeconds());
    }

    @Test void respectsOrderedWaypointsAndConsecutiveRepeatedStops() {
        String via = "tx-vu-trong-phung-nguyen-trai";
        var plan = sample().route(request("driving", "astar", 1, List.of(ORIGIN, via, via, DESTINATION), List.of()));
        var path = plan.routes().getFirst().path().stream().map(RoutingService.Place::id).toList();
        assertTrue(path.contains(via));
        assertEquals(ORIGIN, path.getFirst());
        assertEquals(DESTINATION, path.getLast());
        for (int i = 1; i < path.size(); i++) assertNotEquals(path.get(i - 1), path.get(i));
        assertTrue(plan.routes().getFirst().distanceMeters() > 1935);
    }

    @Test void alternativesAreDistinctAndSortedByDistance() {
        var routes = sample().route(request("driving", "astar", 3, List.of(), List.of())).routes();
        assertTrue(routes.size() > 1 && routes.size() <= 3);
        assertEquals(routes.size(), routes.stream().map(RoutingService.Route::path).distinct().count());
        for (int i = 1; i < routes.size(); i++) assertTrue(routes.get(i).distanceMeters() >= routes.get(i - 1).distanceMeters());
    }

    @Test void excludesRoadsAccentInsensitivelyAndReportsNoRoute() {
        RoutingService service = sample();
        var alternative = service.route(request("driving", "astar", 3, List.of(), List.of("khuat duy tien")));
        assertTrue(alternative.routes().getFirst().distanceMeters() > 1935);
        assertTrue(alternative.routes().stream().flatMap(r -> r.directions().stream())
                .noneMatch(d -> d.roadName().equals("Khuất Duy Tiến")));
        assertEquals("NO_ROUTE", assertThrows(RoutingException.class, () -> service.route(
                request("driving", "astar", 1, List.of(), List.of("Khuất Duy Tiến", "Nguyễn Trãi")))).getCode());
        assertThrows(RoutingException.class, () -> service.route(request("driving", "astar", 1, List.of(), List.of("typo road"))));
    }

    @Test void returnsZeroDistanceForSamePlaceWithValidGeometry() {
        var result = sample().route(new RouteRequest(ORIGIN, ORIGIN)).routes().getFirst();
        assertEquals(0, result.distanceMeters());
        assertEquals(0, result.durationSeconds());
        assertEquals(1, result.path().size());
        assertEquals(2, result.geometry().coordinates().size());
        assertEquals("ARRIVE", result.directions().getFirst().maneuver());
    }

    @Test void rejectsInvalidRouteOptionsAndMissingPlaces() {
        RoutingService service = sample();
        assertThrows(RoutingException.class, () -> service.route(null));
        assertThrows(RoutingException.class, () -> service.route(new RouteRequest(null, DESTINATION)));
        assertEquals("PLACE_NOT_FOUND", assertThrows(RoutingException.class, () -> service.route(new RouteRequest("unknown", DESTINATION))).getCode());
        assertThrows(RoutingException.class, () -> service.route(request("flying", "astar", 1, List.of(), List.of())));
        assertThrows(RoutingException.class, () -> service.route(request("driving", "bfs", 1, List.of(), List.of())));
        assertThrows(RoutingException.class, () -> service.route(request("driving", "astar", 4, List.of(), List.of())));
        assertThrows(RoutingException.class, () -> service.route(request("driving", "astar", 1,
                java.util.Collections.nCopies(9, ORIGIN), List.of())));
    }

    @Test void snapshotsInputAndSelectsCorrectParallelRoadWithoutIgnoringOneWayRules() {
        Graph graph = new Graph(GraphType.DIRECTED);
        Node a = new Node("a", "A", 0, 0), b = new Node("b", "B", 0, 0.01), c = new Node("c", "C", -0.01, 0.01);
        graph.addNode(a); graph.addNode(b); graph.addNode(c);
        graph.addEdge(new Edge(a, b, 3, "Slow"));
        graph.addEdge(new Edge(a, b, 1, "Fast"));
        graph.addEdge(new Edge(b, c, 2, "South road"));
        RoutingService service = new RoutingService(graph);
        graph.removeNode("b");
        var route = service.route(new RouteRequest("a", "c")).routes().getFirst();
        assertEquals(3000, route.distanceMeters());
        assertEquals("A", route.path().getFirst().name());
        assertEquals("Fast", route.directions().getFirst().roadName());
        assertEquals("TURN_RIGHT", route.directions().get(1).maneuver());
        assertEquals("NO_ROUTE", assertThrows(RoutingException.class, () -> service.route(new RouteRequest("c", "a"))).getCode());
    }

    @Test void everyDatasetPairMatchesDijkstraAndHasOnlyConnectedSteps() {
        RoutingService service = sample();
        Set<List<String>> arcs = service.map().roads().stream().map(r -> List.of(r.source(), r.target())).collect(Collectors.toSet());
        for (var from : service.map().places()) for (var to : service.map().places()) {
            var a = service.route(new RouteRequest(from.id(), to.id())).routes().getFirst();
            var d = service.route(new RouteRequest(from.id(), to.id(), null, null, "dijkstra", null, null)).routes().getFirst();
            assertEquals(d.distanceMeters(), a.distanceMeters(), 1e-8);
            for (int i = 1; i < a.path().size(); i++) assertTrue(arcs.contains(List.of(a.path().get(i - 1).id(), a.path().get(i).id())));
        }
    }

    private RouteRequest request(String mode, String algorithm, int count, List<String> via, List<String> avoid) {
        return new RouteRequest(ORIGIN, DESTINATION, via, mode, algorithm, count, avoid);
    }
}
