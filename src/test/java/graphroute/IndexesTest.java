package graphroute;

import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.Node;
import graphroute.routing.PlaceSearchIndex;
import graphroute.routing.RoutingException;
import graphroute.routing.RoutingService;
import graphroute.routing.SpatialIndex;
import graphroute.util.DistanceUtil;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class IndexesTest {
    @Test
    void indexedSearchMatchesBruteForceForNamesIdsRoadsAndVietnameseTextOnTenThousandPlaces() {
        Graph graph = searchableGraph();
        List<RoutingService.Place> places = places(graph);
        PlaceSearchIndex index = new PlaceSearchIndex(graph, places);
        for (String term : List.of("ĐẠI  HỌC", "nguyen trai", "Diem giao 12", "junction-01234",
                "junction-012", "VŨ TRỌNG PHỤNG", "duong vu", "01", "g", "unknown target")) {
            for (int limit : List.of(1, 7, 100)) {
                assertEquals(bruteSearch(graph, places, term, limit), index.search(term, limit).places(), term);
            }
        }
        var rare = index.search("junction-01234", 10);
        assertEquals("junction-01234", rare.places().getFirst().id());
        assertTrue(rare.candidatesExamined() < 1000,
                () -> "A selective indexed query should not scan all 10000 documents: " + rare.candidatesExamined());
        assertEquals(0, index.search("NoPlaceWithThisSubstring", 10).candidatesExamined());
        assertEquals(10000, index.search("g", 10).candidatesExamined(), "Short queries deliberately use a documented full scan");
        assertEquals(List.of(), index.search("  ", 10).places());
        assertThrows(RoutingException.class, () -> index.search("abc", 0));
        assertThrows(RoutingException.class, () -> index.search("abc", 101));
        assertThrows(RoutingException.class, () -> index.search("x".repeat(201), 10));
    }

    @Test
    void indexesKeepTheirSnapshotAndExposeImmutableSearchResults() {
        Graph graph = searchableGraph();
        List<RoutingService.Place> mutablePlaces = new ArrayList<>(places(graph));
        PlaceSearchIndex search = new PlaceSearchIndex(graph, mutablePlaces);
        SpatialIndex spatial = new SpatialIndex(mutablePlaces);
        var before = search.search("nguyen trai", 100).places();
        var nearestBefore = spatial.nearest(21, 105.8).place();
        mutablePlaces.clear();
        graph.addNode(new Node("new", "Nguyễn Trãi", 21, 105.8));
        assertEquals(before, search.search("nguyen trai", 100).places());
        assertEquals(nearestBefore, spatial.nearest(21, 105.8).place());
        assertThrows(UnsupportedOperationException.class, () -> before.clear());
    }

    @Test
    void kdTreeMatchesGreatCircleBruteForceAcrossGlobeAndVisitsOnlySmallFraction() {
        Random random = new Random(194725);
        List<RoutingService.Place> places = new ArrayList<>();
        for (int index = 0; index < 10000; index++) {
            places.add(new RoutingService.Place("p" + index, "Place " + index,
                    -89.9 + random.nextDouble() * 179.8, -180 + random.nextDouble() * 360));
        }
        SpatialIndex tree = new SpatialIndex(places);
        long examined = 0;
        for (int query = 0; query < 120; query++) {
            double lat = query < 20 ? places.get(query).latitude() : -90 + random.nextDouble() * 180;
            double lon = query < 20 ? places.get(query).longitude() : -180 + random.nextDouble() * 360;
            var actual = tree.nearest(lat, lon);
            var expected = nearestBruteForce(places, lat, lon);
            assertEquals(expected.id(), actual.place().id());
            assertEquals(distance(expected, lat, lon), actual.distanceMeters(), 1e-7);
            examined += actual.pointsExamined();
        }
        assertTrue(examined < 120L * 1000, "Exact kd-tree queries should examine less than 10% of this seeded data on average");
    }

    @Test
    void sphericalIndexHandlesAntimeridianPolarQueriesAndStableDuplicateTies() {
        List<RoutingService.Place> places = List.of(
                new RoutingService.Place("east", "East", 0, 179.9),
                new RoutingService.Place("west", "West", 0, -179.8),
                new RoutingService.Place("north", "North", 89.9, -170),
                new RoutingService.Place("south", "South", -89.9, 160),
                new RoutingService.Place("a", "Duplicate A", 10, 30),
                new RoutingService.Place("z", "Duplicate Z", 10, 30));
        SpatialIndex tree = new SpatialIndex(places);
        for (double[] query : List.of(new double[]{0, -179.99}, new double[]{89.99, 170},
                new double[]{-89.99, -179}, new double[]{10, 30}, new double[]{0, 0})) {
            assertEquals(nearestBruteForce(places, query[0], query[1]).id(), tree.nearest(query[0], query[1]).place().id());
        }
        assertEquals("east", tree.nearest(0, -179.99).place().id());
        assertEquals("a", tree.nearest(10, 30).place().id());
        assertThrows(IllegalStateException.class, () -> new SpatialIndex(List.of()).nearest(0, 0));
    }

    private static Graph searchableGraph() {
        Graph graph = new Graph();
        for (int index = 0; index < 10000; index++) {
            String id = String.format(Locale.ROOT, "junction-%05d", index);
            String name = index == 1234 ? "Đại học Nguyễn Trãi" : "Điểm giao " + index;
            graph.addNode(new Node(id, name, 21 + (index / 100) * 0.0001, 105.8 + (index % 100) * 0.0001));
        }
        graph.addEdge(new Edge(graph.getNode("junction-00000"), graph.getNode("junction-01234"), 1, "Đường Vũ Trọng Phụng"));
        graph.addEdge(new Edge(graph.getNode("junction-00001"), graph.getNode("junction-00123"), 1, "Nguyễn Trãi"));
        return graph;
    }

    private static List<RoutingService.Place> places(Graph graph) {
        return graph.getAllNodes().stream().map(node -> new RoutingService.Place(node.getId(), node.getName(), node.getLat(), node.getLng())).toList();
    }

    private static List<RoutingService.Place> bruteSearch(Graph graph, List<RoutingService.Place> places, String query, int limit) {
        String term = normalize(query);
        Map<String, TreeSet<String>> roads = new HashMap<>();
        for (Edge edge : graph.getAllEdges()) {
            roads.computeIfAbsent(edge.getSource().getId(), ignored -> new TreeSet<>()).add(normalize(edge.getRoadName()));
            roads.computeIfAbsent(edge.getDestination().getId(), ignored -> new TreeSet<>()).add(normalize(edge.getRoadName()));
        }
        return places.stream().filter(place -> (normalize(place.name()) + " " + normalize(place.id()) + " "
                        + String.join(" ", roads.getOrDefault(place.id(), new TreeSet<>()))).contains(term))
                .sorted(Comparator.comparingInt((RoutingService.Place place) -> rank(place, term))
                        .thenComparing(RoutingService.Place::name).thenComparing(RoutingService.Place::id))
                .limit(limit).toList();
    }

    private static int rank(RoutingService.Place place, String term) {
        String name = normalize(place.name());
        return normalize(place.id()).equals(term) || name.equals(term) ? 0 : name.startsWith(term) ? 1 : 2;
    }

    private static String normalize(String input) {
        return Normalizer.normalize(input.toLowerCase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").trim().replaceAll("\\s+", " ");
    }

    private static RoutingService.Place nearestBruteForce(List<RoutingService.Place> places, double lat, double lon) {
        return places.stream().min(Comparator.comparingDouble((RoutingService.Place place) -> distance(place, lat, lon))
                .thenComparing(RoutingService.Place::id)).orElseThrow();
    }

    private static double distance(RoutingService.Place place, double lat, double lon) {
        return DistanceUtil.haversine(lat, lon, place.latitude(), place.longitude()) * 1000;
    }
}
