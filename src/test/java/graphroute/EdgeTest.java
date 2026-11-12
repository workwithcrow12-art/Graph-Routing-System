package graphroute;

import graphroute.model.Edge;
import graphroute.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EdgeTest {
    private final Node origin = new Node("a", "Origin", 0, 0);
    private final Node destination = new Node("b", "Destination", 0, 0);

    static Stream<Double> invalidWeights() {
        return Stream.of(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
    }

    @ParameterizedTest
    @MethodSource("invalidWeights")
    void rejectsInvalidWeightWhenCreated(double weight) {
        assertThrows(IllegalArgumentException.class, () -> new Edge(origin, destination, weight));
    }

    @Test
    void copiesGeometryAndAccessMetadataAndExposesImmutableValues() {
        var points = new ArrayList<>(List.of(new Edge.Point(0, 0), new Edge.Point(1, 1), new Edge.Point(0, 0)));
        var speeds = new EnumMap<Edge.Mode, Double>(Edge.Mode.class);
        speeds.put(Edge.Mode.WALKING, 4.0);
        Edge edge = new Edge(origin, destination, 2.5, "Path", points, speeds, "123", Map.of("highway", "path"));
        points.clear();
        speeds.clear();
        assertEquals(3, edge.getGeometry().size());
        assertEquals(4.0, edge.getSpeedKph(Edge.Mode.WALKING));
        assertFalse(edge.allows(Edge.Mode.DRIVING));
        assertThrows(UnsupportedOperationException.class, () -> edge.getGeometry().clear());
        assertThrows(UnsupportedOperationException.class, () -> edge.getSpeedsKph().clear());
        assertThrows(UnsupportedOperationException.class, () -> edge.getTags().clear());
        assertEquals(edge.getGeometry().reversed(), edge.reverse().getGeometry());
        assertEquals("123", edge.reverse().getSourceWayId());
    }

    @Test
    void rejectsMismatchedGeometryAndInvalidSpeeds() {
        assertThrows(IllegalArgumentException.class, () -> new Edge(origin, destination, 1, "",
                List.of(new Edge.Point(1, 1), new Edge.Point(0, 0)), Map.of(Edge.Mode.WALKING, 5.0), "", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Edge(origin, destination, 1, "",
                List.of(new Edge.Point(0, 0), new Edge.Point(0, 0)), Map.of(Edge.Mode.WALKING, Double.NaN), "", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Edge(origin, destination, 1, "",
                List.of(new Edge.Point(0, 0), new Edge.Point(0, 0)), Map.of(), "", Map.of()));
    }

    @Test
    void acceptsZeroWeightAndPreservesRoadInformationWhenReversed() {
        Edge edge = new Edge(origin, destination, 0, "Main Street");
        Edge reversed = edge.reverse();
        assertEquals(destination, reversed.getSource());
        assertEquals(origin, reversed.getDestination());
        assertEquals(0.0, reversed.getWeight());
        assertEquals("Main Street", reversed.getRoadName());
    }
}
