package graphroute.sorting;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;

public final class SortingEvaluationMain {
    private static final int MAX_VALUE = 100_000;
    private static final int[] BENCHMARK_SIZES = { 100, 1_000, 10_000, 100_000 };
    private static final int QUADRATIC_LIMIT = 10_000;
    private static final Random RANDOM = new Random(20260917L);

    private SortingEvaluationMain() {
    }

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--benchmark")) {
            benchmark(Arrays.asList(args).contains("--force-quadratic"));
            return;
        }
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("1 - Nhập dãy số nhỏ");
            System.out.println("2 - Sinh dãy số ngẫu nhiên");
            System.out.println("3 - Benchmark N = 100, 1000, 10000, 100000");
            System.out.print("Lựa chọn: ");
            int choice = Integer.parseInt(scanner.nextLine().trim());
            if (choice == 1) {
                System.out.print("Nhập các số nguyên, cách nhau bởi dấu cách: ");
                String[] tokens = scanner.nextLine().trim().split("\\s+");
                int[] values = new int[tokens.length];
                for (int index = 0; index < tokens.length; index++) {
                    values[index] = Integer.parseInt(tokens[index]);
                }
                evaluateWithStates(values);
            } else if (choice == 2) {
                System.out.print("Nhập N: ");
                evaluateWithStates(randomArray(Integer.parseInt(scanner.nextLine().trim())));
            } else if (choice == 3) {
                benchmark(false);
            } else {
                System.out.println("Lựa chọn không hợp lệ.");
            }
        }
    }

    private static void evaluateWithStates(int[] input) {
        System.out.println("Dãy ban đầu: " + Arrays.toString(input));
        for (Map.Entry<String, SortAlgorithm> entry : algorithms().entrySet()) {
            int[] values = input.clone();
            SortMetrics metrics = new SortMetrics();
            System.out.println("\n=== " + entry.getKey() + " ===");
            entry.getValue().sort(values, (phase, iteration, state) ->
                    System.out.printf("%s - vòng %d: %s%n", phase, iteration, Arrays.toString(state)), metrics);
            System.out.printf("Kết quả: %s | So sánh: %d | Đổi chỗ/di chuyển: %d%n",
                    Arrays.toString(values), metrics.comparisons(), metrics.swaps());
        }
    }

    private static void benchmark(boolean forceQuadratic) {
        System.out.println("\nBenchmark (giá trị ngẫu nhiên trong [1, 100000])");
        System.out.printf("%-18s %-10s %-16s %-16s %-16s%n", "Thuật toán", "N", "Thời gian (ms)", "So sánh", "Đổi chỗ/di chuyển");
        for (Map.Entry<String, SortAlgorithm> entry : algorithms().entrySet()) {
            for (int size : BENCHMARK_SIZES) {
                if (!forceQuadratic && isQuadratic(entry.getKey()) && size > QUADRATIC_LIMIT) {
                    System.out.printf("%-18s %-10d %-16s%n", entry.getKey(), size, "BỎ QUA (O(N^2))");
                    continue;
                }
                int[] values = randomArray(size);
                SortMetrics metrics = new SortMetrics();
                long start = System.nanoTime();
                entry.getValue().sort(values, null, metrics);
                long elapsed = System.nanoTime() - start;
                System.out.printf("%-18s %-10d %-16.3f %-16d %-16d%n", entry.getKey(), size,
                        elapsed / 1_000_000.0, metrics.comparisons(), metrics.swaps());
            }
        }
        System.out.println("Ghi chú: dùng --force-quadratic nếu thật sự cần đo phép đo O(N^2) với N lớn.");
    }

    private static boolean isQuadratic(String name) {
        return name.equals("Bubble sort") || name.equals("Selection sort") || name.equals("Insertion sort");
    }

    private static int[] randomArray(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("N phải không âm");
        }
        int[] values = new int[size];
        for (int index = 0; index < size; index++) {
            values[index] = RANDOM.nextInt(MAX_VALUE) + 1;
        }
        return values;
    }

    private static Map<String, SortAlgorithm> algorithms() {
        Map<String, SortAlgorithm> algorithms = new LinkedHashMap<>();
        algorithms.put("Bubble sort", SortingAlgorithms::bubbleSort);
        algorithms.put("Selection sort", SortingAlgorithms::selectionSort);
        algorithms.put("Insertion sort", SortingAlgorithms::insertionSort);
        algorithms.put("Merge sort", SortingAlgorithms::mergeSort);
        algorithms.put("Quick sort", SortingAlgorithms::quickSort);
        return algorithms;
    }
}
