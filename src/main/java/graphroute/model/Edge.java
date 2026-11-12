package graphroute.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable directed road segment, with kilometre distance and direction-specific mode access. */
public final class Edge {
    public enum Mode { DRIVING, MOTORCYCLING, CYCLING, WALKING }
    public enum Access { PUBLIC, DESTINATION, DELIVERY, CUSTOMERS }
    public record Point(double latitude, double longitude) {
        public Point { Node.validateCoordinates(latitude, longitude); }
    }

    private final Node source;
    private final Node destination;
    private final double weight;
    private final String roadName;
    private final List<Point> geometry;
    private final Map<Mode, Double> speedsKph;
    private final String sourceWayId;
    private final Map<String, String> tags;
    private final Map<Mode, Access> access;

    public Edge(Node source, Node destination, double weight, String roadName) {
        this(source, destination, weight, roadName, endpoints(source, destination),
                Map.of(Mode.DRIVING, 30.0, Mode.MOTORCYCLING, 30.0, Mode.CYCLING, 15.0, Mode.WALKING, 5.0), "", Map.of());
    }

    public Edge(Node source, Node destination, double weight) { this(source, destination, weight, ""); }

    public Edge(Node source, Node destination, double weight, String roadName, List<Point> geometry,
                Map<Mode, Double> speedsKph, String sourceWayId, Map<String, String> tags) {
        this(source, destination, weight, roadName, geometry, speedsKph, sourceWayId, tags, Map.of());
    }

    public Edge(Node source, Node destination, double weight, String roadName, List<Point> geometry,
                Map<Mode, Double> speedsKph, String sourceWayId, Map<String, String> tags, Map<Mode, Access> access) {
        if (source == null || destination == null) throw new IllegalArgumentException("Edge endpoints are required");
        if (!Double.isFinite(weight) || weight < 0) throw new IllegalArgumentException("Edge distance must be finite and nonnegative");
        if (geometry == null || geometry.size() < 2) throw new IllegalArgumentException("Edge geometry needs at least two points");
        this.geometry = List.copyOf(geometry);
        if (!matches(this.geometry.getFirst(), source) || !matches(this.geometry.getLast(), destination)) {
            throw new IllegalArgumentException("Edge geometry endpoints must match its source and destination");
        }
        if (speedsKph == null || speedsKph.isEmpty()) throw new IllegalArgumentException("An edge must permit at least one mode");
        EnumMap<Mode, Double> speeds = new EnumMap<>(Mode.class);
        speedsKph.forEach((mode, speed) -> {
            if (mode == null || speed == null || !Double.isFinite(speed) || speed <= 0 || speed > 500) {
                throw new IllegalArgumentException("Mode speeds must be finite, positive and at most 500 km/h");
            }
            speeds.put(mode, speed);
        });
        this.source = source;
        this.destination = destination;
        this.weight = weight;
        this.roadName = roadName == null ? "" : roadName;
        this.speedsKph = Collections.unmodifiableMap(speeds);
        this.sourceWayId = sourceWayId == null ? "" : sourceWayId;
        this.tags = tags == null ? Map.of() : Map.copyOf(tags);
        EnumMap<Mode, Access> requirements = new EnumMap<>(Mode.class);
        speeds.keySet().forEach(mode -> requirements.put(mode, Objects.requireNonNull(access.getOrDefault(mode, Access.PUBLIC))));
        this.access = Collections.unmodifiableMap(requirements);
    }

    private static List<Point> endpoints(Node source, Node destination) {
        if (source == null || destination == null) throw new IllegalArgumentException("Edge endpoints are required");
        return List.of(new Point(source.getLat(), source.getLng()), new Point(destination.getLat(), destination.getLng()));
    }

    private static boolean matches(Point point, Node node) {
        return Math.abs(point.latitude() - node.getLat()) < 1e-9 && Math.abs(point.longitude() - node.getLng()) < 1e-9;
    }

    public Node getSource() { return source; }
    public Node getDestination() { return destination; }
    public double getWeight() { return weight; }
    public String getRoadName() { return roadName; }
    public List<Point> getGeometry() { return geometry; }
    public Map<Mode, Double> getSpeedsKph() { return speedsKph; }
    public Set<Mode> getAllowedModes() { return speedsKph.keySet(); }
    public boolean allows(Mode mode) { return speedsKph.containsKey(mode); }
    public double getSpeedKph(Mode mode) { return speedsKph.getOrDefault(mode, 0.0); }
    public String getSourceWayId() { return sourceWayId; }
    public Map<String, String> getTags() { return tags; }
    public Map<Mode, Access> getAccess() { return access; }
    public Access getAccess(Mode mode) { return access.getOrDefault(mode, Access.PUBLIC); }

    /** Reverse the arc with the same mode metadata; importers independently calculate asymmetric permissions. */
    public Edge reverse() {
        return new Edge(destination, source, weight, roadName, geometry.reversed(), speedsKph, sourceWayId, tags, access);
    }

    @Override public boolean equals(Object value) {
        if (!(value instanceof Edge other)) return false;
        return source.equals(other.source) && destination.equals(other.destination)
                && Double.compare(weight, other.weight) == 0 && roadName.equals(other.roadName)
                && geometry.equals(other.geometry) && speedsKph.equals(other.speedsKph)
                && sourceWayId.equals(other.sourceWayId) && tags.equals(other.tags) && access.equals(other.access);
    }
    @Override public int hashCode() {
        return Objects.hash(source, destination, weight, roadName, geometry, speedsKph, sourceWayId, tags, access);
    }
    @Override public String toString() {
        return "Edge{" + source.getId() + " -> " + destination.getId() + ", km=" + weight + ", road='" + roadName + "'}";
    }
}
