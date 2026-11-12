package graphroute.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OSMDataUpdaterTest {
    @TempDir Path temporary;

    @Test
    void reusesVerifiedLocalCacheWithoutMakingANetworkRequest() throws Exception {
        var bbox = new OSMDataUpdater.BoundingBox(20.99, 105.795, 21.007, 105.82);
        Path file = temporary.resolve("cached.json");
        byte[] bytes = """
                {"elements":[{"type":"way","id":10,"nodes":[1,2],
                  "geometry":[{"lat":21,"lon":105},{"lat":21.001,"lon":105.001}],
                  "tags":{"highway":"residential"}}]}
                """.getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes);
        String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        new ObjectMapper().writeValue(file.resolveSibling("cached.json.metadata.json").toFile(), Map.of("query", bbox.query(), "sha256", checksum));
        var graph = OSMDataUpdater.download(bbox, file, URI.create("https://unreachable.invalid/api"), false);
        assertEquals(2, graph.nodeCount());
        assertEquals(2, graph.edgeCount());
        Files.writeString(file, "{}");
        assertThrows(IOException.class, () -> OSMDataUpdater.download(bbox, file, URI.create("https://unreachable.invalid/api"), false));
    }

    @Test
    void refusesToSilentlyReuseAnotherBoundingBoxOrUnverifiedCache() throws Exception {
        Path file = Files.writeString(temporary.resolve("cached.json"), "{}");
        var bbox = new OSMDataUpdater.BoundingBox(20.99, 105.795, 21.007, 105.82);
        assertThrows(IllegalArgumentException.class, () -> OSMDataUpdater.download(bbox, file, URI.create("https://unreachable.invalid/api"), false));
        assertThrows(IllegalArgumentException.class, () -> OSMDataUpdater.download(bbox, file, URI.create("http://unreachable.invalid/api"), false));
        assertThrows(IllegalArgumentException.class, () -> OSMDataUpdater.download(bbox, file, URI.create("https://user:password@unreachable.invalid/api"), false));
    }

    @Test
    void stalledResponseBodyIsClosedAtDeadline() {
        class StalledInput extends InputStream {
            boolean closed;
            @Override public synchronized int read() throws IOException {
                while (!closed) {
                    try { wait(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IOException(exception); }
                }
                return -1;
            }
            @Override public synchronized void close() { closed = true; notifyAll(); }
        }
        StalledInput input = new StalledInput();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(IOException.class, () -> OSMDataUpdater.readBounded(input, Duration.ofMillis(30))));
        assertTrue(input.closed);
    }

    @Test
    void completedBodyIsReadWithoutWaitingForDeadline() throws Exception {
        byte[] bytes = "test payload".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(bytes, OSMDataUpdater.readBounded(new ByteArrayInputStream(bytes), Duration.ofSeconds(10)));
    }
}
