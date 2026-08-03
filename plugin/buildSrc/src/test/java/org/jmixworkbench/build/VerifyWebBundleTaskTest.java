package org.jmixworkbench.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifyWebBundleTaskTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyExistingContainedResources() throws Exception {
        Path bundle = Files.createDirectory(temporaryDirectory.resolve("bundle"));
        Path assets = Files.createDirectory(bundle.resolve("assets"));
        Files.writeString(assets.resolve("app.js"), "console.log('ok')");

        assertDoesNotThrow(() -> VerifyWebBundleTask.validateResource(bundle, "assets/app.js"));
        assertThrows(
                IllegalStateException.class,
                () -> VerifyWebBundleTask.validateResource(bundle, "assets/missing.js")
        );
    }

    @Test
    void rejectsAbsoluteAndTraversalReferencesEvenWhenTargetsExist() throws Exception {
        Path bundle = Files.createDirectory(temporaryDirectory.resolve("bundle"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.js"), "outside");

        assertThrows(
                IllegalStateException.class,
                () -> VerifyWebBundleTask.validateResource(bundle, outside.toAbsolutePath().toString())
        );
        assertThrows(
                IllegalStateException.class,
                () -> VerifyWebBundleTask.validateResource(bundle, "../outside.js")
        );
        assertThrows(
                IllegalStateException.class,
                () -> VerifyWebBundleTask.validateResource(bundle, "..\\outside.js")
        );
    }
}
