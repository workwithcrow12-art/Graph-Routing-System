package graphroute;

import graphroute.io.GraphFileManager;
import graphroute.io.GraphSerializer;
import graphroute.io.OsmImporter;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.util.DistanceUtil;
import graphroute.util.OSMDataUpdater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OsmImporterTest {
    @TempDir Path temporary;

    private static Graph read(String text) throws Exception {
        return new OsmImporter().load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String way(String tags) {
        return """
                <osm version="0.6" generator="fixture">
                  <node id="1" lat="21" lon="105"/>
                  <node id="2" lat="21.001" lon="105.001"/>
                  <node id="3" lat="21" lon="105.002"/>
                  <way id="10"><nd ref="1"/><nd ref="2"/><nd ref="3"/>
                    <tag k="highway" v="residential"/><tag k="name" v="Curved Road"/>
                """ + tags + "</way></osm>";
    }

    @Test
    void xmlCompressesShapePointsAndMeasuresFullGeometry() throws Exception {
        Graph graph = read(way(""));
        assertEquals(2, graph.nodeCount());
        assertEquals(2, graph.edgeCount());
        assertNull(graph.getNode("osm:2"));
        Edge edge = graph.getNeighbors("osm:1").getFirst();
        assertEquals(3, edge.getGeometry().size());
        assertEquals("10", edge.getSourceWayId());
        double expected = DistanceUtil.haversine(21, 105, 21.001, 105.001) + DistanceUtil.haversine(21.001, 105.001, 21, 105.002);
        assertEquals(expected, edge.getWeight(), 1e-10);
        assertTrue(edge.getWeight() > DistanceUtil.haversine(21, 105, 21, 105.002));
        assertEquals(edge.getGeometry().reversed(), graph.getNeighbors("osm:3").getFirst().getGeometry());
        assertEquals("OpenStreetMap", graph.getProvenance().get("source"));
        assertTrue(graph.getProvenance().get("license").contains("ODbL"));
    }

    @ParameterizedTest
    @CsvSource({"0, false", "0, true", "128, false", "128, true", "1024, false", "1024, true"})
    void xmlDetectionAcceptsLeadingWhitespaceAndUtf8Bom(int indentation, boolean bom) throws Exception {
        String prefix = (bom ? "\uFEFF" : "") + " \t\r\n".repeat(indentation / 4);

        Graph graph = read(prefix + way(""));

        assertEquals("OSM XML", graph.getProvenance().get("format"));
        assertEquals(2, graph.edgeCount());
        assertEquals(3, graph.getNeighbors("osm:1").getFirst().getGeometry().size());
    }

    @Test
    void sharedOsmIdsSplitJunctionsWithoutConnectingGeometricCrossings() throws Exception {
        String xml = way("").replace("</osm>", """
                <node id="4" lat="21.002" lon="105.001"/>
                <node id="5" lat="21.001" lon="105"/>
                <node id="6" lat="21.001" lon="105.002"/>
                <way id="11"><nd ref="2"/><nd ref="4"/><tag k="highway" v="service"/></way>
                <way id="12"><nd ref="5"/><nd ref="6"/><tag k="highway" v="secondary"/></way>
                </osm>
                """);
        Graph graph = read(xml);
        assertEquals(6, graph.nodeCount());
        assertEquals(3, graph.getNeighbors("osm:2").size());
        assertEquals(1, graph.getNeighbors("osm:5").size());
        assertEquals("osm:6", graph.getNeighbors("osm:5").getFirst().getDestination().getId());
    }

    @Test
    void vehicleOnewayAllowsWalkingBothWaysAndBicycleException() throws Exception {
        Graph graph = read(way("<tag k=\"oneway\" v=\"yes\"/><tag k=\"oneway:bicycle\" v=\"no\"/>"));
        assertEquals(Set.of(Edge.Mode.DRIVING, Edge.Mode.MOTORCYCLING, Edge.Mode.CYCLING, Edge.Mode.WALKING), graph.getNeighbors("osm:1").getFirst().getAllowedModes());
        assertEquals(Set.of(Edge.Mode.CYCLING, Edge.Mode.WALKING), graph.getNeighbors("osm:3").getFirst().getAllowedModes());
    }

    @Test
    void reverseOnewayAndFootOnewayAreDirectionSpecific() throws Exception {
        Graph graph = read(way("<tag k=\"oneway\" v=\"-1\"/><tag k=\"oneway:foot\" v=\"yes\"/>"));
        assertEquals(Set.of(Edge.Mode.WALKING), graph.getNeighbors("osm:1").getFirst().getAllowedModes());
        assertEquals(Set.of(Edge.Mode.DRIVING, Edge.Mode.MOTORCYCLING, Edge.Mode.CYCLING), graph.getNeighbors("osm:3").getFirst().getAllowedModes());
    }

    @Test
    void specificModeAccessOverridesGeneralPrivateAccess() throws Exception {
        Graph graph = read(way("<tag k=\"access\" v=\"private\"/><tag k=\"foot\" v=\"yes\"/>"));
        assertEquals(Set.of(Edge.Mode.WALKING), graph.getNeighbors("osm:1").getFirst().getAllowedModes());
        Graph closed = read(way("<tag k=\"access\" v=\"private\"/>"));
        assertTrue(closed.isEmpty());
    }

    @Test
    void directionSpecificAccessAndSpeedLimitsAreRespected() throws Exception {
        Graph graph = read(way("<tag k=\"motor_vehicle:backward\" v=\"no\"/><tag k=\"maxspeed:forward\" v=\"10 mph\"/>"));
        assertEquals(16.09344, graph.getNeighbors("osm:1").getFirst().getSpeedKph(Edge.Mode.DRIVING), 1e-6);
        assertFalse(graph.getNeighbors("osm:3").getFirst().allows(Edge.Mode.DRIVING));
    }

    @Test
    void roadClassesAndSurfacesHaveDifferentSpeedsAndModes() throws Exception {
        Graph footway = read(way("").replace("v=\"residential\"", "v=\"footway\""));
        assertEquals(Set.of(Edge.Mode.WALKING), footway.getAllEdges().getFirst().getAllowedModes());
        Graph cycleway = read(way("").replace("v=\"residential\"", "v=\"cycleway\""));
        assertFalse(cycleway.getAllEdges().getFirst().allows(Edge.Mode.DRIVING));
        assertEquals(20, cycleway.getAllEdges().getFirst().getSpeedKph(Edge.Mode.CYCLING));
        Graph rough = read(way("<tag k=\"surface\" v=\"gravel\"/>"));
        assertEquals(10, rough.getAllEdges().getFirst().getSpeedKph(Edge.Mode.CYCLING));
        assertEquals(20, rough.getAllEdges().getFirst().getSpeedKph(Edge.Mode.DRIVING));
        Graph motorroad = read(way("<tag k=\"motorroad\" v=\"yes\"/>"));
        assertEquals(Set.of(Edge.Mode.DRIVING, Edge.Mode.MOTORCYCLING), motorroad.getAllEdges().getFirst().getAllowedModes());
    }

    @Test
    void roundaboutsAreDirectedForVehiclesAndClosedWaysKeepGeometry() throws Exception {
        String xml = way("<tag k=\"junction\" v=\"roundabout\"/>").replace("<nd ref=\"3\"/>", "<nd ref=\"3\"/><nd ref=\"1\"/>");
        Graph graph = read(xml);
        assertEquals(2, graph.nodeCount());
        assertTrue(graph.getAllEdges().stream().anyMatch(edge -> edge.getGeometry().size() == 3));
        assertEquals(2, graph.getAllEdges().stream().filter(edge -> edge.allows(Edge.Mode.DRIVING)).count());
    }

    @Test
    void overpassOutGeomNeedsNoSeparateNodeElements() throws Exception {
        Graph graph = read("""
                {"generator":"Overpass API","osm3s":{"timestamp_osm_base":"2026-01-02T03:04:05Z"},"elements":[
                  {"type":"way","id":123,"nodes":[1,2,3],"geometry":[
                    {"lat":21,"lon":105},{"lat":21.001,"lon":105.001},{"lat":21,"lon":105.002}],
                   "tags":{"highway":"residential","name":"Curved Road","oneway":"yes"}}
                ]}
                """);
        assertEquals(2, graph.nodeCount());
        assertEquals(3, graph.getAllEdges().getFirst().getGeometry().size());
        assertEquals("2026-01-02T03:04:05Z", graph.getProvenance().get("timestamp"));
        assertEquals(Set.of(Edge.Mode.WALKING), graph.getNeighbors("osm:3").getFirst().getAllowedModes());
    }

    @Test
    void overpassNodeReferencesAreSupportedAndIncompleteWaysRejected() throws Exception {
        String json = """
                {"elements":[{"type":"node","id":1,"lat":21,"lon":105},
                  {"type":"node","id":2,"lat":21,"lon":105.01},
                  {"type":"way","id":10,"nodes":[1,2],"tags":{"highway":"residential"}}]}
                """;
        assertEquals(2, read(json).nodeCount());
        assertThrows(GraphSerializer.GraphDataException.class, () -> read(json.replace("[1,2]", "[1,3]")));
        assertThrows(GraphSerializer.GraphDataException.class, () -> read(way("").replace("<node id=\"2\" lat=\"21.001\" lon=\"105.001\"/>", "")));
    }

    @Test
    void rejectsExternalEntitiesAndPartialOverpassResponses() {
        String dangerous = "<!DOCTYPE osm [<!ENTITY ext SYSTEM 'file:///nonexistent'>]><osm><node id='1' lat='0' lon='0'><tag k='name' v='&ext;'/></node></osm>";
        assertThrows(GraphSerializer.GraphDataException.class, () -> read(dangerous));
        assertThrows(GraphSerializer.GraphDataException.class, () -> read("{\"remark\":\"runtime error: timeout\",\"elements\":[]}"));
        assertThrows(GraphSerializer.GraphDataException.class, () -> read("{\"elements\":[{\"type\":\"way\",\"id\":1,\"nodes\":[1,2],\"geometry\":[],\"tags\":{\"highway\":\"residential\"}}]}"));
    }

    @Test
    void conflictingCoordinatesAndDuplicateWayIdsFail() {
        String conflicting = way("").replace("</osm>", "<node id='1' lat='22' lon='105'/></osm>");
        assertThrows(GraphSerializer.GraphDataException.class, () -> read(conflicting));
        String duplicate = way("").replace("</osm>", "<way id='10'><nd ref='1'/><nd ref='3'/><tag k='highway' v='service'/></way></osm>");
        assertThrows(GraphSerializer.GraphDataException.class, () -> read(duplicate));
    }

    @Test
    void fileManagerAutoDetectsFormatAndAnnotatesSource() throws Exception {
        Path file = temporary.resolve("roads.osm");
        Files.writeString(file, way(""));
        Graph graph = new GraphFileManager().loadOsm(file);
        assertEquals(2, graph.nodeCount());
        assertEquals("roads.osm", graph.getProvenance().get("file"));
    }

    @Test
    void legacySampleDistancesArePhysicallyConsistentAndLabeledEstimated() {
        Graph graph = new GraphFileManager().loadThanhXuanGraph();
        assertTrue(graph.getProvenance().get("source").contains("Estimated"));
        for (Edge edge : graph.getAllEdges()) assertTrue(edge.getWeight() + 1e-9 >= DistanceUtil.haversine(
                edge.getSource().getLat(), edge.getSource().getLng(), edge.getDestination().getLat(), edge.getDestination().getLng()), edge.toString());
    }

    @Test
    void downloaderRejectsUnboundedInvalidBoxesAndProducesGeometryQuery() {
        assertThrows(IllegalArgumentException.class, () -> new OSMDataUpdater.BoundingBox(20, 105, 21, 106));
        assertThrows(IllegalArgumentException.class, () -> new OSMDataUpdater.BoundingBox(21, 105, 20, 105.1));
        assertThrows(IllegalArgumentException.class, () -> new OSMDataUpdater.BoundingBox(Double.NaN, 105, 21, 105.1));
        String query = new OSMDataUpdater.BoundingBox(20.99, 105.795, 21.007, 105.82).query();
        assertTrue(query.contains("[timeout:45]"));
        assertTrue(query.endsWith("out body geom;"));
        assertTrue(query.contains("20.9900000,105.7950000,21.0070000,105.8200000"));
    }

    @Test
    void bundledRealExtractHasThousandsOfRoadsFullGeometryAndModeRestrictions() {
        Graph graph = new GraphFileManager().loadDefaultGraph();
        assertEquals("OpenStreetMap", graph.getProvenance().get("source"));
        assertTrue(graph.nodeCount() > 1000);
        assertTrue(graph.edgeCount() > 2000);
        assertTrue(Integer.parseInt(graph.getProvenance().get("sourceWays")) > 2000);
        assertTrue(graph.getProvenance().get("endpoint").contains("maps.mail.ru"));
        assertTrue(graph.getAllEdges().stream().anyMatch(edge -> edge.getGeometry().size() > 5));
        assertTrue(graph.getAllEdges().stream().anyMatch(edge -> !edge.allows(Edge.Mode.DRIVING)));
        assertTrue(graph.getAllEdges().stream().anyMatch(edge -> edge.getTags().containsKey("oneway")));
        for (Edge edge : graph.getAllEdges()) {
            double expected = 0;
            for (int i = 1; i < edge.getGeometry().size(); i++) {
                Edge.Point first = edge.getGeometry().get(i - 1), second = edge.getGeometry().get(i);
                expected += DistanceUtil.haversine(first.latitude(), first.longitude(), second.latitude(), second.longitude());
            }
            assertEquals(expected, edge.getWeight(), 1e-8);
            assertTrue(edge.getWeight() + 1e-8 >= DistanceUtil.haversine(edge.getSource().getLat(), edge.getSource().getLng(), edge.getDestination().getLat(), edge.getDestination().getLng()));
        }
    }
}
