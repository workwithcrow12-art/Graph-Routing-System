package graphroute;

import graphroute.algorithm.AlgorithmResult;
import graphroute.algorithm.shortestpath.Dijkstra;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DijkstraTest {
    @Test
    void requeuesImprovedDistancesAndIgnoresObsoleteQueueEntries() {
        Graph graph = new Graph(GraphType.DIRECTED);
        for (String id : List.of("start", "expensive", "shortcut", "goal")) {
            graph.addNode(new Node(id, id, 0, 0));
        }
        graph.addEdge(new Edge(graph.getNode("start"), graph.getNode("expensive"), 9));
        graph.addEdge(new Edge(graph.getNode("start"), graph.getNode("shortcut"), 1));
        graph.addEdge(new Edge(graph.getNode("shortcut"), graph.getNode("expensive"), 1));
        graph.addEdge(new Edge(graph.getNode("expensive"), graph.getNode("goal"), 10));

        AlgorithmResult result = new Dijkstra().execute(graph, "start", "goal");

        assertEquals(12, result.getTotalCost());
        assertEquals(List.of("start", "shortcut", "expensive", "goal"),
                result.getPath().stream().map(Node::getId).toList());
        assertEquals(4, result.getSteps().size(), "The stale expensive queue entry must not be expanded");
    }
}
