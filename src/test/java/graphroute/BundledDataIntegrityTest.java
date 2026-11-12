package graphroute;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class BundledDataIntegrityTest {
    @Test void provenanceDistinguishesQueryBboxFromActualGeometryBounds() {
        var graph = new graphroute.io.GraphFileManager().loadDefaultGraph();
        assertEquals("20.990,105.795,21.007,105.820", graph.getProvenance().get("queryBbox"));
        String[] fields = graph.getProvenance().get("dataBounds").split(",");
        double south = Double.parseDouble(fields[0]), west = Double.parseDouble(fields[1]);
        double north = Double.parseDouble(fields[2]), east = Double.parseDouble(fields[3]);
        assertTrue(south < 20.990 && west < 105.795 && north > 21.007 && east > 105.820);
        graph.getAllEdges().forEach(edge -> edge.getGeometry().forEach(point -> {
            assertTrue(point.latitude() >= south - 1e-7 && point.latitude() <= north + 1e-7);
            assertTrue(point.longitude() >= west - 1e-7 && point.longitude() <= east + 1e-7);
        }));
    }

    @Test void bundledOsmChecksumMatchesExactResourceBytes() throws Exception {
        try (var data = getClass().getResourceAsStream("/data/thanh_xuan_osm.json");
             var metadata = getClass().getResourceAsStream("/data/thanh_xuan_osm.json.metadata.json")) {
            assertNotNull(data);
            assertNotNull(metadata);
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.readAllBytes()));
            assertEquals(new ObjectMapper().readTree(metadata).path("sha256").asText(), actual);
        }
    }
}
