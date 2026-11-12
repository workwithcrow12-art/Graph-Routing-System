package graphroute.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.shortestpath.PreparedShortestPath;
import graphroute.io.GraphFileManager;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import graphroute.util.DistanceUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Measures the bundled real network separately from synthetic grid benchmarks. */
public final class DatasetBenchmark {
    private DatasetBenchmark() { }

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        if (args.length > 1) throw new IllegalArgumentException("Usage: DatasetBenchmark [queries=100]");
        int queries = args.length == 0 ? 100 : Integer.parseInt(args[0]);
        if (queries < 1 || queries > 10000) throw new IllegalArgumentException("queries must be 1..10000");
        long start = System.nanoTime();
        Graph network = new GraphFileManager().loadDefaultGraph();
        Graph driving = new Graph(GraphType.DIRECTED);
        network.getAllNodes().forEach(driving::addNode);
        network.getAllEdges().stream().filter(e -> e.allows(Edge.Mode.DRIVING)).forEach(driving::addEdge);
        driving.freeze();
        long loaded = System.nanoTime();
        var astar = new PreparedShortestPath(driving, true);
        long astarReady = System.nanoTime();
        var dijkstra = new PreparedShortestPath(driving, false);
        long dijkstraReady = System.nanoTime();
        List<String> ids = driving.getAllNodes().stream().map(Node::getId).sorted().toList();
        Random random = new Random(20261019);
        long[] nanos = new long[2], expanded = new long[2], examined = new long[2];
        double maximumDifference = 0;
        int reachable = 0;
        for (int i = -10; i < queries; i++) {
            String from = ids.get(random.nextInt(ids.size())), to = ids.get(random.nextInt(ids.size()));
            // Alternate execution order to reduce consistent first-run bias.
            PreparedShortestPath.SearchReport[] results = new PreparedShortestPath.SearchReport[2];
            for (int position = 0; position < 2; position++) {
                int engine = Math.floorMod(i + position, 2);
                long before = System.nanoTime();
                results[engine] = (engine == 0 ? astar : dijkstra).search(from, to, 0);
                if (i >= 0) {
                    nanos[engine] += System.nanoTime() - before;
                    expanded[engine] += results[engine].expandedNodes();
                    examined[engine] += results[engine].examinedEdges();
                }
            }
            AlgorithmResult a = results[0].result(), d = results[1].result();
            if (a.getStatus() != d.getStatus()) throw new IllegalStateException("Reachability disagrees");
            if (i >= 0 && a.isSuccess()) {
                reachable++;
                maximumDifference = Math.max(maximumDifference, Math.abs(a.getTotalCost() - d.getTotalCost()));
            }
        }
        if (maximumDifference > 1e-9) throw new IllegalStateException("Route costs disagree");
        double minimumRatio = Double.POSITIVE_INFINITY;
        for (Edge edge : driving.getAllEdges()) {
            Node a = edge.getSource(), b = edge.getDestination();
            double straight = DistanceUtil.haversine(a.getLat(), a.getLng(), b.getLat(), b.getLng());
            if (straight > 0) minimumRatio = Math.min(minimumRatio, edge.getWeight() / straight);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("dataset", network.getProvenance());
        report.put("nodes", network.nodeCount());
        report.put("directedArcs", network.getAllEdges().size());
        report.put("drivingArcs", driving.getAllEdges().size());
        report.put("curvedArcs", network.getAllEdges().stream().filter(e -> e.getGeometry().size() > 2).count());
        report.put("minimumDrivingDistanceToGeodesicRatio", Double.isFinite(minimumRatio) ? minimumRatio : null);
        report.put("queries", queries);
        report.put("reachableQueries", reachable);
        report.put("loadNanos", loaded - start);
        report.put("astarPreparationNanos", astarReady - loaded);
        report.put("dijkstraPreparationNanos", dijkstraReady - astarReady);
        report.put("astar", new RoutingBenchmark.Metrics(nanos[0], expanded[0], examined[0]));
        report.put("dijkstra", new RoutingBenchmark.Metrics(nanos[1], expanded[1], examined[1]));
        report.put("maximumCostDifference", maximumDifference);
        report.put("note", "Bundled extract; driving distance; seed 20261019; 10 warmups; no traces; "
                + "random junction pairs include disconnected components. Preparation excluded from query timings. "
                + "Timings depend on hardware/JIT and are not test thresholds.");
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
