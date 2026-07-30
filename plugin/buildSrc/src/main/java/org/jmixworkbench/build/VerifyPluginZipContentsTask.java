package org.jmixworkbench.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.VerificationTask;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;

public abstract class VerifyPluginZipContentsTask extends DefaultTask implements VerificationTask {
    private static final Pattern ASSET_PATTERN =
            Pattern.compile("(?:src|href)=\"(?:\\./)?([^\"#?]+)");
    private static final Pattern DIGEST_PATTERN =
            Pattern.compile("\"inputSha256\"\\s*:\\s*\"([0-9a-f]{64})\"");
    private static final Pattern HASHED_ASSET_PATTERN =
            Pattern.compile("assets/[^/]+-[A-Za-z0-9_-]{6,}\\.(?:js|css)");
    private static final List<String> FORBIDDEN_PATH_PARTS = List.of(
            "node_modules",
            "/.npm/",
            "/nodejs/",
            "/bin/node",
            "node.exe"
    );
    private static final List<String> FORBIDDEN_CONTENT = List.of(
            "com.jmixstudio",
            "Jmix Studio Clone",
            "/Users/",
            "\\Users\\"
    );
    private static final Map<String, Pattern> CREDENTIAL_PATTERNS = Map.of(
            "private key", Pattern.compile("-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----"),
            "AWS access key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            "GitHub token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"),
            "assigned credential", Pattern.compile(
                    "(?i)\\b(?:password|passwd|api[_-]?key|secret|token)\\s*[:=]\\s*[\"'][^\"'\\s]{8,}[\"']"
            )
    );
    private static final java.util.Set<String> CREDENTIAL_SCAN_ALLOWLIST = java.util.Set.of();
    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of(
            "css", "gradle", "html", "java", "js", "json", "kts", "md", "mf",
            "properties", "svg", "text", "txt", "xml", "yaml", "yml"
    );

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getArchives();

    @TaskAction
    public void verify() throws Exception {
        List<Path> archives = getArchives().getFiles().stream()
                .map(file -> file.toPath().toAbsolutePath().normalize())
                .sorted()
                .toList();
        if (archives.size() != 2) {
            throw new IllegalStateException("Expected exactly two host plugin ZIPs, found: " + archives);
        }

        Map<String, String> inputDigests = new LinkedHashMap<>();
        for (Path archive : archives) {
            if (!Files.isRegularFile(archive)) {
                throw new IllegalStateException("Plugin distribution is missing: " + archive);
            }
            String lane = archive.getFileName().toString().contains("idea253") ? "idea253"
                    : archive.getFileName().toString().contains("idea262") ? "idea262"
                    : null;
            if (lane == null) {
                throw new IllegalStateException("Plugin ZIP has no deterministic lane suffix: " + archive);
            }
            inputDigests.put(lane, inspectArchive(archive, lane));
            getLogger().lifecycle("{} {} SHA-256 {}", lane, archive, sha256(Files.readAllBytes(archive)));
        }

        if (!inputDigests.keySet().equals(java.util.Set.of("idea253", "idea262"))) {
            throw new IllegalStateException("Expected one idea253 and one idea262 ZIP: " + inputDigests.keySet());
        }
        if (inputDigests.values().stream().distinct().count() != 1) {
            throw new IllegalStateException("Host ZIPs contain different web input digests: " + inputDigests);
        }
        getLogger().lifecycle("Both host ZIPs contain web input SHA-256 {}", inputDigests.values().iterator().next());
    }

