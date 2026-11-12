package graphroute.routing;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.shortestpath.PreparedShortestPath;
import graphroute.algorithm.shortestpath.SearchBudget;
import graphroute.algorithm.shortestpath.YenKShortestPaths;
import graphroute.model.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/** Indexed immutable network with reusable routing engines and atomic traffic observations. */
public final class RoutingService {
    public record Place(String id, String name, double latitude, double longitude, List<String> modes,
                        List<String> mainComponentModes, String displayName) {
        public Place(String id, String name, double latitude, double longitude) {
            this(id, name, latitude, longitude, List.of(), List.of(), name);
        }
    }
    public record NearbyPlace(Place place, double distanceMeters) { }
    public record Road(String source, String target, String name, double distanceMeters,
                       List<Edge.Point> geometry, Map<Edge.Mode, Double> speedsKph, String sourceWayId,
                       Map<Edge.Mode, Edge.Access> access, String id) { }
    public record Bounds(double south, double west, double north, double east) { }
    public record MapData(String dataset, String graphType, List<Place> places, List<Road> roads,
                          Bounds bounds, String distanceUnit, String coverageNote, Map<String, String> provenance) { }
    public record Geometry(String type, List<List<Double>> coordinates) { }
    public record Direction(String maneuver, String instruction, String roadName, String from, String to,
                            double distanceMeters, long durationSeconds) { }
    public record Segment(String id, String source, String target, String roadName, String sourceWayId,
                          double distanceMeters) { }
    public record Route(double distanceMeters, long durationSeconds, List<Place> path,
                        Geometry geometry, List<Direction> directions, double sharedDistanceRatio,
                        List<Segment> segments, boolean sameNodeSequenceAsPrimary) { }
    public record TrafficStatus(boolean active, String source, String observedAt, String expiresAt, int updates) { }
    public record RoutePlan(String dataset, String mode, String algorithm, String preference, List<String> waypoints,
                            List<Route> routes, TrafficStatus traffic, List<String> notices, String alternativeIdentity) { }
    private record Engines(PreparedShortestPath astar, PreparedShortestPath dijkstra) {
        PreparedShortestPath select(String algorithm) { return algorithm.equals("astar") ? astar : dijkstra; }
    }
    private record Profile(Graph graph, Engines shortest, Engines fastest, Map<Edge, Double> seconds, Set<Edge> closed) { }
    private record TrafficState(TrafficSnapshot snapshot, Map<TravelMode, Profile> profiles) { }
    private final Graph network;
    private final String datasetName;
    private final PlaceSearchIndex searchIndex;
    private final SpatialIndex spatialIndex;
    private final Map<String, Place> placesById;
    private final MapData mapData;
    private final Map<TravelMode, Profile> baseProfiles;
    private final Map<TravelMode, Map<String, Set<String>>> restrictedWayNodes;
    private final Map<TravelMode, SpatialIndex> modeSpatialIndexes;
    private final Map<TravelMode, Map<List<String>, List<Edge>>> trafficRoadIndex;
    private final Map<Edge, String> normalizedRoads;
    private final Map<Edge, String> segmentIds;
    private final Map<String, Edge> segmentsById;
    private final Set<String> roadNames;
    private final Clock clock;
    private volatile TrafficState traffic;

