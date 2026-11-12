package graphroute.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphroute.routing.RoutingException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Counts UTF-8 output bytes while Jackson writes, before allocating an oversized response. */
final class BoundedJson {
    private BoundedJson() { }

    static byte[] encode(ObjectMapper mapper, Object value, int maximum) throws IOException {
        if (maximum < 1) throw new IllegalArgumentException("Output limit must be positive");
        var bytes = new ByteArrayOutputStream(Math.min(maximum, 8192));
        var bounded = new OutputStream() {
            boolean exceeded;
            private void reserve(int length) throws IOException {
                if (length > maximum - bytes.size()) {
                    exceeded = true;
                    throw new IOException("JSON output limit exceeded");
                }
            }
            @Override public void write(int value) throws IOException {
                reserve(1);
                bytes.write(value);
            }
            @Override public void write(byte[] values, int offset, int length) throws IOException {
                reserve(length);
                bytes.write(values, offset, length);
            }
        };
        try {
            mapper.writeValue(bounded, value);
        } catch (IOException exception) {
            if (bounded.exceeded) throw new RoutingException("RESPONSE_LIMIT_EXCEEDED",
                    "JSON response exceeds " + maximum + " bytes; use a smaller dataset or request");
            throw exception;
        }
        return bytes.toByteArray();
    }
}
