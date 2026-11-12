package graphroute;

import static org.junit.jupiter.api.Assertions.*;

import graphroute.sorting.SortAlgorithm;
import graphroute.sorting.SortMetrics;
import graphroute.sorting.SortingAlgorithms;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.MethodSource;

class SortingAlgorithmsTest {
    @Test
    @Timeout(10)
    void quickSortHandlesLargeOrderedReverseEqualAndRepeatedInputs() {
        int size = 100_000;
        assertMatchesOracle(SortingAlgorithms::quickSort, java.util.stream.IntStream.range(0, size).toArray());
        assertMatchesOracle(SortingAlgorithms::quickSort, java.util.stream.IntStream.range(0, size).map(i -> size - i).toArray());
        int[] equal = new int[size];
        Arrays.fill(equal, 7);
        SortMetrics metrics = new SortMetrics();
        SortingAlgorithms.quickSort(equal, null, metrics);
        assertTrue(Arrays.stream(equal).allMatch(value -> value == 7));
        assertTrue(metrics.comparisons() < size * 2L, "Equal keys should require linear work");
        assertMatchesOracle(SortingAlgorithms::quickSort, new Random(42).ints(size, -3, 4).toArray());
    }

    static Stream<SortAlgorithm> algorithms() { return Stream.of(
            SortingAlgorithms::bubbleSort,
            SortingAlgorithms::selectionSort,
            SortingAlgorithms::insertionSort,
            SortingAlgorithms::mergeSort,
            SortingAlgorithms::quickSort); }

    @ParameterizedTest
    @MethodSource("algorithms")
    void sortsEmptySingletonOrderedReverseDuplicateAndExtremeInputs(SortAlgorithm algorithm) {
        List<int[]> examples = List.of(new int[]{}, new int[]{1}, new int[]{1, 2, 3, 4},
                new int[]{4, 3, 2, 1}, new int[]{5, -2, 5, 0, 9, 1, -2}, new int[]{7, 7, 7, 7},
                new int[]{Integer.MAX_VALUE, 0, Integer.MIN_VALUE, -1, Integer.MAX_VALUE});
        for (int[] example : examples) assertMatchesOracle(algorithm, example);
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void matchesJdkSortForSeededRandomArrays(SortAlgorithm algorithm) {
        Random random = new Random(87431);
        for (int size : new int[]{0, 1, 2, 3, 31, 64, 129, 256}) {
            for (int trial = 0; trial < 8; trial++) {
                assertMatchesOracle(algorithm, random.ints(size, -50, 51).toArray());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void observersReceiveIndependentSnapshotsAndCannotCorruptTheSort(SortAlgorithm algorithm) {
        int[] input = {5, 2, 4, 1, 3};
        List<int[]> snapshots = new ArrayList<>();
        List<Integer> iterations = new ArrayList<>();
        List<String> phases = new ArrayList<>();
        algorithm.sort(input, (phase, iteration, values) -> {
            assertNotSame(input, values);
            if (!snapshots.isEmpty()) assertNotSame(snapshots.getLast(), values);
            snapshots.add(values);
            iterations.add(iteration);
            phases.add(phase);
            Arrays.fill(values, 42);
        }, new SortMetrics());
        assertArrayEquals(new int[]{1, 2, 3, 4, 5}, input);
        assertFalse(snapshots.isEmpty());
        for (int index = 0; index < snapshots.size(); index++) {
            assertEquals(index + 1, iterations.get(index));
            assertFalse(phases.get(index).isBlank());
            assertArrayEquals(new int[]{42, 42, 42, 42, 42}, snapshots.get(index));
        }
    }

    @ParameterizedTest
    @MethodSource("algorithms")
    void recordsComparisonsAndMovesAndValidatesRequiredArguments(SortAlgorithm algorithm) {
        SortMetrics metrics = new SortMetrics();
        algorithm.sort(new int[]{2, 1}, null, metrics);
        assertEquals(1, metrics.comparisons());
        assertTrue(metrics.swaps() > 0);
        assertThrows(NullPointerException.class, () -> algorithm.sort(null, null, new SortMetrics()));
        assertThrows(NullPointerException.class, () -> algorithm.sort(new int[]{1}, null, null));
        SortMetrics empty = new SortMetrics();
        algorithm.sort(new int[]{}, (phase, iteration, values) -> fail("Empty input has no iterations"), empty);
        assertEquals(0, empty.comparisons());
        assertEquals(0, empty.swaps());
    }

    private static void assertMatchesOracle(SortAlgorithm algorithm, int[] input) {
        int[] expected = input.clone();
        Arrays.sort(expected);
        int[] actual = input.clone();
        SortMetrics metrics = new SortMetrics();
        algorithm.sort(actual, null, metrics);
        assertArrayEquals(expected, actual);
        assertTrue(metrics.comparisons() >= 0);
        assertTrue(metrics.swaps() >= 0);
    }
}
