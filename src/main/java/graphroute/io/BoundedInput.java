package graphroute.io;

import java.io.IOException;
import java.io.InputStream;

/** Applies a byte limit before a parser can allocate an object tree; caller owns the stream. */
final class BoundedInput {
    private BoundedInput() { }

    static byte[] read(InputStream input, int maximum, String description) throws IOException {
        if (maximum < 1 || maximum == Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid byte limit");
        byte[] bytes = input.readNBytes(maximum + 1);
        if (bytes.length > maximum) throw new GraphSerializer.GraphDataException(description + " exceeds " + maximum + " bytes");
        return bytes;
    }
}
