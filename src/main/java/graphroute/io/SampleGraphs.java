package graphroute.io;

public final class SampleGraphs {
    private SampleGraphs() { }

    /** Small connected undirected network usable by all eight analysis algorithms. */
    public static graphroute.model.Graph thanhXuan() {
        return new GraphFileManager().loadThanhXuanGraph().freeze();
    }
}
