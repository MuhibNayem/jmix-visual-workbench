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

    @Test
    void rejectsAPluginWhosePackagedDescriptorOmitsTheNativeEntityEditor() throws Exception {
        byte[] pluginJar = validPluginJar();
        byte[] withoutEntityEditor = rewriteNestedEntry(
                pluginJar,
                "META-INF/plugin.xml",
                text(pluginJar, "META-INF/plugin.xml").replace(
                        "<fileEditorProvider implementation=\"org.jmixworkbench.editor.JmixEntityFileEditorProvider\" />",
                        ""
                )
        );
        Path archive = writeArchive("missing-entity-editor.zip", Map.of(
                "plugin/lib/main.jar", withoutEntityEditor
        ));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> VerifyPluginZipContentsTask.inspectArchive(archive, "idea253")
        );
        assertTrue(error.getMessage().contains("JmixEntityFileEditorProvider"));
    }

    @Test
    void rejectsAPluginWhosePackagedDescriptorOmitsNativeRepositoryInjection() throws Exception {
        byte[] pluginJar = validPluginJar();
        byte[] withoutRepositoryInjection = rewriteNestedEntry(
                pluginJar,
                "META-INF/plugin.xml",
                text(pluginJar, "META-INF/plugin.xml").replace(
                        "<action id=\"JmixWorkbench.InjectRepository\" "
                                + "class=\"org.jmixworkbench.actions.InjectJmixRepositoryAction\">"
                                + "<add-to-group group-id=\"GenerateGroup\"/></action>",
                        ""
                )
        );
        Path archive = writeArchive("missing-repository-injection.zip", Map.of(
                "plugin/lib/main.jar", withoutRepositoryInjection
        ));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> VerifyPluginZipContentsTask.inspectArchive(archive, "idea253")
        );
        assertTrue(error.getMessage().contains("JmixWorkbench.InjectRepository"));
    }

    @Test
    void rejectsAPluginWhosePackagedDescriptorOmitsRepositoryMethodRefactoring() throws Exception {
        byte[] pluginJar = validPluginJar();
        byte[] withoutRefactorService = rewriteNestedEntry(
                pluginJar,
                "META-INF/plugin.xml",
                text(pluginJar, "META-INF/plugin.xml").replace(
                        "<projectService serviceImplementation=\"org.jmixworkbench.services.RepositoryMethodRefactorService\" />",
                        ""
                )
        );
        Path archive = writeArchive("missing-repository-refactor.zip", Map.of(
                "plugin/lib/main.jar", withoutRefactorService
        ));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> VerifyPluginZipContentsTask.inspectArchive(archive, "idea253")
        );
        assertTrue(error.getMessage().contains("RepositoryMethodRefactorService"));
    }

    @Test
    void rejectsAPluginWhosePackagedDescriptorOmitsAggregateUpdateService() throws Exception {
        byte[] pluginJar = validPluginJar();
        byte[] withoutAggregateService = rewriteNestedEntry(
                pluginJar,
                "META-INF/plugin.xml",
                text(pluginJar, "META-INF/plugin.xml").replace(
                        "<projectService serviceImplementation=\"org.jmixworkbench.services.AggregateUpdateServiceChangeService\" />",
                        ""
                )
        );
        Path archive = writeArchive("missing-aggregate-update-service.zip", Map.of(
                "plugin/lib/main.jar", withoutAggregateService
        ));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> VerifyPluginZipContentsTask.inspectArchive(archive, "idea253")
        );
        assertTrue(error.getMessage().contains("AggregateUpdateServiceChangeService"));
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
                        + "<depends>com.intellij.gradle</depends>"
                        + "<extensions>"
                        + "<newProjectWizard.generator implementation=\"org.jmixworkbench.project.JmixNewProjectWizard\" />"
                        + "<fileEditorProvider implementation=\"org.jmixworkbench.editor.JmixEntityFileEditorProvider\" />"
                        + "<projectService serviceImplementation=\"org.jmixworkbench.toolwindow.WorkbenchNavigationService\" />"
                        + "<projectService serviceImplementation=\"org.jmixworkbench.services.EntityEventListenerService\" />"
                        + "<projectService serviceImplementation=\"org.jmixworkbench.services.RepositoryMethodRefactorService\" />"
                        + "<projectService serviceImplementation=\"org.jmixworkbench.services.AggregateUpdateServiceChangeService\" />"
                        + "</extensions>"
                        + "<actions><action id=\"JmixWorkbench.InjectRepository\" "
                        + "class=\"org.jmixworkbench.actions.InjectJmixRepositoryAction\">"
                        + "<add-to-group group-id=\"GenerateGroup\"/></action></actions>"
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
        entries.put("project-template/gradle/wrapper/gradle-wrapper.jar", new byte[]{0, 1, 2});
        entries.put("project-template/gradlew", bytes("#!/bin/sh"));
        entries.put("project-template/gradlew.bat", bytes("@echo off"));
        entries.put(
                "org/jmixworkbench/project/JmixNewProjectWizard.class",
                new byte[]{0, 1, 2}
        );
        entries.put(
                "org/jmixworkbench/project/JmixProjectTemplateGenerator.class",
                new byte[]{0, 1, 2}
        );
        entries.put(
                "org/jmixworkbench/project/JmixProjectInstaller.class",
                new byte[]{0, 1, 2}
        );
        entries.put(
                "org/jmixworkbench/services/EntityEventListenerService.class",
                new byte[]{0, 1, 2}
        );
        entries.put(
                "org/jmixworkbench/actions/InjectJmixRepositoryAction.class",
                new byte[]{0, 1, 2}
        );
        entries.put(
                "org/jmixworkbench/services/RepositoryMethodRefactorService.class",
                new byte[]{0, 1, 2}
        );
        entries.put(
                "org/jmixworkbench/services/AggregateUpdateServiceChangeService.class",
                new byte[]{0, 1, 2}
        );
        entries.put(
                "org/jmixworkbench/generator/AggregateUpdateServiceGenerator.class",
                new byte[]{0, 1, 2}
        );
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

    private static String text(byte[] archive, String entryName) throws Exception {
        try (java.util.zip.ZipInputStream input =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalArgumentException("Missing nested entry: " + entryName);
    }

    private static byte[] rewriteNestedEntry(
            byte[] archive,
            String targetEntry,
            String replacement
    ) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream input =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries.put(
                        entry.getName(),
                        targetEntry.equals(entry.getName())
                                ? bytes(replacement)
                                : input.readAllBytes()
                );
            }
        }
        return nestedArchive(entries);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
