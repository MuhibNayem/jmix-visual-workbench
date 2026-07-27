package org.jmixworkbench.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.VerificationTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class VerifyWebBundleTask extends DefaultTask implements VerificationTask {
    private static final Pattern DIGEST_PATTERN =
            Pattern.compile("\"inputSha256\"\\s*:\\s*\"([0-9a-f]{64})\"");
    private static final Pattern RESOURCE_PATTERN =
            Pattern.compile("(?:src|href)=\"(?:\\./)?([^\"#?]+)");

    @Internal
    public abstract DirectoryProperty getInputRoot();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getDeclaredInputs();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getBundleDirectory();

    @TaskAction
    public void verify() throws IOException {
        Path bundleDirectory = getBundleDirectory().get().getAsFile().toPath();
        Path index = bundleDirectory.resolve("index.html");
        Path manifest = bundleDirectory.resolve("build-info.json");
        if (!Files.isRegularFile(index)) {
            throw new IllegalStateException("Generated web bundle is missing index.html");
        }
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("Generated web bundle is missing build-info.json");
        }

        String manifestText = Files.readString(manifest, StandardCharsets.UTF_8);
        Matcher digestMatcher = DIGEST_PATTERN.matcher(manifestText);
        if (!digestMatcher.find()) {
            throw new IllegalStateException("build-info.json has no valid inputSha256");
        }
        String currentDigest = WebBundleFingerprint.digest(
                getInputRoot().get().getAsFile().toPath(),
                getDeclaredInputs().getFiles()
        );
        if (!currentDigest.equals(digestMatcher.group(1))) {
            throw new IllegalStateException(
                    "Generated web bundle is stale: manifest digest " + digestMatcher.group(1)
                            + " does not match current inputs " + currentDigest
            );
        }

        String indexText = Files.readString(index, StandardCharsets.UTF_8);
        Matcher resourceMatcher = RESOURCE_PATTERN.matcher(indexText);
        while (resourceMatcher.find()) {
            String resource = resourceMatcher.group(1);
            if (resource.contains("://") || resource.startsWith("data:")) {
                continue;
            }
            if (!Files.isRegularFile(bundleDirectory.resolve(resource).normalize())) {
                throw new IllegalStateException("Generated web bundle references missing resource: " + resource);
            }
        }
    }

    @Override
    public boolean getIgnoreFailures() {
        return false;
    }

    @Override
    public void setIgnoreFailures(boolean ignoreFailures) {
        if (ignoreFailures) {
            throw new IllegalArgumentException("Web bundle verification cannot ignore failures");
        }
    }
}
