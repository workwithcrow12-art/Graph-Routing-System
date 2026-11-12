package graphroute.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class FrontendAssetsTest {
    @TempDir Path temporary;

    @Test void productionFrontendAndItsAssetsAreOnTheRuntimeClasspath() throws Exception {
        var loader = FrontendAssets.class.getClassLoader();
        try (var index = loader.getResourceAsStream("web/index.html")) {
            assertNotNull(index, "Maven must build and embed the frontend without a separate npm command");
            String html = new String(index.readAllBytes(), StandardCharsets.UTF_8);
            var references = java.util.regex.Pattern.compile("(?:src|href)=\"(/assets/[^\"]+)\"").matcher(html);
            int count = 0;
            while (references.find()) {
                try (var asset = loader.getResourceAsStream("web" + references.group(1))) {
                    assertNotNull(asset, "Missing bundled asset: " + references.group(1));
                    assertTrue(asset.readAllBytes().length > 0);
                }
                count++;
            }
            assertTrue(count > 0, "The bundled index must reference its compiled frontend assets");
        }
    }

    @Test void servesOnlyCompiledIndexAndKnownAssetTypes() throws Exception {
        Path dist = Files.createDirectory(temporary.resolve("dist"));
        Files.createDirectory(dist.resolve("assets"));
        Files.writeString(dist.resolve("index.html"), "<!doctype html><h1>Routing UI</h1>");
        Files.writeString(dist.resolve("assets/app-abc.js"), "console.log('UI');");
        Files.writeString(dist.resolve("assets/app.css"), "body{margin:0}");
        Files.writeString(temporary.resolve("secret.txt"), "must not be exposed");
        var assets = new FrontendAssets(dist);
        assertTrue(new String(assets.read("/").bytes(), StandardCharsets.UTF_8).contains("Routing UI"));
        assertTrue(assets.read("/assets/app-abc.js").contentType().startsWith("text/javascript"));
        assertTrue(assets.read("/assets/app.css").contentType().startsWith("text/css"));
        for (String path : new String[]{"/../secret.txt", "/assets/../../secret.txt", "/pom.xml", "/assets/a.js.map", "/assets/missing.js"})
            assertNull(assets.read(path), path);
    }

    @Test void packagedFrontendCarriesItsCodeAndDependencyLicenses() throws Exception {
        var loader = FrontendAssets.class.getClassLoader();
        try (var license = loader.getResourceAsStream("web/LICENSE");
             var notices = loader.getResourceAsStream("web/THIRD-PARTY-LICENSES.md")) {
            assertNotNull(license, "Missing project license from the packaged frontend");
            assertNotNull(notices, "Missing third-party notices from the packaged frontend");
            assertTrue(new String(license.readAllBytes(), StandardCharsets.UTF_8).startsWith("MIT License"));
            String text = new String(notices.readAllBytes(), StandardCharsets.UTF_8);
            assertAll(
                    () -> assertTrue(text.contains("MapLibre contributors")),
                    () -> assertTrue(text.contains("SIL OPEN FONT LICENSE")),
                    () -> assertTrue(text.contains("## react-dom ")),
                    () -> assertTrue(text.contains("Copyright (c) 2011 Gary Court"))
            );
        }
    }

    @Test void missingBuildIsRepresentedWithoutExposingOtherFiles() throws Exception {
        assertNull(new FrontendAssets(temporary.resolve("absent")).read("/"));
    }
}
