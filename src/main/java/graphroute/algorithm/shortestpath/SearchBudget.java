package graphroute.algorithm.shortestpath;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.concurrent.CancellationException;

/** One request's cumulative allowance. Share across all legs; never share between threads. */
public final class SearchBudget {
    private long remainingWork;
    private int remainingSpurs;
    private long remainingPathBytes;
    private final long started;
    private final long timeoutNanos;
    private final LongSupplier nanoTime;
    private boolean unbounded;

    /** Library calls have no request quota, but still cooperate with thread cancellation. */
    public static SearchBudget unbounded() {
        SearchBudget budget = new SearchBudget(Long.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, Duration.ofNanos(Long.MAX_VALUE));
        budget.unbounded = true;
        return budget;
    }

    /** Covers the 300-node Floyd-Warshall and 25-million-relaxation Bellman-Ford caps. */
    public static SearchBudget analysis() {
        return new SearchBudget(50_000_000, 0, 128L * 1024 * 1024, Duration.ofSeconds(30));
    }

    public static SearchBudget routing() {
        return new SearchBudget(2_000_000, 2_048, 16L * 1024 * 1024, Duration.ofSeconds(5));
    }

    public SearchBudget(long work, int spurs, long pathBytes, Duration timeout) {
        this(work, spurs, pathBytes, timeout, System::nanoTime);
    }

    SearchBudget(long work, int spurs, long pathBytes, Duration timeout, LongSupplier nanoTime) {
        if (work < 1 || spurs < 0 || pathBytes < 1 || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Search limits must be positive (spur count may be zero)");
        }
        remainingWork = work;
        remainingSpurs = spurs;
        remainingPathBytes = pathBytes;
        timeoutNanos = timeout.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime);
        started = nanoTime.getAsLong();
    }

    public void check() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Search was cancelled");
        if (unbounded) return;
        if (nanoTime.getAsLong() - started >= timeoutNanos) {
            throw new LimitExceededException("Search exceeded its time limit");
        }
    }

    public void work() {
        check();
        if (unbounded) return;
        if (remainingWork == 0) throw new LimitExceededException("Search exceeded its work limit");
        remainingWork--;
    }

    void spur() {
        check();
        if (unbounded) return;
        if (remainingSpurs == 0) throw new LimitExceededException("Search exceeded its spur limit");
        remainingSpurs--;
    }

    /** Conservative allocation accounting for path copies, references and boxed edge signatures.
     * Cumulative rather than refunded, so rejected/duplicate candidates also consume the allowance.
     * This bounds path materialization, not the JVM's total memory usage.
     */
    public void path(long entries) {
        check();
        if (unbounded) {
            if (entries < 0) throw new IllegalArgumentException("Path entries must be nonnegative");
            return;
        }
        if (entries < 0 || entries > remainingPathBytes / 128) {
            throw new LimitExceededException("Search exceeded its path allocation limit");
        }
        remainingPathBytes -= entries * 128;
    }

    public static final class LimitExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private LimitExceededException(String message) { super(message); }
    }
}
