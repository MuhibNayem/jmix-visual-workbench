package org.jmixworkbench.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public abstract class AssembleWebBundleTask extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getCompiledAssets();

    @Internal
    public abstract DirectoryProperty getInputRoot();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getDeclaredInputs();

    @Input
    public abstract Property<String> getPluginVersion();

    @Input
    public abstract Property<String> getRevision();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @TaskAction
    public void assemble() throws IOException {
        Path compiledAssets = getCompiledAssets().get().getAsFile().toPath();
        Path outputDirectory = getOutputDirectory().get().getAsFile().toPath();
        recreateDirectory(outputDirectory);

        try (var paths = Files.walk(compiledAssets)) {
            for (Path source : paths.sorted().toList()) {
                Path relative = compiledAssets.relativize(source);
                Path destination = outputDirectory.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination);
                }
            }
        }

        Path inputRoot = getInputRoot().get().getAsFile().toPath();
        String inputDigest = WebBundleFingerprint.digest(inputRoot, getDeclaredInputs().getFiles());
        List<String> inputPaths = WebBundleFingerprint.relativePaths(inputRoot, getDeclaredInputs().getFiles());
        String joinedPaths = inputPaths.stream()
                .map(path -> "    \"" + WebBundleFingerprint.jsonEscape(path) + "\"")
                .reduce((left, right) -> left + ",\n" + right)
                .orElse("");
        String manifest = """
                {
                  "pluginVersion": "%s",
                  "gitRevision": "%s",
                  "inputSha256": "%s",
                  "inputFiles": [
                %s
                  ]
                }
                """.formatted(
                WebBundleFingerprint.jsonEscape(getPluginVersion().get()),
                WebBundleFingerprint.jsonEscape(getRevision().get()),
                inputDigest,
                joinedPaths
        );
        Files.writeString(outputDirectory.resolve("build-info.json"), manifest, StandardCharsets.UTF_8);
    }

    private static void recreateDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(directory);
    }
}
