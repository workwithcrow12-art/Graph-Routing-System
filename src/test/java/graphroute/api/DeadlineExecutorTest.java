package graphroute.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.Pipe;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DeadlineExecutorTest {
    @Test void interruptsBlockedReadAndNextTaskHasNoStaleInterrupt() throws Exception {
        var pipe = Pipe.open();
        try (var source = pipe.source(); var sink = pipe.sink();
             var executor = new DeadlineExecutor(1, 1, Duration.ofMillis(200))) {
            var started = new CountDownLatch(1);
            var result = new CompletableFuture<Boolean>();
            executor.execute(() -> {
                started.countDown();
                try {
                    source.read(ByteBuffer.allocate(1));
                    result.complete(false);
                } catch (ClosedByInterruptException expected) {
                    result.complete(true);
                } catch (Exception exception) {
                    result.completeExceptionally(exception);
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(result.get(3, TimeUnit.SECONDS));
            // Submit a fresh request after expiry; a request already queued during
            // the blocked read may legitimately exhaust its own submission deadline.
            var next = new CompletableFuture<Boolean>();
            executor.execute(() -> next.complete(Thread.currentThread().isInterrupted()));
            assertFalse(next.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void interruptsBlockedWriteWithoutWaitingForStreamCloseLock() throws Exception {
        var pipe = Pipe.open();
        try (var source = pipe.source(); var sink = pipe.sink();
             var executor = new DeadlineExecutor(1, 1, Duration.ofMillis(200))) {
            var result = new CompletableFuture<Boolean>();
            executor.execute(() -> {
                // No reader: a fixed 2 MiB buffer exceeds the bounded pipe capacity.
                var bytes = ByteBuffer.allocate(2 * 1024 * 1024);
                try {
                    while (bytes.hasRemaining()) sink.write(bytes);
                    result.complete(false);
                } catch (ClosedByInterruptException expected) {
                    result.complete(true);
                } catch (Exception exception) {
                    result.completeExceptionally(exception);
                }
            });
            assertTrue(result.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void overloadRejectsInsteadOfRunningOnTheSubmittingThread() throws Exception {
        try (var executor = new DeadlineExecutor(1, 1, Duration.ofSeconds(5))) {
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try {
                executor.execute(() -> {
                    started.countDown();
                    try { release.await(); } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                executor.execute(() -> { });
                assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> fail("Must not run")));
            } finally {
                release.countDown();
            }
        }
    }
}
