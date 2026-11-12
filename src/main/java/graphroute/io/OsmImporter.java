package graphroute.io;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import graphroute.util.DistanceUtil;
import graphroute.routing.RoutingService;
import graphroute.routing.SpatialIndex;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Imports bounded OSM XML extracts and Overpass JSON (nodes or {@code out geom}).
 * Shape-only points are compressed into geometry; shared OSM node IDs remain junctions.
 * Country-specific defaults, turn restrictions and conditional restrictions are not evaluated.
 */
public final class OsmImporter {
    public static final int MAX_INPUT_BYTES = 64 * 1024 * 1024;
    public static final int MAX_NAME_CHARS = 512;
    public static final int MAX_JUNCTION_NAME_CHARS = 2_048;
    private static final long MAX_EXPANDED_NAME_CHARS = 8L * 1024 * 1024;
    private static final int MAX_WAY_REFS = 1_000_000;
    private static final Set<String> ROUTABLE = Set.of("motorway", "motorway_link", "trunk", "trunk_link",
            "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link",
            "unclassified", "residential", "living_street", "service", "track", "road",
            "footway", "pedestrian", "path", "steps", "cycleway", "bridleway", "corridor");
    private static final Set<String> DENIED = Set.of("no", "private", "agricultural", "forestry", "permit");
    private static final Set<String> RESTRICTED = Set.of("destination", "delivery", "customers");
    private static final Set<String> ALLOWED = Set.of("yes", "permissive", "designated", "official");
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Does not close the caller's stream. Rejects incomplete ways instead of inventing connecting segments. */
    public Graph load(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("OSM input is required");
        byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
        if (bytes.length > MAX_INPUT_BYTES) throw problem("OSM extract exceeds 64 MiB; use a smaller bounding box");
        Data data = startsWithXml(bytes) ? xml(bytes) : json(bytes);
        return build(data);
    }

    private static boolean startsWithXml(byte[] bytes) {
        boolean utf8Bom = bytes.length >= 3 && bytes[0] == (byte) 0xef
                && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf;
        for (int index = utf8Bom ? 3 : 0; index < bytes.length; index++) {
            byte value = bytes[index];
            if (value != ' ' && value != '\t' && value != '\r' && value != '\n') return value == '<';
        }
        return false;
    }

