package graphroute.sorting;

import java.util.Objects;

public final class SortingAlgorithms {
    private SortingAlgorithms() {
    }

    public static void bubbleSort(int[] values, SortObserver observer, SortMetrics metrics) {
        validate(values, metrics);
        for (int end = values.length - 1, pass = 1; end > 0; end--, pass++) {
            boolean changed = false;
            for (int index = 0; index < end; index++) {
                metrics.comparison();
                if (values[index] > values[index + 1]) {
                    exchange(values, index, index + 1, metrics);
                    changed = true;
                }
            }
            notify(observer, "Bubble sort", pass, values);
            if (!changed) {
                break;
            }
        }
    }

    public static void selectionSort(int[] values, SortObserver observer, SortMetrics metrics) {
        validate(values, metrics);
        for (int start = 0, pass = 1; start < values.length - 1; start++, pass++) {
            int minimum = start;
            for (int index = start + 1; index < values.length; index++) {
                metrics.comparison();
                if (values[index] < values[minimum]) {
                    minimum = index;
                }
            }
            if (minimum != start) {
                exchange(values, start, minimum, metrics);
            }
            notify(observer, "Selection sort", pass, values);
        }
    }

    public static void insertionSort(int[] values, SortObserver observer, SortMetrics metrics) {
        validate(values, metrics);
        for (int index = 1, pass = 1; index < values.length; index++, pass++) {
            int current = values[index];
            int position = index - 1;
            while (position >= 0) {
                metrics.comparison();
                if (values[position] <= current) {
                    break;
                }
                values[position + 1] = values[position];
                metrics.swap();
                position--;
            }
            values[position + 1] = current;
            notify(observer, "Insertion sort", pass, values);
        }
    }

    public static void mergeSort(int[] values, SortObserver observer, SortMetrics metrics) {
        validate(values, metrics);
        mergeSort(values, 0, values.length - 1, observer, metrics, new int[values.length], new int[] { 0 });
    }

    private static void mergeSort(int[] values, int left, int right, SortObserver observer,
            SortMetrics metrics, int[] buffer, int[] mergeCount) {
        if (left >= right) {
            return;
        }
        int middle = left + (right - left) / 2;
        mergeSort(values, left, middle, observer, metrics, buffer, mergeCount);
        mergeSort(values, middle + 1, right, observer, metrics, buffer, mergeCount);
        int leftIndex = left;
        int rightIndex = middle + 1;
        int target = left;
        while (leftIndex <= middle && rightIndex <= right) {
            metrics.comparison();
            if (values[leftIndex] <= values[rightIndex]) {
                buffer[target++] = values[leftIndex++];
            } else {
                buffer[target++] = values[rightIndex++];
            }
        }
        while (leftIndex <= middle) {
            buffer[target++] = values[leftIndex++];
        }
        while (rightIndex <= right) {
            buffer[target++] = values[rightIndex++];
        }
        for (int index = left; index <= right; index++) {
            values[index] = buffer[index];
            metrics.swap();
        }
        notify(observer, "Merge sort", ++mergeCount[0], values);
    }

    public static void quickSort(int[] values, SortObserver observer, SortMetrics metrics) {
        validate(values, metrics);
        quickSort(values, 0, values.length - 1, observer, metrics, new int[] { 0 });
    }

    private static void quickSort(int[] values, int low, int high, SortObserver observer,
            SortMetrics metrics, int[] partitionCount) {
        // Recurse only into the smaller partition: stack depth stays O(log n),
        // including adversarial inputs. Equal keys leave the search in one pass.
        while (low < high) {
            if (high - low == 1) {
                metrics.comparison();
                if (values[low] > values[high]) exchange(values, low, high, metrics);
                notify(observer, "Quick sort", ++partitionCount[0], values);
                return;
            }
            int middle = low + (high - low) / 2;
            if (compare(values[low], values[middle], metrics) > 0) exchange(values, low, middle, metrics);
            if (compare(values[low], values[high], metrics) > 0) exchange(values, low, high, metrics);
            if (compare(values[middle], values[high], metrics) > 0) exchange(values, middle, high, metrics);
            int pivot = values[middle];
            int smaller = low, index = low, greater = high;
            while (index <= greater) {
                int order = compare(values[index], pivot, metrics);
                if (order < 0) exchange(values, smaller++, index++, metrics);
                else if (order > 0) exchange(values, index, greater--, metrics);
                else index++;
            }
            notify(observer, "Quick sort", ++partitionCount[0], values);
            if (smaller - low < high - greater) {
                quickSort(values, low, smaller - 1, observer, metrics, partitionCount);
                low = greater + 1;
            } else {
                quickSort(values, greater + 1, high, observer, metrics, partitionCount);
                high = smaller - 1;
            }
        }
    }

    private static int compare(int first, int second, SortMetrics metrics) {
        metrics.comparison();
        return Integer.compare(first, second);
    }

    private static void exchange(int[] values, int first, int second, SortMetrics metrics) {
        if (first == second) {
            return;
        }
        int temporary = values[first];
        values[first] = values[second];
        values[second] = temporary;
        metrics.swap();
    }

    private static void validate(int[] values, SortMetrics metrics) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(metrics, "metrics");
    }

    private static void notify(SortObserver observer, String phase, int iteration, int[] values) {
        if (observer != null) {
            observer.afterIteration(phase, iteration, values.clone());
        }
    }
}
