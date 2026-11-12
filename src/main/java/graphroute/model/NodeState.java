package graphroute.model;

public enum NodeState {
    /** The algorithm has yet to discover this node. */
    UNVISITED,
    /** The node is in the frontier. */
    VISITING,
    /** The algorithm has processed this node. */
    VISITED,
    /** The node is on the final shortest path. */
    IN_PATH
}
