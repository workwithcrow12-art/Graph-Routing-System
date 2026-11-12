package graphroute.routing;

import java.util.List;

/** Node IDs are returned by place search or nearest-node lookup. Distances use kilometres internally. */
public record RouteRequest(String origin, String destination, List<String> waypoints,
                           String mode, String algorithm, Integer alternatives, List<String> avoidRoads, String preference,
                           List<String> authorizedRestrictedWays) {
    public RouteRequest(String origin, String destination, List<String> waypoints, String mode,
                        String algorithm, Integer alternatives, List<String> avoidRoads, String preference) {
        this(origin, destination, waypoints, mode, algorithm, alternatives, avoidRoads, preference, null);
    }
    public RouteRequest(String origin, String destination, List<String> waypoints, String mode,
                        String algorithm, Integer alternatives, List<String> avoidRoads) {
        this(origin, destination, waypoints, mode, algorithm, alternatives, avoidRoads, "shortest");
    }
    public RouteRequest(String origin, String destination) {
        this(origin, destination, List.of(), "driving", "astar", 1, List.of(), "shortest");
    }
}
