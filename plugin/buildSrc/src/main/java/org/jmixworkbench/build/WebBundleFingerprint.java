package org.jmixworkbench.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;

final class WebBundleFingerprint {
    private WebBundleFingerprint() {
    }

    static String digest(Path inputRoot, Iterable<File> inputFiles) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<File> files = java.util.stream.StreamSupport.stream(inputFiles.spliterator(), false)
                    .filter(File::isFile)
                    .sorted(Comparator.comparing(file -> relativePath(inputRoot, file)))
                    .toList();

            for (File file : files) {
                digest.update(relativePath(inputRoot, file).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file.toPath()));
                digest.update((byte) 0);
            }

            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static List<String> relativePaths(Path inputRoot, Iterable<File> inputFiles) {
        return java.util.stream.StreamSupport.stream(inputFiles.spliterator(), false)
                .filter(File::isFile)
                .map(file -> relativePath(inputRoot, file))
                .sorted()
                .toList();
    }

    private static String relativePath(Path inputRoot, File file) {
        return inputRoot.toAbsolutePath().normalize()
                .relativize(file.toPath().toAbsolutePath().normalize())
                .toString()
                .replace(File.separatorChar, '/');
    }

    static String jsonEscape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
