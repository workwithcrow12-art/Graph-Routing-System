package graphroute;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import graphroute.routing.TrafficSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_OUT)
class MainTest {
    private static final String ORIGIN = "tx-khuat-duy-tien-nguyen-trai";
    private static final String DESTINATION = "tx-le-van-luong-hoang-dao-thuy";
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path temporary;
    @Test void preservesVietnameseNamesSpacesAndUnicodePathsInWindowsLauncher() throws Exception {
        String[] expected = {"route", "a", "b", "--avoid", "Khuất Duy Tiến", "--nodes", "C:/dữ liệu/nodes.json"};
        String encoded = Base64.getEncoder().encodeToString(new ObjectMapper().writeValueAsBytes(expected));
        assertArrayEquals(expected, Main.decodeArguments(new String[]{"--utf8-arguments", encoded}));
        assertArrayEquals(expected, Main.decodeArguments(expected));
    }

    @Test void rejectsMalformedEncodedArguments() {
        assertThrows(Exception.class, () -> Main.decodeArguments(new String[]{"--utf8-arguments"}));
        assertThrows(Exception.class, () -> Main.decodeArguments(new String[]{"--utf8-arguments", "!"}));
        String encoded = Base64.getEncoder().encodeToString("[null]".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> Main.decodeArguments(new String[]{"--utf8-arguments", encoded}));
    }

    @Test void listsAllEightAlgorithmsAndExecutesThemWithBoundedTraces() throws Exception {
        JsonNode descriptors = json.readTree(output("algorithms"));
        assertEquals(8, descriptors.size());
        for (JsonNode descriptor : descriptors) {
            List<String> arguments = new ArrayList<>(List.of("analyze", descriptor.path("id").asText(), "--trace-limit", "1"));
            if (!descriptor.path("category").asText().equals("SPANNING_TREE")) {
                arguments.addAll(List.of("--start", ORIGIN, "--goal", DESTINATION));
            }
            JsonNode response = json.readTree(output(fixture(arguments.toArray(String[]::new))));
            assertEquals(descriptor.path("id").asText(), response.path("algorithm").asText());
            assertEquals("SUCCESS", response.path("status").asText(), response.toString());
            assertEquals(1, response.path("trace").size());
            assertTrue(response.path("metrics").path("traceTruncated").asBoolean());
            assertFalse(response.path("nodeIds").isEmpty());
        }
    }

    @Test void validatesAnalysisArgumentsWithoutExitingTheJvm() {
        for (String[] arguments : List.of(
                new String[]{"analyze", "bfs", "--trace-limit", "-1"},
                new String[]{"analyze", "bfs", "--trace-limit", "1.5"},
                new String[]{"analyze", "unknown"},
                new String[]{"analyze", "dijkstra"},
                new String[]{"analyze", "bfs", "--start", "missing"},
                new String[]{"analyze", "bfs", "--trace-limit"},
                new String[]{"analyze", "bfs", "--start", ORIGIN, "--start", DESTINATION},
                new String[]{"analyze", "bfs", "--unknown", "value"})) {
            assertThrows(IllegalArgumentException.class, () -> Main.run(fixture(arguments)), Arrays.toString(arguments));
        }
    }

    @Test void supportsSearchingMapExportAndZeroTraceOutput() throws Exception {
        assertEquals(8, json.readTree(output(fixture("map"))).path("places").size());
        assertFalse(json.readTree(output(fixture("search", "nguyen", "trai"))).isEmpty());
        JsonNode analysis = json.readTree(output(fixture("analyze", "bfs", "--trace-limit", "0")));
        assertTrue(analysis.path("trace").isEmpty());
        assertEquals(8, analysis.path("nodeIds").size());
        assertEquals(8, analysis.path("metrics").path("visitedNodes").asInt());
    }

    @Test void loadsObservedTrafficAndFastestPreferenceFromCli() throws Exception {
        JsonNode baseline = json.readTree(output(fixture("route", ORIGIN, DESTINATION)));
        JsonNode route = baseline.path("routes").get(0);
        String from = route.path("path").get(0).path("id").asText();
        String to = route.path("path").get(1).path("id").asText();
        Instant now = Instant.now();
        Path traffic = temporary.resolve("traffic.json");
        json.writeValue(traffic.toFile(), new TrafficSnapshot("CLI fixture observation", now.toString(),
                now.plusSeconds(3600).toString(), List.of(new TrafficSnapshot.Update(from, to, null, "driving", .1, false))));
        JsonNode shortest = json.readTree(output(fixture("route", ORIGIN, DESTINATION,
                "--traffic", traffic.toString(), "--preference", "shortest")));
        JsonNode fastest = json.readTree(output(fixture("route", ORIGIN, DESTINATION,
                "--traffic", traffic.toString(), "--preference", "fastest")));
        assertTrue(fastest.path("traffic").path("active").asBoolean());
        assertEquals("fastest", fastest.path("preference").asText());
        assertEquals("CLI fixture observation", fastest.path("traffic").path("source").asText());
        assertTrue(fastest.path("routes").get(0).path("durationSeconds").asLong()
                < shortest.path("routes").get(0).path("durationSeconds").asLong());
        assertEquals(1935, shortest.path("routes").get(0).path("distanceMeters").asDouble(), 1e-8);
    }

    private static String[] fixture(String... arguments) {
        List<String> complete = new ArrayList<>(List.of(arguments));
        complete.addAll(List.of("--nodes", Path.of("src/main/resources/data/thanh_xuan_nodes.json").toAbsolutePath().toString(),
                "--edges", Path.of("src/main/resources/data/thanh_xuan_edges.json").toAbsolutePath().toString()));
        return complete.toArray(String[]::new);
    }

    private static String output(String... arguments) throws Exception {
        PrintStream previous = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            Main.run(arguments);
        } finally {
            System.setOut(previous);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
