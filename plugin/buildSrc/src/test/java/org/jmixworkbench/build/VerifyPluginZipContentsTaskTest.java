package org.jmixworkbench.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyPluginZipContentsTaskTest {
    private static final String DIGEST = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsAContainedArtifactAndReturnsItsDigest() throws Exception {
        Path archive = writeArchive("valid.zip", Map.of(
                "plugin/lib/main.jar", validPluginJar(),
                "plugin/lib/dependency.jar", nestedArchive(Map.of("safe.txt", bytes("safe payload"))),
                "plugin/README.txt", bytes("safe outer payload")
        ));

        assertEquals(DIGEST, VerifyPluginZipContentsTask.inspectArchive(archive, "idea253"));
    }

    @Test
    void rejectsOuterAndNestedTraversalEntryNames() throws Exception {
        Path outerTraversal = writeArchive("outer-traversal.zip", Map.of(
                "../escape.txt", bytes("escape"),
                "plugin/lib/main.jar", validPluginJar()
        ));
        assertThrows(
                IllegalStateException.class,
                () -> VerifyPluginZipContentsTask.inspectArchive(outerTraversal, "idea253")
        );
        assertThrows(
                IllegalStateException.class,
                () -> VerifyPluginZipContentsTask.inspectPath("safe/../escape.txt", "synthetic.zip")
        );

        Path nestedTraversal = writeArchive("nested-traversal.zip", Map.of(
                "plugin/lib/main.jar", validPluginJar(),
                "plugin/lib/dependency.jar", nestedArchive(Map.of("../escape.txt", bytes("escape")))
        ));
        assertThrows(
                IllegalStateException.class,
                () -> VerifyPluginZipContentsTask.inspectArchive(nestedTraversal, "idea253")
        );
    }

    @Test
    void scansEveryNestedJarAfterFindingTheMainPluginJar() throws Exception {
        Path archive = writeArchive("nested-secret.zip", Map.of(
                "plugin/lib/main.jar", validPluginJar(),
                "plugin/lib/zzz-dependency.jar", nestedArchive(Map.of(
                        "credentials.txt",
                        bytes("aws_access_key_id=AKIA1234567890ABCDEF")
                ))
        ));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> VerifyPluginZipContentsTask.inspectArchive(archive, "idea253")
        );
        assertTrue(error.getMessage().contains("AWS access key"));
    }

    @Test
    void scansNonJarOuterPayloadsForPrivateKeys() throws Exception {
        Path archive = writeArchive("outer-secret.zip", Map.of(
                "plugin/lib/main.jar", validPluginJar(),
                "plugin/credentials.txt", bytes("-----BEGIN PRIVATE KEY-----\nnot-a-real-key")
        ));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> VerifyPluginZipContentsTask.inspectArchive(archive, "idea253")
        );
        assertTrue(error.getMessage().contains("private key"));
    }

    private Path writeArchive(String name, Map<String, byte[]> entries) throws Exception {
        Path archive = temporaryDirectory.resolve(name);
        try (ZipOutputStream output = new ZipOutputStream(java.nio.file.Files.newOutputStream(archive))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return archive;
    }

    private byte[] validPluginJar() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/plugin.xml", bytes(
                "<idea-plugin><id>org.jmixworkbench</id><name>Jmix Visual Workbench</name>"
                        + "<idea-version since-build=\"253\" until-build=\"253.*\"/></idea-plugin>"
        ));
        entries.put("webui/index.html", bytes(
                "<script src=\"./assets/app-abcdef.js\"></script>"
                        + "<link href=\"./assets/app-abcdef.css\" rel=\"stylesheet\">"
        ));
        entries.put("webui/assets/app-abcdef.js", bytes("console.log('safe')"));
        entries.put("webui/assets/app-abcdef.css", bytes("body{}"));
        entries.put("webui/build-info.json", bytes("{\"inputSha256\":\"" + DIGEST + "\"}"));
        entries.put("icons/workbench.svg", bytes("<svg/>"));
        entries.put("LICENSE", bytes("license"));
        entries.put("NOTICE", bytes("notice"));
        return nestedArchive(entries);
    }

    private static byte[] nestedArchive(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