    static String inspectArchive(Path archive, String lane) throws Exception {
        List<Map<String, byte[]>> pluginJars = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                inspectPath(entry.getName(), archive.toString());
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] entryBytes = zip.getInputStream(entry).readAllBytes();
                if (entry.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    Map<String, byte[]> contents = readNestedArchive(
                            entryBytes,
                            archive + "!/" + entry.getName()
                    );
                    if (contents.containsKey("META-INF/plugin.xml")) {
                        pluginJars.add(contents);
                    }
                } else {
                    inspectContent(entry.getName(), entryBytes, archive.toString());
                }
            }
            if (pluginJars.isEmpty()) {
                throw new IllegalStateException(archive + " has no plugin JAR containing META-INF/plugin.xml");
            }
            if (pluginJars.size() != 1) {
                throw new IllegalStateException(
                        archive + " must contain exactly one main plugin JAR, found " + pluginJars.size()
                );
            }
            return inspectPluginJar(pluginJars.get(0), archive, lane);
        }
    }

    private static Map<String, byte[]> readNestedArchive(
            byte[] bytes,
            String displayName
    ) throws IOException {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                inspectPath(entry.getName(), displayName);
                if (!entry.isDirectory()) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    input.transferTo(output);
                    byte[] entryBytes = output.toByteArray();
                    inspectContent(entry.getName(), entryBytes, displayName);
                    contents.put(entry.getName(), entryBytes);
                }
            }
        }
        return contents;
    }

    private static String inspectPluginJar(Map<String, byte[]> contents, Path archive, String lane) {
        requireEntry(contents, "META-INF/plugin.xml", archive);
        requireEntry(contents, "webui/index.html", archive);
        requireEntry(contents, "webui/build-info.json", archive);
        requireEntry(contents, "icons/workbench.svg", archive);
        requireEntry(contents, "LICENSE", archive);
        requireEntry(contents, "NOTICE", archive);
        requireEntry(contents, "project-template/gradle/wrapper/gradle-wrapper.jar", archive);
        requireEntry(contents, "project-template/gradlew", archive);
        requireEntry(contents, "project-template/gradlew.bat", archive);
        requireEntry(
                contents,
                "org/jmixworkbench/project/JmixNewProjectWizard.class",
                archive
        );
        requireEntry(
                contents,
                "org/jmixworkbench/project/JmixProjectTemplateGenerator.class",
                archive
        );
        requireEntry(
                contents,
                "org/jmixworkbench/project/JmixProjectInstaller.class",
                archive
        );
        requireEntry(
                contents,
                "org/jmixworkbench/services/EntityEventListenerService.class",
                archive
        );
        requireEntry(
                contents,
                "org/jmixworkbench/actions/InjectJmixRepositoryAction.class",
                archive
        );
        requireEntry(
                contents,
                "org/jmixworkbench/services/RepositoryMethodRefactorService.class",
                archive
        );
        requireEntry(
                contents,
                "org/jmixworkbench/services/AggregateUpdateServiceChangeService.class",
                archive
        );
        requireEntry(
                contents,
                "org/jmixworkbench/generator/AggregateUpdateServiceGenerator.class",
                archive
        );

        String descriptor = text(contents.get("META-INF/plugin.xml"));
        requireContains(descriptor, "<id>org.jmixworkbench</id>", archive);
        requireContains(descriptor, "<name>Jmix Visual Workbench</name>", archive);
        requireContains(descriptor, "<depends>com.intellij.gradle</depends>", archive);
        requireExtensionRegistration(
                descriptor,
                "newProjectWizard.generator",
                "implementation",
                "org.jmixworkbench.project.JmixNewProjectWizard",
                archive
        );
        requireExtensionRegistration(
                descriptor,
                "fileEditorProvider",
                "implementation",
                "org.jmixworkbench.editor.JmixEntityFileEditorProvider",
                archive
        );
        requireExtensionRegistration(
                descriptor,
                "projectService",
                "serviceImplementation",
                "org.jmixworkbench.toolwindow.WorkbenchNavigationService",
                archive
        );
        requireExtensionRegistration(
                descriptor,
                "projectService",
                "serviceImplementation",
                "org.jmixworkbench.services.EntityEventListenerService",
                archive
        );
        requireExtensionRegistration(
                descriptor,
                "projectService",
                "serviceImplementation",
                "org.jmixworkbench.services.RepositoryMethodRefactorService",
                archive
        );
        requireExtensionRegistration(
                descriptor,
                "projectService",
                "serviceImplementation",
                "org.jmixworkbench.services.AggregateUpdateServiceChangeService",
                archive
        );
        requireActionRegistration(
                descriptor,
                "JmixWorkbench.InjectRepository",
                "org.jmixworkbench.actions.InjectJmixRepositoryAction",
                "GenerateGroup",
                archive
        );
        if ("idea253".equals(lane)) {
            requireContains(descriptor, "since-build=\"253\"", archive);
            requireContains(descriptor, "until-build=\"253.*\"", archive);
        } else {
            requireContains(descriptor, "since-build=\"262\"", archive);
            requireContains(descriptor, "until-build=\"262.*\"", archive);
            requireContains(descriptor, "<depends>com.intellij.modules.jcef</depends>", archive);
        }

        String index = text(contents.get("webui/index.html"));
        Matcher assets = ASSET_PATTERN.matcher(index);
        int referencedAssets = 0;
        while (assets.find()) {
            String relativePath = assets.group(1);
            if (relativePath.contains("://") || relativePath.startsWith("data:")) {
                continue;
            }
            requireEntry(contents, "webui/" + relativePath, archive);
            if (relativePath.startsWith("assets/")) {
                if (!HASHED_ASSET_PATTERN.matcher(relativePath).matches()) {
                    throw new IllegalStateException(
                            archive + " webui/index.html references an unhashed asset: " + relativePath
                    );
                }
                referencedAssets++;
            }
        }
        if (referencedAssets == 0) {
            throw new IllegalStateException(archive + " webui/index.html references no hashed assets");
        }

        String buildInfo = text(contents.get("webui/build-info.json"));
        Matcher digest = DIGEST_PATTERN.matcher(buildInfo);
        if (!digest.find()) {
            throw new IllegalStateException(archive + " build-info.json has no valid inputSha256");
        }
        return digest.group(1);
    }

    private static void requireExtensionRegistration(
            String descriptor,
            String elementName,
            String attributeName,
            String expectedValue,
            Path archive
    ) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            var document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(descriptor.getBytes(StandardCharsets.UTF_8))
            );
            var candidates = document.getElementsByTagName(elementName);
            for (int index = 0; index < candidates.getLength(); index++) {
                var candidate = candidates.item(index);
                var attribute = candidate.getAttributes().getNamedItem(attributeName);
                if (attribute != null && expectedValue.equals(attribute.getNodeValue())) {
                    return;
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    archive + " contains an unreadable META-INF/plugin.xml",
                    exception
            );
        }
        throw new IllegalStateException(
                archive + " descriptor is missing <" + elementName + " "
                        + attributeName + "=\"" + expectedValue + "\">"
        );
    }

    private static void requireActionRegistration(
            String descriptor,
            String actionId,
            String implementationClass,
            String groupId,
            Path archive
    ) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            var document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(descriptor.getBytes(StandardCharsets.UTF_8))
            );
            var actions = document.getElementsByTagName("action");
            for (int index = 0; index < actions.getLength(); index++) {
                var action = actions.item(index);
                var attributes = action.getAttributes();
                var id = attributes.getNamedItem("id");
                var implementation = attributes.getNamedItem("class");
                if (id == null
                        || implementation == null
                        || !actionId.equals(id.getNodeValue())
                        || !implementationClass.equals(implementation.getNodeValue())) {
                    continue;
                }
                var children = action.getChildNodes();
                for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
                    var child = children.item(childIndex);
                    if (!"add-to-group".equals(child.getNodeName()) || child.getAttributes() == null) {
                        continue;
                    }
                    var registeredGroup = child.getAttributes().getNamedItem("group-id");
                    if (registeredGroup != null && groupId.equals(registeredGroup.getNodeValue())) {
                        return;
                    }
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    archive + " contains an unreadable META-INF/plugin.xml",
                    exception
            );
        }
        throw new IllegalStateException(
                archive + " descriptor is missing action " + actionId
                        + " (" + implementationClass + ") in group " + groupId
        );
    }

    static void inspectPath(String entryName, String archive) {
        if (entryName.isBlank() || entryName.indexOf('\0') >= 0 || entryName.indexOf('\\') >= 0) {
            throw new IllegalStateException(archive + " contains an invalid archive entry path: " + entryName);
        }
        Path entryPath;
        try {
            entryPath = Paths.get(entryName);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(archive + " contains an invalid archive entry path: " + entryName, exception);
        }
        Path archiveRoot = Paths.get("/archive-root");
        Path candidate = archiveRoot.resolve(entryPath).normalize();
        boolean hasParentSegment = java.util.stream.StreamSupport.stream(entryPath.spliterator(), false)
                .anyMatch(segment -> segment.toString().equals(".."));
        if (entryPath.isAbsolute()
                || entryName.startsWith("/")
                || entryName.matches("^[A-Za-z]:.*")
                || hasParentSegment
                || !candidate.startsWith(archiveRoot)) {
            throw new IllegalStateException(archive + " contains a traversal-shaped archive entry: " + entryName);
        }

        String normalized = "/" + entryName.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".map")) {
            throw new IllegalStateException(archive + " contains an unapproved source map: " + entryName);
        }
        for (String forbidden : FORBIDDEN_PATH_PARTS) {
            if (normalized.contains(forbidden.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(archive + " contains forbidden Node/npm cache content: " + entryName);
            }
        }
    }

    static void inspectContent(String entryName, byte[] bytes, String archive) {
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String forbidden : FORBIDDEN_CONTENT) {
            if (content.contains(forbidden)) {
                throw new IllegalStateException(
                        archive + "!/" + entryName + " contains forbidden content: " + forbidden
                );
            }
        }
        if (isTextLike(entryName) && !CREDENTIAL_SCAN_ALLOWLIST.contains(entryName)) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            CREDENTIAL_PATTERNS.forEach((description, pattern) -> {
                if (pattern.matcher(text).find()) {
                    throw new IllegalStateException(
                            archive + "!/" + entryName + " contains a forbidden " + description
                    );
                }
            });
        }
    }

    private static boolean isTextLike(String entryName) {
        String fileName = Path.of(entryName).getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.equals("license") || fileName.equals("notice")) {
            return true;
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(fileName.substring(dot + 1));
    }

    private static void requireEntry(Map<String, byte[]> contents, String entry, Path archive) {
        if (!contents.containsKey(entry)) {
            throw new IllegalStateException(archive + " plugin JAR is missing " + entry);
        }
    }

    private static void requireContains(String value, String expected, Path archive) {
        if (!value.contains(expected)) {
            throw new IllegalStateException(archive + " descriptor is missing " + expected);
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Override
    public boolean getIgnoreFailures() {
        return false;
    }

    @Override
    public void setIgnoreFailures(boolean ignoreFailures) {
        if (ignoreFailures) {
            throw new IllegalArgumentException("Plugin ZIP verification cannot ignore failures");
        }
    }
}
