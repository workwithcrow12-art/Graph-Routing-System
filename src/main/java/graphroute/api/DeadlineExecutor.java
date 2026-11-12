package graphroute.api;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;

/** Bounded transport tasks, including work performed before HttpHandler receives headers. */
final class DeadlineExecutor implements Executor, AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor deadlines;
    private final long timeoutNanos;

    DeadlineExecutor(int workerCount, int queueCapacity, Duration timeout) {
        if (workerCount < 1 || queueCapacity < 1 || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("HTTP executor limits must be positive");
        }
        timeoutNanos = timeout.toNanos();
        workers = new ThreadPoolExecutor(workerCount, workerCount, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().daemon(true).name("routing-http-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        deadlines = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().daemon(true).name("routing-http-deadline-", 0).factory());
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override public void execute(Runnable command) {
        Objects.requireNonNull(command);
        long submitted = System.nanoTime();
        // AbortPolicy propagates overload to the JDK dispatcher, which closes the connection.
        // Never run a rejected exchange on the dispatcher thread.
        workers.execute(() -> {
            RunningTask task = new RunningTask(Thread.currentThread());
            long remaining = timeoutNanos - (System.nanoTime() - submitted);
            // Queuing does not grant a fresh timeout. An expired queued connection is
            // interrupted before its transport task reads or writes the socket.
            if (remaining <= 0) task.expire();
            ScheduledFuture<?> timeout = null;
            try {
                try {
                    timeout = deadlines.schedule(task::expire, Math.max(0, remaining), TimeUnit.NANOSECONDS);
                } catch (RejectedExecutionException closing) {
                    // Run transport cleanup with an interrupted worker so socket I/O closes.
                    task.expire();
                }
                command.run();
            } finally {
                task.finish();
                if (timeout != null) timeout.cancel(false);
            }
        });
    }

    @Override public void close() {
        workers.shutdownNow();
        // Keep timers alive until every dequeued task has installed/removed its deadline.
        Thread.ofPlatform().daemon(true).name("routing-http-shutdown").start(() -> {
            try {
                while (!workers.awaitTermination(1, TimeUnit.SECONDS)) { /* wait for transport cleanup */ }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                deadlines.shutdownNow();
            }
        });
    }

    private static final class RunningTask {
        private final Thread worker;
        private boolean finished;

        RunningTask(Thread worker) { this.worker = worker; }

        synchronized void expire() {
            // The JDK HTTP transport uses interruptible SocketChannel reads/writes.
            // Interrupt the worker instead of closing an exchange from the timer: stream
            // close can wait on a lock held by that same blocked read/write.
            if (!finished) worker.interrupt();
        }

        synchronized void finish() {
            finished = true;
            // Serialize completion with expire(), so a late timer cannot interrupt the
            // next exchange on this pooled thread. Preserve no request's interrupt state.
            Thread.interrupted();
        }
    }
}
