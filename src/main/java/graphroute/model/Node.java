package graphroute.model;

import java.util.Objects;

/** Immutable map junction. Algorithm and animation state do not belong to this value. */
public final class Node {
    private final String id;
    private final String name;
    private final double lat;
    private final double lng;
    private final double x;
    private final double y;

    public Node(String id, String name, double lat, double lng) {
        this(id, name, lat, lng, 0, 0);
    }

    /** Legacy screen coordinates are read-only metadata and are never used for routing. */
    public Node(String id, String name, double lat, double lng, double x, double y) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Node id must not be blank");
        validateCoordinates(lat, lng);
        if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Coordinates must be finite");
        this.id = id;
        this.name = name == null ? "" : name;
        this.lat = lat;
        this.lng = lng;
        this.x = x;
        this.y = y;
    }

    public static void validateCoordinates(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Coordinates must be finite latitude [-90,90] and longitude [-180,180]");
        }
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public double getLat() { return lat; }
    public double getLng() { return lng; }
    public double getX() { return x; }
    public double getY() { return y; }
    public boolean hasScreenCoordinates() { return x != 0 || y != 0; }

    @Override public boolean equals(Object value) {
        return value instanceof Node other && id.equals(other.id);
    }
    @Override public int hashCode() { return Objects.hash(id); }
    @Override public String toString() {
        return "Node{id='" + id + "', name='" + name + "', lat=" + lat + ", lng=" + lng + "}";
    }
}
