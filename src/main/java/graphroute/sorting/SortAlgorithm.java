package graphroute.sorting;

@FunctionalInterface
public interface SortAlgorithm {
    void sort(int[] values, SortObserver observer, SortMetrics metrics);
}