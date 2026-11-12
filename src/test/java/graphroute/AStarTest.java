package graphroute;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.shortestpath.AStar;
import graphroute.algorithm.shortestpath.Dijkstra;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AStarTest {
    @Test
    void geographicHeuristicCannotOverestimateSmallEdgeWeights() {
        Graph graph = new Graph(GraphType.DIRECTED);
        Node start = new Node("start", "Start", 0, 1);
        Node detour = new Node("detour", "Detour", 0, 3);
        Node goal = new Node("goal", "Goal", 0, 2);
        graph.addNode(start);
        graph.addNode(detour);
        graph.addNode(goal);
        graph.addEdge(new Edge(start, goal, 3));
        graph.addEdge(new Edge(start, detour, 1));
        graph.addEdge(new Edge(detour, goal, 1));

        AlgorithmResult result = new AStar().execute(graph, "start", "goal");

        assertEquals(2, result.getTotalCost());
        assertEquals(List.of(start, detour, goal), result.getPath());
    }

    @Test
    void coincidentCoordinatesFallBackToDijkstraWithoutChangingRouteCosts() {
        Graph graph = new Graph();
        Node start = new Node("start", "Start", 20, 100);
        Node middle = new Node("middle", "Middle", 20, 100);
        Node goal = new Node("goal", "Goal", 20, 100);
        graph.addNode(start);
        graph.addNode(middle);
        graph.addNode(goal);
        graph.addEdge(new Edge(start, middle, 2));
        graph.addEdge(new Edge(middle, goal, 2));
        graph.addEdge(new Edge(start, goal, 8));

        AlgorithmResult result = new AStar().execute(graph, "start", "goal");

        assertEquals(new Dijkstra().execute(graph, "start", "goal").getTotalCost(), result.getTotalCost());
        assertEquals(4, result.getTotalCost());
    }

    @Test
    void geographicOriginAndAntipodalCoordinatesRemainUsable() {
        Graph graph = new Graph();
        Node origin = new Node("origin", "Origin", 0, 0);
        Node opposite = new Node("opposite", "Opposite", 0, 180);
        graph.addNode(origin);
        graph.addNode(opposite);
        graph.addEdge(new Edge(origin, opposite, 20_016));

        AlgorithmResult result = new AStar().execute(graph, "origin", "opposite");

        assertEquals(AlgorithmResult.Status.SUCCESS, result.getStatus());
        assertEquals(20_016, result.getTotalCost());
    }
}
