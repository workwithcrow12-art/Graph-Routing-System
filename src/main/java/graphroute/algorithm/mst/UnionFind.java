package graphroute.algorithm.mst;

import java.util.*;

import graphroute.model.Node;

/**
 * Disjoint sets with path compression and union by rank for Kruskal's algorithm.
 */
public class UnionFind {

    /** Maps each node ID to its parent ID. */
    private final Map<String, String> parent;

    /** Stores the ranks used to choose a root when merging sets. */
    private final Map<String, Integer> rank;

    /**
     * Starts with one set per node, each rooted at that node.
     *
     * @param nodes nodes to include
     */
    public UnionFind(Collection<Node> nodes) {
        parent = new HashMap<>();
        rank   = new HashMap<>();
        for (Node node : nodes) {
            parent.put(node.getId(), node.getId());
            rank.put(node.getId(), 0);
        }
    }

    /**
     * Finds the root of a node's set and compresses the path to that root.
     *
     * @param nodeId node to look up
     * @return ID of the root node
     */
    public String find(String nodeId) {
        if (!parent.get(nodeId).equals(nodeId)) {
            parent.put(nodeId, find(parent.get(nodeId))); // path compression
        }
        return parent.get(nodeId);
    }

    /**
     * Merges the sets of two nodes, using rank to keep the trees shallow.
     *
     * @param nodeId1 first node ID
     * @param nodeId2 second node ID
     * @return true if the sets were distinct and were merged
     */
    public boolean union(String nodeId1, String nodeId2) {
        String root1 = find(nodeId1);
        String root2 = find(nodeId2);
        if (root1.equals(root2)) {
            return false; // An edge within the same set would create a cycle.
        }
        int rank1 = rank.get(root1);
        int rank2 = rank.get(root2);
        if (rank1 < rank2) {
            parent.put(root1, root2);
        } else if (rank1 > rank2) {
            parent.put(root2, root1);
        } else {
            parent.put(root2, root1);
            rank.put(root1, rank1 + 1);
        }
        return true;
    }

    /**
     * Checks whether two nodes belong to the same set.
     *
     * @param nodeId1 first node ID
     * @param nodeId2 second node ID
     * @return true if the nodes share a root
     */
    public boolean connected(String nodeId1, String nodeId2) {
        return find(nodeId1).equals(find(nodeId2));
    }
}
