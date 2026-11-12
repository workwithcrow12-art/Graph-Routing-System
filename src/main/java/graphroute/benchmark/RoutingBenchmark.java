package graphroute.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphroute.algorithm.shortestpath.PreparedShortestPath;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;

/** Reproducible synthetic-grid benchmark; timings are observations, never pass/fail thresholds. */
public final class RoutingBenchmark {
    private RoutingBenchmark() { }

    /** Run: java -cp target/*-jar-with-dependencies.jar graphroute.benchmark.RoutingBenchmark [side] [queries]. */
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        if (args.length > 2) throw new IllegalArgumentException("Usage: RoutingBenchmark [side=100] [queries=20]");
        int side = args.length > 0 ? Integer.parseInt(args[0]) : 100;
        int queries = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(run(side, queries)));
    }

    public record Metrics(long queryNanos, long expandedNodes, long examinedEdges) { }
    public record Report(int gridSide, int nodes, int directedArcs, int queries, long buildNanos,
                         long astarPreparationNanos, long dijkstraPreparationNanos,
                         Metrics astar, Metrics dijkstra, double maximumCostDifference,
                         String note) { }

    public static Report run(int side, int queries) {
        if (queries < 1 || queries > 10_000) throw new IllegalArgumentException("queries must be between 1 and 10000");
        long started = System.nanoTime();
        Graph graph = grid(side);
        long built = System.nanoTime();
        PreparedShortestPath astar = new PreparedShortestPath(graph, true);
        long astarReady = System.nanoTime();
        PreparedShortestPath dijkstra = new PreparedShortestPath(graph, false);
        long dijkstraReady = System.nanoTime();
        // Warm both code paths before measurement; all queries use the already prepared indexes.
        for (int warm = 0; warm < 3; warm++) {
            astar.search(id(side / 2, 0), id(side / 2, side - 1), 0);
            dijkstra.search(id(side / 2, 0), id(side / 2, side - 1), 0);
        }
        long astarNanos = 0, astarExpanded = 0, astarExamined = 0;
        long dijkstraNanos = 0, dijkstraExpanded = 0, dijkstraExamined = 0;
        double maximumDifference = 0;
        for (int index = 0; index < queries; index++) {
            int row = (int) (((long) index * 31 + side / 2) % side);
            String start = id(row, index % 2 == 0 ? 0 : side - 1);
            String goal = id(row, index % 2 == 0 ? side - 1 : 0);
            long before = System.nanoTime();
            var astarResult = astar.search(start, goal, 0);
            long middle = System.nanoTime();
            var dijkstraResult = dijkstra.search(start, goal, 0);
            long after = System.nanoTime();
            if (!astarResult.result().isSuccess() || !dijkstraResult.result().isSuccess()) {
                throw new IllegalStateException("The generated connected grid must have a route");
            }
            double difference = Math.abs(astarResult.result().getTotalCost() - dijkstraResult.result().getTotalCost());
            maximumDifference = Math.max(maximumDifference, difference);
            if (difference > 1e-9) throw new IllegalStateException("A* disagrees with Dijkstra: " + difference);
            astarNanos += middle - before;
            astarExpanded += astarResult.expandedNodes();
            astarExamined += astarResult.examinedEdges();
            dijkstraNanos += after - middle;
            dijkstraExpanded += dijkstraResult.expandedNodes();
            dijkstraExamined += dijkstraResult.examinedEdges();
        }
        return new Report(side, graph.nodeCount(), graph.getAllEdges().size(), queries, built - started,
                astarReady - built, dijkstraReady - astarReady,
                new Metrics(astarNanos, astarExpanded, astarExamined),
                new Metrics(dijkstraNanos, dijkstraExpanded, dijkstraExamined), maximumDifference,
                "Synthetic geographic grid; horizontal queries; trace disabled; preparation excluded from query timings. "
                        + "Results are not a claim about all road networks or traffic. Larger runs may need -Xmx2g.");
    }

    /** Generates a frozen, geographically plausible network, reusable by correctness/performance checks. */
    public static Graph grid(int side) {
        if (side < 2 || side > 500) throw new IllegalArgumentException("side must be between 2 and 500");
        Graph graph = new Graph(GraphType.UNDIRECTED);
        for (int row = 0; row < side; row++) {
            for (int column = 0; column < side; column++) {
                String id = id(row, column);
                graph.addNode(new Node(id, id, 21 + row * 0.0001, 105.8 + column * 0.0001));
            }
        }
        for (int row = 0; row < side; row++) {
            for (int column = 0; column < side; column++) {
                Node current = graph.getNode(id(row, column));
                if (row + 1 < side) connect(graph, current, graph.getNode(id(row + 1, column)), "column-" + column);
                if (column + 1 < side) connect(graph, current, graph.getNode(id(row, column + 1)), "row-" + row);
            }
        }
        return graph.freeze();
    }

    private static void connect(Graph graph, Node first, Node second, String road) {
        double latitudeSine = Math.sin(Math.toRadians(second.getLat() - first.getLat()) / 2);
        double longitudeSine = Math.sin(Math.toRadians(second.getLng() - first.getLng()) / 2);
        double haversine = latitudeSine * latitudeSine + Math.cos(Math.toRadians(first.getLat()))
                * Math.cos(Math.toRadians(second.getLat())) * longitudeSine * longitudeSine;
        double kilometres = 6371 * 2 * Math.asin(Math.min(1, Math.sqrt(haversine)));
        graph.addEdge(new Edge(first, second, kilometres, road));
    }

    private static String id(int row, int column) { return row + ":" + column; }
}
