package graphroute.sorting;

@FunctionalInterface
public interface SortObserver {
    void afterIteration(String phase, int iteration, int[] values);
}