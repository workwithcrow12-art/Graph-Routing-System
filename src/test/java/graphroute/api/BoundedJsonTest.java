package graphroute.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphroute.routing.RoutingException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BoundedJsonTest {
    @Test void countsEncodedBytesAndAcceptsExactlyTheLimit() throws Exception {
        var mapper = new ObjectMapper();
        var value = Map.of("name", "Đường phố");
        byte[] expected = mapper.writeValueAsBytes(value);
        assertArrayEquals(expected, BoundedJson.encode(mapper, value, expected.length));
        var failure = assertThrows(RoutingException.class,
                () -> BoundedJson.encode(mapper, value, expected.length - 1));
        assertEquals("RESPONSE_LIMIT_EXCEEDED", failure.getCode());
        assertArrayEquals(expected, BoundedJson.encode(mapper, value, expected.length));
    }
}