    public RoutingService(Graph graph) { this(graph, "Custom road network"); }
    public RoutingService(Graph graph, String name) { this(graph, name, Clock.systemUTC()); }
    public RoutingService(Graph graph, String name, Clock clock) {
        if (graph == null || graph.isEmpty()) throw new IllegalArgumentException("A nonempty road network is required");
        network = graph.snapshot();
        datasetName = Objects.requireNonNull(name);
        this.clock = Objects.requireNonNull(clock);
        Map<TravelMode, Set<String>> mainComponents = new EnumMap<>(TravelMode.class);
        Map<String, Set<String>> publicModes = new HashMap<>();
        for (TravelMode mode : TravelMode.values()) {
            mainComponents.put(mode, PublicRoadComponents.largest(network, edgeMode(mode)));
            for (Edge edge : network.getAllEdges()) if (edge.allows(edgeMode(mode)) && edge.getAccess(edgeMode(mode)) == Edge.Access.PUBLIC) {
                publicModes.computeIfAbsent(edge.getSource().getId(), key -> new TreeSet<>()).add(mode.name().toLowerCase(Locale.ROOT));
                publicModes.computeIfAbsent(edge.getDestination().getId(), key -> new TreeSet<>()).add(mode.name().toLowerCase(Locale.ROOT));
            }
        }
        Map<String, Long> nameCounts = network.getAllNodes().stream().collect(java.util.stream.Collectors.groupingBy(Node::getName, java.util.stream.Collectors.counting()));
        List<Place> places = network.getAllNodes().stream().map(node -> new Place(node.getId(), node.getName(), node.getLat(), node.getLng(),
                        List.copyOf(publicModes.getOrDefault(node.getId(), Set.of())),
                        Arrays.stream(TravelMode.values()).filter(m -> mainComponents.get(m).contains(node.getId())).map(m -> m.name().toLowerCase(Locale.ROOT)).toList(),
                        nameCounts.get(node.getName()) > 1 || node.getName().startsWith("OSM junction ")
                                ? String.format(Locale.ROOT, "%s (%.5f, %.5f; %s)", node.getName(), node.getLat(), node.getLng(), node.getId()) : node.getName()))
                .sorted(Comparator.comparing(Place::name).thenComparing(Place::id)).toList();
        placesById = places.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Place::id, p -> p));
        searchIndex = new PlaceSearchIndex(network, places);
        spatialIndex = new SpatialIndex(places);
        Map<Edge, String> normalized = new IdentityHashMap<>();
        Map<Edge, String> ids = new IdentityHashMap<>();
        network.getAllEdges().forEach(e -> ids.putIfAbsent(e, "arc:" + ids.size()));
        segmentIds = Collections.unmodifiableMap(ids);
        Map<String, Edge> byId = new HashMap<>();
        ids.forEach((edge, id) -> byId.put(id, edge));
        segmentsById = Map.copyOf(byId);
        network.getAllEdges().forEach(e -> normalized.put(e, PlaceSearchIndex.normalize(e.getRoadName())));
        normalizedRoads = Collections.unmodifiableMap(normalized);
        roadNames = Set.copyOf(normalized.values());
        EnumMap<TravelMode, Profile> profiles = new EnumMap<>(TravelMode.class);
        for (TravelMode mode : TravelMode.values()) {
            Graph modeGraph = new Graph(GraphType.DIRECTED);
            network.getAllNodes().forEach(modeGraph::addNode);
            network.getAllEdges().stream().filter(e -> e.allows(edgeMode(mode))).forEach(modeGraph::addEdge);
            modeGraph.freeze();
            Map<Edge, Double> seconds = new IdentityHashMap<>();
            modeGraph.getAllEdges().forEach(e -> seconds.put(e, 3600 * e.getWeight() / e.getSpeedKph(edgeMode(mode))));
            profiles.put(mode, new Profile(modeGraph, engines(modeGraph, Edge::getWeight),
                    engines(modeGraph, seconds::get), Collections.unmodifiableMap(seconds), Set.of()));
        }
        baseProfiles = Map.copyOf(profiles);
        Map<TravelMode, Map<String, Set<String>>> restricted = new EnumMap<>(TravelMode.class);
        Map<TravelMode, SpatialIndex> spatial = new EnumMap<>(TravelMode.class);
        Map<TravelMode, Map<List<String>, List<Edge>>> trafficLookup = new EnumMap<>(TravelMode.class);
        for (TravelMode mode : TravelMode.values()) {
            Map<String, Set<String>> ways = new HashMap<>();
            Set<String> publicNodes = new HashSet<>();
            Map<List<String>, List<Edge>> arcs = new HashMap<>();
            for (Edge edge : profiles.get(mode).graph().getAllEdges()) {
                for (List<String> key : List.of(List.of(edge.getSource().getId(), edge.getDestination().getId()),
                        List.of(edge.getSource().getId(), edge.getDestination().getId(), edge.getSourceWayId())))
                    arcs.computeIfAbsent(key, ignored -> new ArrayList<>()).add(edge);
                if (edge.getAccess(edgeMode(mode)) == Edge.Access.PUBLIC) {
                    publicNodes.add(edge.getSource().getId()); publicNodes.add(edge.getDestination().getId());
                } else {
                    Set<String> nodes = ways.computeIfAbsent(edge.getSourceWayId(), key -> new HashSet<>());
                    nodes.add(edge.getSource().getId()); nodes.add(edge.getDestination().getId());
                }
            }
            Map<String, Set<String>> frozenWays = new HashMap<>();
            ways.forEach((key, value) -> frozenWays.put(key, Set.copyOf(value)));
            restricted.put(mode, Map.copyOf(frozenWays));
            arcs.replaceAll((key, values) -> List.copyOf(values));
            trafficLookup.put(mode, Map.copyOf(arcs));
            // Prefer a mutually reachable component. For a purely one-way network, retain
            // all public endpoints instead of choosing an arbitrary singleton component.
            Set<String> snapNodes = mainComponents.get(mode).size() > 1 ? mainComponents.get(mode) : publicNodes;
            if (!snapNodes.isEmpty()) spatial.put(mode, new SpatialIndex(places.stream().filter(p -> snapNodes.contains(p.id())).toList()));
        }
        restrictedWayNodes = Map.copyOf(restricted);
        modeSpatialIndexes = Map.copyOf(spatial);
        trafficRoadIndex = Map.copyOf(trafficLookup);
        DoubleSummaryStatistics latitudes = new DoubleSummaryStatistics();
        DoubleSummaryStatistics longitudes = new DoubleSummaryStatistics();
        places.forEach(p -> { latitudes.accept(p.latitude()); longitudes.accept(p.longitude()); });
        network.getAllEdges().forEach(e -> e.getGeometry().forEach(p -> {
            latitudes.accept(p.latitude()); longitudes.accept(p.longitude());
        }));
        Bounds bounds = new Bounds(latitudes.getMin(), longitudes.getMin(), latitudes.getMax(), longitudes.getMax());
        mapData = new MapData(name, "DIRECTED", places, network.getAllEdges().stream()
                .map(e -> new Road(e.getSource().getId(), e.getDestination().getId(), e.getRoadName(),
                        e.getWeight() * 1000, e.getGeometry(), e.getSpeedsKph(), e.getSourceWayId(), e.getAccess(), segmentIds.get(e))).toList(), bounds,
                "meters", "Coverage is limited to the loaded extract; consult provenance for source and limitations.", network.getProvenance());
    }

    /** Immutable network shared with independently configured analysis clients. */
    public Graph network() { return network; }
    public MapData map() { return mapData; }

    /** Conservative viewport filter: include roads whose geometry bounds intersect the box. */
    public MapData map(Bounds box) {
        if (box == null || !Double.isFinite(box.south()) || !Double.isFinite(box.west())
                || !Double.isFinite(box.north()) || !Double.isFinite(box.east())
                || box.south() < -90 || box.north() > 90 || box.west() < -180 || box.east() > 180
                || box.south() > box.north() || box.west() > box.east())
            throw new IllegalArgumentException("bbox must be south,west,north,east within coordinate ranges (no antimeridian wrapping)");
        List<Road> roads = mapData.roads().stream().filter(road -> {
            double south = 90, west = 180, north = -90, east = -180;
            for (Edge.Point point : road.geometry()) {
                south = Math.min(south, point.latitude()); north = Math.max(north, point.latitude());
                west = Math.min(west, point.longitude()); east = Math.max(east, point.longitude());
            }
            return south <= box.north() && north >= box.south() && west <= box.east() && east >= box.west();
        }).toList();
        Set<String> endpoints = new HashSet<>();
        roads.forEach(road -> { endpoints.add(road.source()); endpoints.add(road.target()); });
        List<Place> places = mapData.places().stream().filter(place -> endpoints.contains(place.id())
                || place.latitude() >= box.south() && place.latitude() <= box.north()
                && place.longitude() >= box.west() && place.longitude() <= box.east()).toList();
        return new MapData(mapData.dataset(), mapData.graphType(), places, roads, mapData.bounds(),
                mapData.distanceUnit(), mapData.coverageNote(), mapData.provenance());
    }
    public List<Place> search(String query, int limit) { return search(query, limit, 0, "driving").places(); }
    public PlaceSearchIndex.SearchResult search(String query, int limit, int offset, String mode) {
        String selected = mode == null ? "driving" : mode;
        if (selected.equals("all")) return searchIndex.search(query, limit, offset, p -> true);
        String parsed = TravelMode.parse(selected).name().toLowerCase(Locale.ROOT);
        return searchIndex.search(query, limit, offset, p -> p.modes().contains(parsed));
    }
    public PlaceSearchIndex.SearchResult searchDiagnostics(String query, int limit) { return searchIndex.search(query, limit); }
    public SpatialIndex.Match nearestDiagnostics(double latitude, double longitude) {
        validateCoordinates(latitude, longitude);
        return spatialIndex.nearest(latitude, longitude);
    }
    public NearbyPlace nearest(double latitude, double longitude, double maxDistanceMeters) {
        return nearest(latitude, longitude, maxDistanceMeters, null);
    }
    public NearbyPlace nearest(double latitude, double longitude, double maxDistanceMeters, String mode) {
        validateCoordinates(latitude, longitude);
        if (!Double.isFinite(maxDistanceMeters) || maxDistanceMeters <= 0 || maxDistanceMeters > 100000)
            throw invalid("maxDistanceMeters must be greater than 0 and at most 100000");
        SpatialIndex index = "all".equals(mode) ? spatialIndex : modeSpatialIndexes.get(TravelMode.parse(mode == null ? "driving" : mode));
        if (index == null) throw new RoutingException("OUTSIDE_COVERAGE", "No public road junction supports this mode");
        var result = index.nearest(latitude, longitude);
        if (result.distanceMeters() > maxDistanceMeters)
            throw new RoutingException("OUTSIDE_COVERAGE", "No stored junction within the requested radius");
        return new NearbyPlace(result.place(), result.distanceMeters());
    }

    public synchronized TrafficStatus updateTraffic(TrafficSnapshot supplied) {
        if (supplied == null) throw invalid("Traffic snapshot is required");
        TrafficSnapshot snapshot = supplied.validated(clock.instant());
        if (traffic != null && Instant.parse(snapshot.observedAt()).isBefore(Instant.parse(traffic.snapshot().observedAt())))
            throw invalid("A traffic snapshot cannot be older than the previously accepted observation");
        Map<TravelMode, Map<Edge, TrafficSnapshot.Update>> matched = new EnumMap<>(TravelMode.class);
        for (TravelMode mode : TravelMode.values()) matched.put(mode, new IdentityHashMap<>());
        for (int index = 0; index < snapshot.updates().size(); index++) {
            var update = snapshot.updates().get(index);
            TravelMode mode = TravelMode.parse(update.mode());
            List<Edge> edges;
            if (update.segmentId() != null) {
                Edge edge = segmentsById.get(update.segmentId());
                if (edge == null || !edge.allows(edgeMode(mode))
                        || !edge.getSource().getId().equals(update.from()) || !edge.getDestination().getId().equals(update.to())
                        || (update.sourceWayId() != null && !edge.getSourceWayId().equals(update.sourceWayId()))) {
                    throw invalid("Traffic updates[" + index + "] has an unknown, disallowed or mismatched segment: " + TrafficSnapshot.describe(update));
                }
                edges = List.of(edge);
            } else {
                List<String> key = update.sourceWayId() == null ? List.of(update.from(), update.to())
                        : List.of(update.from(), update.to(), update.sourceWayId());
                edges = trafficRoadIndex.get(mode).getOrDefault(key, List.of());
            }
            if (edges.isEmpty()) throw invalid("Traffic updates[" + index + "] references an unknown or disallowed road: " + TrafficSnapshot.describe(update));
            for (Edge edge : edges) {
                var existing = matched.get(mode).put(edge, update);
                if (existing != null && existing != update) throw invalid("Overlapping traffic updates at updates[" + index
                        + "] for segmentId=" + segmentIds.get(edge) + " (" + TrafficSnapshot.describe(update)
                        + "); do not mix a group selector with an individual update for the same arc/mode");
            }
        }
        Map<TravelMode, Profile> profiles = new EnumMap<>(TravelMode.class);
        for (TravelMode mode : TravelMode.values()) {
            Profile base = baseProfiles.get(mode);
            if (matched.get(mode).isEmpty()) { profiles.put(mode, base); continue; }
            Map<Edge, Double> seconds = new IdentityHashMap<>(base.seconds());
            Set<Edge> closed = Collections.newSetFromMap(new IdentityHashMap<>());
            matched.get(mode).forEach((edge, update) -> {
                if (update.closed()) closed.add(edge);
                if (update.speedKph() != null) {
                    double speed = Math.min(edge.getSpeedKph(edgeMode(mode)), update.speedKph());
                    seconds.put(edge, 3600 * edge.getWeight() / speed);
                }
            });
            profiles.put(mode, new Profile(base.graph(), base.shortest(), engines(base.graph(), seconds::get),
                    Collections.unmodifiableMap(seconds), Collections.unmodifiableSet(closed)));
        }
        traffic = new TrafficState(snapshot, Map.copyOf(profiles));
        return trafficStatus();
    }

    public TrafficStatus trafficStatus() { return status(traffic); }
    private TrafficStatus status(TrafficState state) {
        if (state == null) return new TrafficStatus(false, "none", null, null, 0);
        TrafficSnapshot value = state.snapshot();
        int count = value.updates().size();
        return new TrafficStatus(count > 0 && !value.expired(clock.instant()),
                value.source(), value.observedAt(), value.expiresAt(), count);
    }

    public RoutePlan route(RouteRequest request) {
        return route(request, SearchBudget.routing());
    }

    RoutePlan route(RouteRequest request, SearchBudget budget) {
        try {
            return routeWithinBudget(request, budget);
        } catch (SearchBudget.LimitExceededException exception) {
            throw new RoutingException("ROUTE_LIMIT_EXCEEDED", exception.getMessage());
        }
    }

    private RoutePlan routeWithinBudget(RouteRequest request, SearchBudget budget) {
        budget.check();
        if (request == null) throw invalid("A route request is required");
        requirePlace(request.origin()); requirePlace(request.destination());
        List<String> via = request.waypoints() == null ? List.of() : request.waypoints();
        if (via.size() > 8) throw invalid("At most 8 ordered waypoints are supported");
        via.forEach(this::requirePlace);
        TravelMode mode = TravelMode.parse(request.mode());
        String algorithm = request.algorithm() == null ? "astar" : request.algorithm().toLowerCase(Locale.ROOT);
        if (!Set.of("astar", "dijkstra").contains(algorithm))
            throw invalid("algorithm must be astar or dijkstra; other algorithms use /api/algorithms/run");
        String preference = request.preference() == null ? "shortest" : request.preference().toLowerCase(Locale.ROOT);
        if (!Set.of("shortest", "fastest").contains(preference)) throw invalid("preference must be shortest or fastest");
        int count = request.alternatives() == null ? 1 : request.alternatives();
        if (count < 1 || count > 3) throw invalid("alternatives must be between 1 and 3, including the primary route");
        Set<String> avoided = new HashSet<>();
        if (request.avoidRoads() != null) {
            if (request.avoidRoads().size() > 50) throw invalid("At most 50 avoided roads are supported");
            for (String name : request.avoidRoads()) {
                if (name == null || name.isBlank()) throw invalid("Avoided roads cannot be blank");
                String normalized = PlaceSearchIndex.normalize(name);
                if (!roadNames.contains(normalized)) throw invalid("Unknown road to avoid: " + name);
                avoided.add(normalized);
            }
        }
        TrafficState captured = traffic;
        TrafficStatus trafficStatus = status(captured);
        Profile profile = (trafficStatus.active() ? captured.profiles() : baseProfiles).get(mode);
        PreparedShortestPath engine = (preference.equals("fastest") ? profile.fastest() : profile.shortest()).select(algorithm);
        Predicate<Edge> allowed = e -> !profile.closed().contains(e) && !avoided.contains(normalizedRoads.get(e));
        List<String> stops = new ArrayList<>(); stops.add(request.origin()); stops.addAll(via); stops.add(request.destination());
        Set<String> authorized = new HashSet<>();
        if (request.authorizedRestrictedWays() != null) {
            if (request.authorizedRestrictedWays().size() > 50) throw invalid("At most 50 authorizedRestrictedWays are supported");
            for (String way : request.authorizedRestrictedWays()) {
                Set<String> nodes = way == null ? null : restrictedWayNodes.get(mode).get(way);
                if (nodes == null || way.isBlank() || Collections.disjoint(nodes, stops))
                    throw invalid("An authorized restricted way must exist for this mode and contain an origin, destination or waypoint");
                authorized.add(way);
            }
        }
        List<List<AlgorithmResult>> legs = new ArrayList<>();
        YenKShortestPaths yen = new YenKShortestPaths(engine);
        for (int i = 1; i < stops.size(); i++) {
            String from = stops.get(i - 1), to = stops.get(i);
            Predicate<Edge> legAllowed = edge -> allowed.test(edge) && (edge.getAccess(edgeMode(mode)) == Edge.Access.PUBLIC
                    || (authorized.contains(edge.getSourceWayId())
                    && (restrictedWayNodes.get(mode).get(edge.getSourceWayId()).contains(from)
                    || restrictedWayNodes.get(mode).get(edge.getSourceWayId()).contains(to))));
            var routes = yen.execute(from, to, count, legAllowed, budget);
            if (routes.isEmpty()) throw new RoutingException("NO_ROUTE", "No route connects the requested stops for this mode and road restrictions");
            legs.add(routes);
        }
        List<AlgorithmResult> choices = combineLegs(legs, count, budget);
        List<Route> routes = choices.stream().map(r -> describe(r, choices.getFirst(), profile, budget)).toList();
        budget.check();
        return new RoutePlan(datasetName, mode.name().toLowerCase(Locale.ROOT), algorithm, preference, List.copyOf(via),
                routes, trafficStatus, List.of(
                "Speed estimates use road/mode metadata and any current traffic observations.",
                "Restricted destination/delivery/customers ways require explicit authorization and a stop on that way; private/no remain excluded.",
                "No automatic live traffic provider is connected. Expired traffic observations are ignored.",
                "Yen returns the first k loopless routes for each leg; ordered-waypoint routes can revisit nodes across legs.",
                "Alternatives are directed-edge-distinct; parallel OSM ways can share node sequences. Inspect segments and sameNodeSequenceAsPrimary.",
                "Coverage, geometry accuracy and unsupported access/turn restrictions are described in dataset provenance."),
                "directed-edge-sequence");
    }

    /** Top k Cartesian sums of sorted per-leg paths, without exponential waypoint enumeration. */
    private static List<AlgorithmResult> combineLegs(List<List<AlgorithmResult>> legs, int count, SearchBudget budget) {
        record Combination(List<Integer> indexes, double cost) { }
        PriorityQueue<Combination> queue = new PriorityQueue<>(Comparator.comparingDouble(Combination::cost)
                .thenComparing(c -> c.indexes().toString()));
        List<Integer> initial = Collections.nCopies(legs.size(), 0);
        queue.add(new Combination(initial, legs.stream().mapToDouble(l -> l.getFirst().getTotalCost()).sum()));
        Set<List<Integer>> seen = new HashSet<>(); seen.add(initial);
        List<AlgorithmResult> result = new ArrayList<>();
        while (!queue.isEmpty() && result.size() < count) {
            budget.check();
            Combination choice = queue.remove();
            List<Node> nodes = new ArrayList<>(); List<Edge> edges = new ArrayList<>();
            for (int i = 0; i < legs.size(); i++) {
                AlgorithmResult leg = legs.get(i).get(choice.indexes().get(i));
                budget.path(leg.getPath().size());
                nodes.addAll(leg.getPath().subList(nodes.isEmpty() ? 0 : 1, leg.getPath().size()));
                edges.addAll(leg.getEdges());
                List<Integer> next = new ArrayList<>(choice.indexes());
                int rank = next.get(i) + 1;
                if (rank < legs.get(i).size()) {
                    next.set(i, rank);
                    List<Integer> indexes = List.copyOf(next);
                    if (seen.add(indexes)) {
                        double cost = 0;
                        for (int j = 0; j < legs.size(); j++) cost += legs.get(j).get(next.get(j)).getTotalCost();
                        queue.add(new Combination(indexes, cost));
                    }
                }
            }
            result.add(new AlgorithmResult(AlgorithmResult.Status.SUCCESS, nodes, edges, choice.cost(), List.of()));
        }
        return result;
    }

    private Route describe(AlgorithmResult result, AlgorithmResult primary, Profile profile, SearchBudget budget) {
        List<Direction> directions = new ArrayList<>();
        List<List<Double>> coordinates = new ArrayList<>();
        Set<Edge> primaryEdges = Collections.newSetFromMap(new IdentityHashMap<>()); primaryEdges.addAll(primary.getEdges());
        double distance = 0, seconds = 0, shared = 0, lastBearing = Double.NaN;
        for (Edge edge : result.getEdges()) {
            budget.work();
            List<Edge.Point> geometry = edge.getGeometry();
            budget.path(geometry.size());
            Bearings bearings = appendGeometry(geometry, coordinates, budget);
            String maneuver = directions.isEmpty() ? "DEPART" : maneuver(lastBearing, bearings.start());
            String road = edge.getRoadName().isBlank() ? "unnamed road" : edge.getRoadName();
            String text = switch (maneuver) {
                case "DEPART" -> "Head onto " + road;
                case "TURN_LEFT" -> "Turn left onto " + road;
                case "TURN_RIGHT" -> "Turn right onto " + road;
                case "U_TURN" -> "Make a U-turn onto " + road;
                default -> "Continue on " + road;
            };
            double duration = profile.seconds().get(edge);
            long roundedDuration = Math.round(seconds + duration) - Math.round(seconds);
            if (maneuver.equals("CONTINUE") && !directions.isEmpty() && !edge.getRoadName().isBlank()
                    && directions.getLast().roadName().equals(edge.getRoadName())) {
                Direction previous = directions.removeLast();
                directions.add(new Direction(previous.maneuver(), previous.instruction(), previous.roadName(), previous.from(),
                        edge.getDestination().getId(), previous.distanceMeters() + edge.getWeight() * 1000,
                        previous.durationSeconds() + roundedDuration));
            } else directions.add(new Direction(maneuver, text, edge.getRoadName(), edge.getSource().getId(),
                    edge.getDestination().getId(), edge.getWeight() * 1000, roundedDuration));
            if (Double.isFinite(bearings.end())) lastBearing = bearings.end();
            distance += edge.getWeight(); seconds += duration;
            if (primaryEdges.contains(edge)) shared += edge.getWeight();
        }
        Node last = result.getPath().getLast();
        if (coordinates.isEmpty()) coordinates.add(List.of(last.getLng(), last.getLat()));
        if (coordinates.size() == 1) coordinates.add(coordinates.getFirst());
        directions.add(new Direction("ARRIVE", "Arrive at " + last.getName(), "", last.getId(), last.getId(), 0, 0));
        return new Route(distance * 1000, Math.round(seconds), result.getPath().stream().map(n -> placesById.get(n.getId())).toList(),
                new Geometry("LineString", List.copyOf(coordinates)), List.copyOf(directions), distance == 0 ? 1 : shared / distance,
                result.getEdges().stream().map(e -> new Segment(segmentIds.get(e), e.getSource().getId(),
                        e.getDestination().getId(), e.getRoadName(), e.getSourceWayId(), e.getWeight() * 1000)).toList(),
                result.getPath().equals(primary.getPath()));
    }

    private void requirePlace(String id) {
        if (id == null || id.isBlank()) throw invalid("origin, destination, and waypoint IDs cannot be blank");
        if (!network.hasNode(id)) throw new RoutingException("PLACE_NOT_FOUND", "Unknown place ID: " + id);
    }
    private static Engines engines(Graph graph, java.util.function.ToDoubleFunction<Edge> cost) {
        return new Engines(new PreparedShortestPath(graph, true, cost), new PreparedShortestPath(graph, false, cost));
    }
    private static Edge.Mode edgeMode(TravelMode mode) { return Edge.Mode.valueOf(mode.name()); }
    private static RoutingException invalid(String message) { return new RoutingException("INVALID_REQUEST", message); }
    private static void validateCoordinates(double lat, double lng) {
        try { Node.validateCoordinates(lat, lng); } catch (IllegalArgumentException error) { throw invalid(error.getMessage()); }
    }
    private record Bearings(double start, double end) { }

    private static Bearings appendGeometry(List<Edge.Point> geometry, List<List<Double>> coordinates, SearchBudget budget) {
        Edge.Point first = geometry.getFirst();
        Edge.Point previous = first, firstDistinct = null, beforeEnd = null;
        for (int index = coordinates.isEmpty() ? 0 : 1; index < geometry.size(); index++) {
            budget.work();
            Edge.Point point = geometry.get(index);
            coordinates.add(List.of(point.longitude(), point.latitude()));
            if (point.latitude() != previous.latitude() || point.longitude() != previous.longitude()) {
                if (firstDistinct == null) firstDistinct = point;
                beforeEnd = previous;
            }
            previous = point;
        }
        if (firstDistinct == null) return new Bearings(Double.NaN, Double.NaN);
        return new Bearings(bearing(first, firstDistinct), bearing(beforeEnd, previous));
    }
    private static double bearing(Edge.Point from, Edge.Point to) {
        double lat1 = Math.toRadians(from.latitude()), lat2 = Math.toRadians(to.latitude());
        double delta = Math.toRadians(to.longitude() - from.longitude());
        return (Math.toDegrees(Math.atan2(Math.sin(delta) * Math.cos(lat2),
                Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(delta))) + 360) % 360;
    }
    private static String maneuver(double before, double after) {
        if (Double.isNaN(before) || Double.isNaN(after)) return "CONTINUE";
        double angle = (after - before + 540) % 360 - 180;
        if (Math.abs(angle) > 150) return "U_TURN";
        if (angle > 30) return "TURN_RIGHT";
        if (angle < -30) return "TURN_LEFT";
        return "CONTINUE";
    }
}
