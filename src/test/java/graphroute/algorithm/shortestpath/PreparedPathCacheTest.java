package graphroute.algorithm.shortestpath;

import graphroute.model.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparedPathCacheTest {
    @Test void keysOnBothGraphRevisionAndHeuristic() {
        Graph graph = new Graph();
        graph.addNode(new Node("a", "a", 0, 0));
        var cache = new PreparedPathCache();
        var dijkstra = cache.get(graph, false);
        var astar = cache.get(graph, true);
        assertNotSame(dijkstra, astar);
        assertSame(dijkstra, cache.get(graph, false));
        assertSame(astar, cache.get(graph, true));
        graph.addNode(new Node("b", "b", 0, 0));
        assertNotSame(dijkstra, cache.get(graph, false));
    }
}
