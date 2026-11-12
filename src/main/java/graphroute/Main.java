package graphroute;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphroute.api.RoutingServer;
import graphroute.io.GraphFileManager;
import graphroute.model.Graph;
import graphroute.routing.RouteRequest;
import graphroute.routing.RoutingService;
import graphroute.routing.TrafficSnapshot;
import graphroute.analysis.AlgorithmRequest;
import graphroute.analysis.AlgorithmService;
import graphroute.analysis.AnalysisWorkspace;
import graphroute.util.OSMDataUpdater;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Headless command-line entry point for the JSON backend consumed by the React frontend. */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        try {
            run(decodeArguments(args));
        } catch (Exception exception) {
            System.err.println("Error: " + exception.getMessage());
            System.exit(1);
        }
    }

    // Windows Java launchers can decode native arguments using a legacy code page.
    // The PowerShell launcher transports UTF-8 JSON as ASCII Base64 to preserve accents and file paths.
    static String[] decodeArguments(String[] args) throws Exception {
        if (args.length == 0 || !args[0].equals("--utf8-arguments")) return args;
        if (args.length != 2) throw new IllegalArgumentException("Expected one encoded argument list");
        String[] decoded = new ObjectMapper().readValue(Base64.getDecoder().decode(args[1]), String[].class);
        if (decoded == null || Arrays.stream(decoded).anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Encoded arguments must be an array of strings");
        }
        return decoded;
    }

    public static void run(String[] args) throws Exception {
        List<String> arguments = new ArrayList<>(Arrays.asList(args));
        if (arguments.isEmpty() || arguments.equals(List.of("--help")) || arguments.equals(List.of("help"))) {
            System.out.println("""
                    Graph Routing System — offline routing backend
                    Usage: java -jar target/graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar COMMAND
                      serve [--port 8080] [--cors-origin http://localhost:5173]...
                      search QUERY [--mode driving|motorcycling|cycling|walking|all] [--limit 20] [--offset 0]
                      nearest LAT LNG [--mode driving|motorcycling|cycling|walking|all]
                      route ORIGIN DESTINATION [--mode driving|motorcycling|cycling|walking]
                            [--algorithm astar|dijkstra] [--alternatives 1..3]
                            [--preference shortest|fastest] [--via NODE_ID]... [--avoid ROAD_NAME]...
                            [--authorize-way OSM_WAY_ID]... (restricted way containing a stop)
                      algorithms               List all eight analysis algorithms
                      analyze ALGORITHM [--start NODE_ID] [--goal NODE_ID] [--trace-limit 1000]
                            [--graph loaded|sample|custom] [--node NODE_ID]... [--undirected]
                      analysis-graphs          List analysis datasets and edit capabilities
                      analysis-map [--graph sample]   Export an analysis graph and revision
                      update-osm --bbox S,W,N,E --output PATH [--endpoint URL] [--force]
                      demo                     Calculate a sample route
                      map                      Export the loaded network as JSON
                    Optional: --osm PATH or --nodes PATH --edges PATH; --traffic PATH loads current observations.
                    Default coverage and limitations are reported by map.provenance.
                    No automatic live traffic provider is connected.
                    """);
            return;
        }
        if (arguments.getFirst().equals("update-osm")) {
            OSMDataUpdater.main(arguments.subList(1, arguments.size()).toArray(String[]::new));
            return;
        }
        if (arguments.equals(List.of("algorithms"))) {
            System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(AlgorithmService.algorithms()));
            return;
        }
        String nodeFile = option(arguments, "--nodes", null);
        String edgeFile = option(arguments, "--edges", null);
        String osmFile = option(arguments, "--osm", null);
        String trafficFile = option(arguments, "--traffic", null);
        if ((nodeFile == null) != (edgeFile == null)) throw new IllegalArgumentException("Supply both --nodes and --edges");
        if (osmFile != null && nodeFile != null) throw new IllegalArgumentException("Choose --osm or --nodes/--edges, not both");
        if (arguments.isEmpty()) throw new IllegalArgumentException("A command is required; use --help");
        GraphFileManager files = new GraphFileManager();
        Graph graph = osmFile != null ? files.loadOsm(Path.of(osmFile))
                : nodeFile == null ? files.loadDefaultGraph() : files.loadGraph(Path.of(nodeFile), Path.of(edgeFile));
        ObjectMapper json = new ObjectMapper();
        String command = arguments.removeFirst();
        if (java.util.Set.of("analyze", "analysis-graphs", "analysis-map").contains(command)) {
            if (trafficFile != null) throw new IllegalArgumentException("Analysis uses graph weights, not traffic observations");
            AnalysisWorkspace workspace = new AnalysisWorkspace(graph);
            Object analysisResult;
            if (command.equals("analysis-graphs")) {
                requireCount(arguments, 0, "analysis-graphs");
                analysisResult = workspace.graphs();
            } else if (command.equals("analysis-map")) {
                String selected = option(arguments, "--graph", "sample");
                requireCount(arguments, 0, "analysis-map [--graph loaded|sample|custom]");
                analysisResult = workspace.view(selected);
            } else {
                String start = option(arguments, "--start", null);
                String goal = option(arguments, "--goal", null);
                String selected = option(arguments, "--graph", "loaded");
                int traceLimit = Integer.parseInt(option(arguments, "--trace-limit", "1000"));
                List<String> nodes = repeatedOption(arguments, "--node");
                boolean undirected = arguments.remove("--undirected");
                requireCount(arguments, 1, "analyze ALGORITHM [--graph sample] [--start ID] [--goal ID] [--trace-limit NUMBER]");
                analysisResult = workspace.execute(new AlgorithmRequest(arguments.getFirst(), start, goal, traceLimit,
                        selected, nodes.isEmpty() ? null : nodes, undirected));
            }
            System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(analysisResult));
            return;
        }
        RoutingService routing = new RoutingService(graph, graph.getProvenance().getOrDefault("name", "Loaded road network"));
        if (trafficFile != null) routing.updateTraffic(json.readValue(Path.of(trafficFile).toFile(), TrafficSnapshot.class));
        Object result;
        switch (command) {
            case "serve" -> {
                int port = Integer.parseInt(option(arguments, "--port", "8080"));
                var origins = java.util.Set.copyOf(repeatedOption(arguments, "--cors-origin"));
                requireCount(arguments, 0, "serve [--port NUMBER] [--cors-origin ORIGIN]...");
                RoutingServer server = new RoutingServer(routing, port, origins);
                Runtime.getRuntime().addShutdownHook(new Thread(server::close));
                server.start();
                System.out.println("Routing UI and API listening at http://127.0.0.1:" + server.port());
                System.out.println("Press Ctrl+C to stop. GET /api lists JSON endpoints; / serves the built UI to browsers.");
                new CountDownLatch(1).await();
                return;
            }
            case "search" -> {
                String mode = option(arguments, "--mode", "driving");
                int limit = Integer.parseInt(option(arguments, "--limit", "20"));
                int offset = Integer.parseInt(option(arguments, "--offset", "0"));
                if (arguments.isEmpty() || arguments.stream().anyMatch(a -> a.startsWith("--"))) throw new IllegalArgumentException("Usage: search QUERY");
                result = routing.search(String.join(" ", arguments), limit, offset, mode).places();
            }
            case "nearest" -> {
                String mode = option(arguments, "--mode", null);
                requireCount(arguments, 2, "nearest LAT LNG");
                result = routing.nearest(Double.parseDouble(arguments.get(0)), Double.parseDouble(arguments.get(1)), 2000, mode);
            }
            case "map" -> { requireCount(arguments, 0, "map"); result = routing.map(); }
            case "demo" -> {
                requireCount(arguments, 0, "demo");
                // Choose a reachable pair from the loaded network without assuming synthetic IDs.
                var demoEdge = graph.getAllEdges().stream().filter(e -> e.allows(graphroute.model.Edge.Mode.DRIVING))
                        .max(java.util.Comparator.comparingDouble(graphroute.model.Edge::getWeight))
                        .orElseThrow(() -> new IllegalArgumentException("The loaded network has no driving road"));
                result = routing.route(new RouteRequest(demoEdge.getSource().getId(), demoEdge.getDestination().getId(),
                        List.of(), "driving", "astar", 3, List.of()));
            }
            case "route" -> {
                String mode = option(arguments, "--mode", "driving");
                String algorithm = option(arguments, "--algorithm", "astar");
                String preference = option(arguments, "--preference", "shortest");
                int alternatives = Integer.parseInt(option(arguments, "--alternatives", "1"));
                List<String> via = repeatedOption(arguments, "--via");
                List<String> avoid = repeatedOption(arguments, "--avoid");
                List<String> authorized = repeatedOption(arguments, "--authorize-way");
                requireCount(arguments, 2, "route ORIGIN_ID DESTINATION_ID [options]");
                result = routing.route(new RouteRequest(arguments.get(0), arguments.get(1), via, mode, algorithm, alternatives, avoid, preference, authorized));
            }
            default -> throw new IllegalArgumentException("Unknown command: " + command + ". Use --help.");
        }
        System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private static String option(List<String> arguments, String key, String fallback) {
        int index = arguments.indexOf(key);
        if (index < 0) return fallback;
        if (index == arguments.size() - 1 || arguments.get(index + 1).startsWith("--")) {
            throw new IllegalArgumentException("Missing value for " + key);
        }
        arguments.remove(index);
        String value = arguments.remove(index);
        if (arguments.contains(key)) throw new IllegalArgumentException("Repeated option: " + key);
        return value;
    }
    private static List<String> repeatedOption(List<String> arguments, String key) {
        List<String> values = new ArrayList<>();
        while (arguments.contains(key)) {
            int index = arguments.indexOf(key);
            if (index == arguments.size() - 1 || arguments.get(index + 1).startsWith("--")) throw new IllegalArgumentException("Missing value for " + key);
            arguments.remove(index);
            values.add(arguments.remove(index));
        }
        return values;
    }
    private static void requireCount(List<String> arguments, int count, String usage) {
        if (arguments.size() != count || arguments.stream().anyMatch(a -> a.startsWith("--"))) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
    }
}
