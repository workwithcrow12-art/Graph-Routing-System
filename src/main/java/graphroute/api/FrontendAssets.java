package graphroute.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Serves only the Vite index and flat assets directory, never arbitrary workspace files. */
final class FrontendAssets {
    private static final int MAX_ASSET_BYTES = 8 * 1024 * 1024;
    record Asset(byte[] bytes, String contentType) { }
    private final Path directory;
    private final boolean bundled;

    FrontendAssets() { this(Path.of("src/main/frontend/dist"), true); }
    FrontendAssets(Path directory) { this(directory, false); }
    private FrontendAssets(Path directory, boolean bundled) {
        this.directory = directory.toAbsolutePath().normalize(); this.bundled = bundled;
    }

    Asset read(String path) throws IOException {
        String file;
        if (path.equals("/") || path.equals("/index.html")) file = "index.html";
        else if (path.matches("/assets/[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.(js|css|svg|png|woff2)")) file = path.substring(1);
        else return null;
        ClassLoader loader = FrontendAssets.class.getClassLoader();
        try (InputStream resource = bundled ? loader.getResourceAsStream("web/" + file) : null) {
            if (resource != null) return asset(file, resource);
        }
        Path candidate = directory.resolve(file).normalize();
        if (!candidate.startsWith(directory) || !Files.isRegularFile(candidate)) return null;
        // A symlink in dist must not expose a file outside the compiled frontend.
        if (!candidate.toRealPath().startsWith(directory.toRealPath())) return null;
        try (InputStream input = Files.newInputStream(candidate)) { return asset(file, input); }
    }

    private static Asset asset(String file, InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_ASSET_BYTES + 1);
        if (bytes.length > MAX_ASSET_BYTES) throw new IOException("Frontend asset exceeds 8 MiB");
        String contentType = file.endsWith(".html") ? "text/html; charset=utf-8"
                : file.endsWith(".js") ? "text/javascript; charset=utf-8"
                : file.endsWith(".css") ? "text/css; charset=utf-8"
                : file.endsWith(".svg") ? "image/svg+xml"
                : file.endsWith(".woff2") ? "font/woff2" : "image/png";
        return new Asset(bytes, contentType);
    }
}