    private Data json(byte[] bytes) throws IOException {
        JsonNode root = JSON.readTree(bytes);
        if (root == null || !root.isObject() || !root.path("elements").isArray()) throw problem("OSM JSON needs an elements array");
        if (root.hasNonNull("remark")) throw problem("Overpass returned a partial/error response: " + root.get("remark").asText());
        Data data = new Data();
        data.metadata.put("format", "Overpass JSON");
        data.metadata.put("generator", root.path("generator").asText("unknown"));
        data.metadata.put("timestamp", root.path("osm3s").path("timestamp_osm_base").asText("unknown"));
        for (JsonNode element : root.get("elements")) {
            String type = element.path("type").asText();
            if (type.equals("node")) {
                String id = jsonId(element.get("id"));
                data.putNode(id, new PointData(jsonCoordinate(element, "lat"), jsonCoordinate(element, "lon"), tags(element.get("tags"))));
            }
        }
        Set<String> wayIds = new HashSet<>();
        for (JsonNode element : root.get("elements")) {
            if (!element.path("type").asText().equals("way")) continue;
            Map<String, String> tags = tags(element.get("tags"));
            if (!routable(tags)) continue;
            String id = jsonId(element.get("id"));
            if (!wayIds.add(id)) throw problem("Duplicate OSM way " + id);
            JsonNode refs = element.get("nodes");
            if (refs == null || !refs.isArray() || refs.size() < 2) throw problem("OSM way " + id + " needs at least two node references");
            JsonNode geometry = element.get("geometry");
            if (geometry != null && (!geometry.isArray() || geometry.size() != refs.size())) throw problem("Geometry/reference count mismatch for way " + id);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < refs.size(); i++) {
                String nodeId = jsonId(refs.get(i));
                ids.add(nodeId);
                if (geometry != null) {
                    JsonNode point = geometry.get(i);
                    data.putNode(nodeId, new PointData(jsonCoordinate(point, "lat"), jsonCoordinate(point, "lon"), Map.of()));
                }
            }
            data.ways.add(new Way(id, List.copyOf(ids), tags));
        }
        return data;
    }

    private Data xml(byte[] bytes) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicID, systemID, baseURI, namespace) -> { throw new XMLStreamException("External entities are disabled"); });
        Data data = new Data();
        data.metadata.put("format", "OSM XML");
        Set<String> wayIds = new HashSet<>();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
            try {
                String active = null, id = null;
                double lat = 0, lon = 0;
                Map<String, String> tags = null;
                List<String> refs = null;
                boolean rootSeen = false;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw problem("DTD and XML entities are not allowed");
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String name = reader.getLocalName();
                        if (!rootSeen) {
                            if (!name.equals("osm")) throw problem("Expected an osm XML root");
                            rootSeen = true;
                            data.metadata.put("generator", attribute(reader, "generator", "unknown"));
                        } else if (name.equals("meta")) {
                            data.metadata.put("timestamp", attribute(reader, "osm_base", "unknown"));
                        } else if (name.equals("node") || name.equals("way")) {
                            active = name;
                            id = requiredAttribute(reader, "id");
                            tags = new LinkedHashMap<>();
                            refs = new ArrayList<>();
                            if (name.equals("node")) {
                                lat = Double.parseDouble(requiredAttribute(reader, "lat"));
                                lon = Double.parseDouble(requiredAttribute(reader, "lon"));
                                Node.validateCoordinates(lat, lon);
                            }
                        } else if (active != null && name.equals("tag")) {
                            String value = reader.getAttributeValue(null, "v");
                            if (value == null) throw problem("Missing OSM XML attribute v");
                            tags.put(requiredAttribute(reader, "k"), value);
                        } else if ("way".equals(active) && name.equals("nd")) {
                            refs.add(requiredAttribute(reader, "ref"));
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals(active)) {
                        if (active.equals("node")) data.putNode(id, new PointData(lat, lon, Map.copyOf(tags)));
                        else if (routable(tags)) {
                            if (refs.size() < 2) throw problem("OSM way " + id + " needs at least two node references");
                            if (!wayIds.add(id)) throw problem("Duplicate OSM way " + id);
                            data.ways.add(new Way(id, List.copyOf(refs), Map.copyOf(tags)));
                        }
                        active = null;
                    }
                }
                if (!rootSeen) throw problem("Empty OSM XML document");
            } finally { reader.close(); }
        } catch (XMLStreamException | IllegalArgumentException exception) {
            throw new GraphSerializer.GraphDataException("Invalid OSM XML: " + exception.getMessage(), exception);
        }
        return data;
    }

    private Graph build(Data data) {
        long references = 0;
        for (PointData point : data.nodes.values()) checkName(point.tags);
        for (Way way : data.ways) {
            checkName(way.tags);
            references += way.refs.size();
            if (references > MAX_WAY_REFS) throw problem("Too many OSM way references");
        }
        Map<String, Integer> occurrences = new HashMap<>();
        Map<String, JunctionNames> names = new HashMap<>();
        long expandedNameChars = 0;
        Set<String> junctions = new HashSet<>();
        for (Way way : data.ways) {
            junctions.add(way.refs.getFirst());
            junctions.add(way.refs.getLast());
            if (way.refs.getFirst().equals(way.refs.getLast()) && way.refs.size() > 2) junctions.add(way.refs.get(way.refs.size() / 2));
            for (String id : way.refs) {
                if (!data.nodes.containsKey(id)) throw problem("OSM way " + way.id + " references missing node " + id);
                occurrences.merge(id, 1, Integer::sum);
                String name = roadName(way.tags);
                if (!name.isBlank()) {
                    JunctionNames labels = names.computeIfAbsent(id, ignored -> new JunctionNames());
                    int added = labels.additionalLength(name);
                    if (expandedNameChars + added > MAX_EXPANDED_NAME_CHARS) throw problem("Expanded OSM names exceed limit");
                    labels.add(name, added);
                    expandedNameChars += added;
                }
                if (!pointName(data.nodes.get(id).tags).isBlank()) junctions.add(id);
            }
        }
        occurrences.forEach((id, count) -> { if (count > 1) junctions.add(id); });
        // Index source labels once; derived "Near" labels never seed other labels.
        List<RoutingService.Place> landmarks = new ArrayList<>();
        for (String id : occurrences.keySet()) {
            PointData point = data.nodes.get(id);
            String label = pointName(point.tags);
            if (label.isBlank() && names.containsKey(id)) label = names.get(id).values.iterator().next();
            if (!label.isBlank()) landmarks.add(new RoutingService.Place(id, label, point.lat, point.lon));
        }
        SpatialIndex nearbyNames = landmarks.isEmpty() ? null : new SpatialIndex(landmarks);
        Graph graph = new Graph(GraphType.DIRECTED);
        long outputNameChars = 0;
        for (String id : occurrences.keySet().stream().sorted().toList()) {
            if (!junctions.contains(id)) continue;
            PointData point = data.nodes.get(id);
            // Avoid eagerly constructing a joined fallback when the node already has a name.
            String name = pointName(point.tags);
            if (name.isBlank()) name = names.containsKey(id) ? names.get(id).joined() : "";
            if (name.isBlank() && nearbyNames != null) {
                SpatialIndex.Match nearby = nearbyNames.nearest(point.lat, point.lon);
                if (nearby.distanceMeters() <= 200)
                    name = "Near " + nearby.place().name() + " (OSM junction " + id + ")";
            }
            outputNameChars += name.length();
            if (outputNameChars > MAX_EXPANDED_NAME_CHARS) throw problem("Output OSM names exceed limit");
            graph.addNode(new Node(nodeId(id), name.isBlank() ? "OSM junction " + id : name, point.lat, point.lon));
        }
        for (Way way : data.ways) {
            int start = 0;
            for (int end = 1; end < way.refs.size(); end++) {
                if (!junctions.contains(way.refs.get(end))) continue;
                List<Edge.Point> geometry = new ArrayList<>();
                double distance = 0;
                for (int i = start; i <= end; i++) {
                    PointData point = data.nodes.get(way.refs.get(i));
                    if (!geometry.isEmpty()) {
                        Edge.Point previous = geometry.getLast();
                        distance += DistanceUtil.haversine(previous.latitude(), previous.longitude(), point.lat, point.lon);
                    }
                    geometry.add(new Edge.Point(point.lat, point.lon));
                }
                Node source = graph.getNode(nodeId(way.refs.get(start)));
                Node target = graph.getNode(nodeId(way.refs.get(end)));
                addArc(graph, source, target, geometry, distance, way, true);
                addArc(graph, target, source, geometry.reversed(), distance, way, false);
                start = end;
            }
        }
        // Drop isolated junctions belonging only to ways whose access excludes all supported modes.
        Set<String> used = new HashSet<>();
        for (Edge edge : graph.getAllEdges()) { used.add(edge.getSource().getId()); used.add(edge.getDestination().getId()); }
        for (Node node : List.copyOf(graph.getAllNodes())) if (!used.contains(node.getId())) graph.removeNode(node.getId());
        Map<String, String> metadata = new LinkedHashMap<>(data.metadata);
        metadata.put("source", "OpenStreetMap");
        metadata.put("attribution", "© OpenStreetMap contributors");
        metadata.put("license", "ODbL 1.0 https://opendatacommons.org/licenses/odbl/1-0/");
        metadata.put("distance", "Sum of geodesic distances along OSM way geometry (km)");
        metadata.put("routingLimitations", "No turn-restriction relations, barriers or time-dependent access; generic speed estimates, not live traffic; private/no excluded; destination/delivery/customers require explicit endpoint-way authorization. Motorcycle defaults exclude motorways, with explicit OSM motorcycle tags taking precedence.");
        metadata.put("sourceWays", Integer.toString(data.ways.size()));
        metadata.put("sourceShapeNodes", Integer.toString(occurrences.size()));
        java.util.DoubleSummaryStatistics latitudes = new java.util.DoubleSummaryStatistics();
        java.util.DoubleSummaryStatistics longitudes = new java.util.DoubleSummaryStatistics();
        graph.getAllEdges().forEach(edge -> edge.getGeometry().forEach(point -> {
            latitudes.accept(point.latitude()); longitudes.accept(point.longitude());
        }));
        if (latitudes.getCount() > 0) metadata.put("dataBounds", String.format(Locale.ROOT, "%.7f,%.7f,%.7f,%.7f",
                latitudes.getMin(), longitudes.getMin(), latitudes.getMax(), longitudes.getMax()));
        graph.setProvenance(metadata);
        return graph;
    }

    private static void addArc(Graph graph, Node source, Node target, List<Edge.Point> geometry, double distance, Way way, boolean forward) {
        EnumMap<Edge.Mode, Double> speeds = new EnumMap<>(Edge.Mode.class);
        EnumMap<Edge.Mode, Edge.Access> access = new EnumMap<>(Edge.Mode.class);
        for (Edge.Mode mode : Edge.Mode.values()) if (accessible(way.tags, mode, forward) && directionAllowed(way.tags, mode, forward)) {
            speeds.put(mode, speed(way.tags, mode, forward));
            access.put(mode, accessRequirement(way.tags, mode, forward));
        }
        if (!speeds.isEmpty()) graph.addEdge(new Edge(source, target, distance, roadName(way.tags), geometry, speeds, way.id, way.tags, access));
    }

    private static String[] accessHierarchy(Edge.Mode mode) {
        return switch (mode) {
            case DRIVING -> new String[]{"access", "vehicle", "motor_vehicle", "motorcar"};
            case MOTORCYCLING -> new String[]{"access", "vehicle", "motor_vehicle", "motorcycle"};
            case CYCLING -> new String[]{"access", "vehicle", "bicycle"};
            case WALKING -> new String[]{"access", "foot"};
        };
    }

    private static Edge.Access accessRequirement(Map<String, String> tags, Edge.Mode mode, boolean forward) {
        Edge.Access result = Edge.Access.PUBLIC;
        for (String key : accessHierarchy(mode)) for (String field : new String[]{key, key + (forward ? ":forward" : ":backward")}) {
            String value = tags.getOrDefault(field, "");
            if (RESTRICTED.contains(value)) result = Edge.Access.valueOf(value.toUpperCase(Locale.ROOT));
            else if (ALLOWED.contains(value) && !field.equals("access")) result = Edge.Access.PUBLIC;
        }
        return result;
    }

    private static boolean accessible(Map<String, String> tags, Edge.Mode mode, boolean forward) {
        String highway = tags.get("highway");
        boolean allowed = switch (mode) {
            case DRIVING -> !Set.of("footway", "pedestrian", "path", "steps", "cycleway", "bridleway", "corridor").contains(highway);
            case MOTORCYCLING -> !Set.of("motorway", "motorway_link", "footway", "pedestrian", "path", "steps", "cycleway", "bridleway", "corridor").contains(highway);
            case CYCLING -> !Set.of("motorway", "motorway_link", "steps", "footway", "corridor").contains(highway);
            case WALKING -> !Set.of("motorway", "motorway_link").contains(highway);
        };
        if ((mode == Edge.Mode.CYCLING || mode == Edge.Mode.WALKING) && tags.getOrDefault("motorroad", "no").equals("yes")) allowed = false;
        String[] hierarchy = accessHierarchy(mode);
        String direction = forward ? ":forward" : ":backward";
        for (String key : hierarchy) {
            String value = tags.get(key);
            if (value != null) {
                if (DENIED.contains(value) || value.equals("use_sidepath") || value.equals("dismount")) allowed = false;
                // Generic access=yes does not turn a footpath into a motor road.
                else if (ALLOWED.contains(value) && !key.equals("access")) allowed = true;
            }
            value = tags.get(key + direction);
            if (value != null) {
                if (DENIED.contains(value)) allowed = false;
                else if (ALLOWED.contains(value)) allowed = true;
            }
        }
        return allowed;
    }

    private static boolean directionAllowed(Map<String, String> tags, Edge.Mode mode, boolean forward) {
        String value = mode == Edge.Mode.WALKING ? "no" : tags.getOrDefault("oneway",
                tags.getOrDefault("junction", "").equals("roundabout") || Set.of("motorway", "motorway_link").contains(tags.get("highway")) ? "yes" : "no");
        if (mode == Edge.Mode.DRIVING) value = tags.getOrDefault("oneway:motorcar", tags.getOrDefault("oneway:motor_vehicle", tags.getOrDefault("oneway:vehicle", value)));
        if (mode == Edge.Mode.MOTORCYCLING) value = tags.getOrDefault("oneway:motorcycle", tags.getOrDefault("oneway:motor_vehicle", tags.getOrDefault("oneway:vehicle", value)));
        if (mode == Edge.Mode.CYCLING) {
            value = tags.getOrDefault("oneway:vehicle", value);
            if (tags.getOrDefault("cycleway", "").startsWith("opposite")) value = "no";
            value = tags.getOrDefault("oneway:bicycle", value);
        }
        if (mode == Edge.Mode.WALKING) value = tags.getOrDefault("oneway:foot", value);
        if (Set.of("reversible", "alternating").contains(value)) return false;
        if (Set.of("yes", "1", "true").contains(value)) return forward;
        if (value.equals("-1")) return !forward;
        return true;
    }

    private static double speed(Map<String, String> tags, Edge.Mode mode, boolean forward) {
        String highway = tags.get("highway");
        boolean rough = Set.of("unpaved", "gravel", "dirt", "sand", "ground", "cobblestone").contains(tags.getOrDefault("surface", ""));
        if (mode == Edge.Mode.WALKING) return highway.equals("steps") ? 2.5 : rough ? 4.0 : 5.0;
        if (mode == Edge.Mode.CYCLING) return rough ? 10.0 : highway.equals("cycleway") ? 20.0 : Set.of("path", "pedestrian", "living_street").contains(highway) ? 12.0 : 16.0;
        double estimate = switch (highway) {
            case "motorway" -> 90; case "motorway_link", "trunk", "trunk_link" -> 60;
            case "primary", "primary_link" -> 45; case "secondary", "secondary_link" -> 40;
            case "tertiary", "tertiary_link" -> 35; case "living_street" -> 10;
            case "service", "track" -> 15; default -> 30;
        };
        if (rough) estimate = Math.min(estimate, 20);
        if (mode == Edge.Mode.MOTORCYCLING) estimate = Math.min(estimate, 40);
        String limit = tags.getOrDefault(forward ? "maxspeed:forward" : "maxspeed:backward", tags.getOrDefault("maxspeed", ""));
        try {
            String normalized = limit.toLowerCase(Locale.ROOT).trim();
            double factor = normalized.endsWith("mph") ? 1.609344 : 1;
            double value = Double.parseDouble(normalized.replace("mph", "").replace("km/h", "").replace("kph", "").trim()) * factor;
            if (Double.isFinite(value) && value > 0) estimate = Math.min(estimate, value);
        } catch (NumberFormatException ignored) { /* Symbolic/regional speed limits retain the documented estimate. */ }
        return estimate;
    }

    private static boolean routable(Map<String, String> tags) { return ROUTABLE.contains(tags.getOrDefault("highway", "")) && !tags.getOrDefault("area", "no").equals("yes"); }
    private static String nodeId(String id) { return "osm:" + id; }
    private static String jsonId(JsonNode value) {
        if (value == null || !(value.isIntegralNumber() || value.isTextual()) || value.asText().isBlank()) throw problem("OSM IDs must be nonblank strings or integers");
        return value.asText();
    }
    private static double jsonCoordinate(JsonNode value, String field) {
        JsonNode coordinate = value == null ? null : value.get(field);
        if (coordinate == null || !coordinate.isNumber() || !Double.isFinite(coordinate.doubleValue())) throw problem("OSM geometry needs numeric " + field);
        return coordinate.doubleValue();
    }
    private static Map<String, String> tags(JsonNode node) {
        if (node == null) return Map.of();
        if (!node.isObject()) throw problem("OSM tags must be an object");
        Map<String, String> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> { if (!entry.getValue().isTextual()) throw problem("OSM tag values must be strings"); result.put(entry.getKey(), entry.getValue().asText()); });
        return Map.copyOf(result);
    }
    private static String attribute(XMLStreamReader reader, String name, String fallback) { String value = reader.getAttributeValue(null, name); return value == null ? fallback : value; }
    private static String requiredAttribute(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        if (value == null || value.isBlank()) throw problem("Missing OSM XML attribute " + name);
        return value;
    }
    private static GraphSerializer.GraphDataException problem(String message) { return new GraphSerializer.GraphDataException(message); }
    private static void checkName(Map<String, String> tags) {
        for (String key : List.of("name", "name:vi", "name:en", "ref", "addr:street", "addr:place", "addr:housenumber")) {
            String value = tags.get(key);
            if (value != null && value.length() > MAX_NAME_CHARS) throw problem("OSM " + key + " exceeds " + MAX_NAME_CHARS + " characters");
        }
    }

    private static String roadName(Map<String, String> tags) {
        return firstLabel(tags, "name", "name:vi", "name:en", "ref", "addr:street");
    }

    private static String pointName(Map<String, String> tags) {
        String explicit = firstLabel(tags, "name", "name:vi", "name:en", "ref");
        if (!explicit.isBlank()) return explicit;
        String street = firstLabel(tags, "addr:street", "addr:place");
        String number = firstLabel(tags, "addr:housenumber");
        return street.isBlank() ? "" : number.isBlank() ? street : number + " " + street;
    }

    private static String firstLabel(Map<String, String> tags, String... keys) {
        for (String key : keys) {
            String value = tags.get(key);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static final class JunctionNames {
        private final Set<String> values = new TreeSet<>();
        private int length;

        int additionalLength(String name) {
            if (values.contains(name)) return 0;
            int added = name.length() + (values.isEmpty() ? 0 : 3);
            if (length + added > MAX_JUNCTION_NAME_CHARS) throw problem("OSM junction name exceeds limit");
            return added;
        }

        void add(String name, int added) { if (added != 0) { values.add(name); length += added; } }
        String joined() { return String.join(" / ", values); }
    }
    private record PointData(double lat, double lon, Map<String, String> tags) { PointData { Node.validateCoordinates(lat, lon); } }
    private record Way(String id, List<String> refs, Map<String, String> tags) { }
    private static final class Data {
        final Map<String, PointData> nodes = new LinkedHashMap<>();
        final List<Way> ways = new ArrayList<>();
        final Map<String, String> metadata = new LinkedHashMap<>();
        void putNode(String id, PointData point) {
            PointData old = nodes.get(id);
            if (old != null && (Math.abs(old.lat - point.lat) > 1e-7 || Math.abs(old.lon - point.lon) > 1e-7)) throw problem("Conflicting coordinates for OSM node " + id);
            if (old == null || !point.tags.isEmpty()) nodes.put(id, point);
        }
    }
}
