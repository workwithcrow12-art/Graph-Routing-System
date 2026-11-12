package graphroute.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ImportLimitsTest {
    @TempDir Path directory;

    @Test void boundedReaderStopsAtFirstExtraByteAndLeavesOwnershipWithCaller() {
        var input = new InputStream() {
            int reads;
            boolean closed;
            @Override public int read() {
                if (++reads > 33) fail("Reader continued beyond the bound");
                return ' ';
            }
            @Override public void close() { closed = true; }
        };
        assertThrows(GraphSerializer.GraphDataException.class, () -> BoundedInput.read(input, 32, "fixture"));
        assertEquals(33, input.reads);
        assertFalse(input.closed);
    }

    @Test void legacyNamesAreBoundedBeforeGraphConstruction() {
        String nodes = "[{\"id\":\"a\",\"name\":\"" + "x".repeat(GraphSerializer.MAX_TEXT_CHARS + 1)
                + "\",\"latitude\":0,\"longitude\":0}]";
        assertThrows(GraphSerializer.GraphDataException.class,
                () -> new GraphSerializer().load(input(nodes), input("[]")));
    }

    @Test void longNamesAndExpandedJunctionNamesAreRejectedInXmlAndJson() {
        String longName = "x".repeat(OsmImporter.MAX_NAME_CHARS + 1);
        assertThrows(GraphSerializer.GraphDataException.class, () -> new OsmImporter().load(input(
                xml("<way id=\"1\"><nd ref=\"1\"/><nd ref=\"2\"/><tag k=\"highway\" v=\"residential\"/>"
                        + "<tag k=\"name\" v=\"" + longName + "\"/></way>"))));
        assertThrows(GraphSerializer.GraphDataException.class, () -> new OsmImporter().load(input(
                "{\"elements\":[{\"type\":\"node\",\"id\":1,\"lat\":0,\"lon\":0,\"tags\":{\"name\":\""
                        + longName + "\"}}]}")));
        StringBuilder ways = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            ways.append("<way id=\"").append(i).append("\"><nd ref=\"1\"/><nd ref=\"2\"/>")
                    .append("<tag k=\"highway\" v=\"residential\"/><tag k=\"name\" v=\"")
                    .append(i).append("x".repeat(511)).append("\"/></way>");
        }
        var exception = assertThrows(GraphSerializer.GraphDataException.class,
                () -> new OsmImporter().load(input(xml(ways.toString()))));
        assertTrue(exception.getMessage().contains("junction name"));
    }

    @Test void metadataRejectsOversizeDuplicateAndTrailingDocuments() throws Exception {
        Path graph = directory.resolve("fixture.osm");
        Files.writeString(graph, xml("<way id=\"1\"><nd ref=\"1\"/><nd ref=\"2\"/>"
                + "<tag k=\"highway\" v=\"residential\"/></way>"));
        Path metadata = directory.resolve("fixture.osm.metadata.json");
        for (String invalid : new String[]{" ".repeat(GraphFileManager.MAX_METADATA_BYTES + 1),
                "{\"name\":\"a\",\"name\":\"b\"}", "{} {}"}) {
            Files.writeString(metadata, invalid);
            assertThrows(GraphSerializer.GraphDataException.class, () -> new GraphFileManager().loadOsm(graph));
        }
        Files.writeString(metadata, "{\"name\":\"accepted\"}");
        assertEquals("accepted", new GraphFileManager().loadOsm(graph).getProvenance().get("name"));
    }

    private static String xml(String ways) {
        return "<osm><node id=\"1\" lat=\"0\" lon=\"0\"/><node id=\"2\" lat=\"0\" lon=\"0.01\"/>" + ways + "</osm>";
    }
    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
