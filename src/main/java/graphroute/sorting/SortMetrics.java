package graphroute.sorting;

public final class SortMetrics {
    private long comparisons;
    private long swaps;

    public void comparison() {
        comparisons++;
    }

    public void swap() {
        swaps++;
    }

    public long comparisons() {
        return comparisons;
    }

    public long swaps() {
        return swaps;
    }
}