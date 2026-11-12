package graphroute.routing;

import graphroute.algorithm.shortestpath.SearchBudget;
import graphroute.model.Edge;
import graphroute.model.Graph;
import graphroute.model.GraphType;
import graphroute.model.Node;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoutingBudgetTest {
    @Test void waypointLegsShareOneBudgetAndFailureDoesNotAffectLaterRequests() {
        var graph = new Graph(GraphType.DIRECTED);
        for (String id : List.of("a", "b", "c")) graph.addNode(new Node(id, id, 0, 0));
        graph.addEdge(new Edge(graph.getNode("a"), graph.getNode("b"), 1));
        graph.addEdge(new Edge(graph.getNode("b"), graph.getNode("c"), 1));
        var service = new RoutingService(graph);
        var request = new RouteRequest("a", "c", List.of("b"), "driving", "dijkstra", 1, List.of());
        // Each one-edge leg needs four work units. Seven cannot pay for both legs.
        assertEquals(1, service.route(new RouteRequest("a", "b"), budget()).routes().size());
        var failure = assertThrows(RoutingException.class, () -> service.route(request, budget()));
        assertEquals("ROUTE_LIMIT_EXCEEDED", failure.getCode());
        assertEquals(1, service.route(request).routes().size());
    }

    private static SearchBudget budget() {
        return new SearchBudget(7, 100, 100_000, Duration.ofSeconds(10));
    }
}
