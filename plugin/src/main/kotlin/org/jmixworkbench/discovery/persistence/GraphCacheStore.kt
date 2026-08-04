package org.jmixworkbench.discovery.persistence

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Persists derived application-graph knowledge inside the target project so
 * indexing knowledge survives IDE restarts and only changed files need work.
 *
 * The store is deliberately platform-free (no VFS, no IntelliJ services): it
 * owns atomic file IO, envelope validation, and streaming JSON plumbing only.
 * Payload interpretation is left to the host service layer. Any corruption,
 * schema mismatch, or IO failure is reported as "no cache" so callers fall
 * back to a full rebuild.
 *
 * Both [save] and [load] stream through a gzip-compressed file: graph
 * payloads carry source-derived text and are too large to materialize as one
 * in-memory string or to keep uncompressed in the user's repository.
 */
class GraphCacheStore(
    private val projectRoot: Path,
    private val pluginVersion: String,
) {
    fun cacheDirectory(): Path = projectRoot.resolve(DIR_NAME)

    fun cacheFile(): Path = cacheDirectory().resolve(FILE_NAME)

    /**
     * Streams a cache body to disk atomically (temp file + move). The envelope
     * (schema version, plugin version, timestamp) is written by the store; the
     * caller writes its payload sections on the positioned writer. Returns
     * false when the write fails; callers must treat persistence as
     * best-effort.
     */
    fun save(body: (JsonWriter) -> Unit): Boolean {
        val directory = try {
            Files.createDirectories(cacheDirectory())
        } catch (cause: Exception) {
            return false
        }
        ensureCacheIsIgnoredByGit(directory)
        val temporary = directory.resolve("$FILE_NAME.tmp-${ProcessHandle.current().pid()}")
        return try {
            GZIPOutputStream(Files.newOutputStream(temporary)).bufferedWriter(Charsets.UTF_8).use { fileWriter ->
                JsonWriter(fileWriter).use { json ->
                    json.beginObject()
                    json.name("schemaVersion").value(SCHEMA_VERSION)
                    json.name("pluginVersion").value(pluginVersion)
                    json.name("savedAtEpochMillis").value(System.currentTimeMillis())
                    body(json)
                    json.endObject()
                }
            }
            try {
                Files.move(temporary, cacheFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (atomicUnsupported: Exception) {
                Files.move(temporary, cacheFile(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (cause: Exception) {
            runCatching { Files.deleteIfExists(temporary) }
            false
        }
    }

    /**
     * Streams a cache body back from disk. The store validates schema and
     * plugin version first, then offers each payload section to [section] by
     * name with the reader positioned at the value. The callback must consume
     * the value when it recognizes the name and return true; returning false
     * makes the store skip the value. Returns false when the cache is
     * missing, unreadable, corrupt, or foreign-versioned.
     */
    fun load(section: (name: String, reader: JsonReader) -> Boolean): Boolean {
        val file = cacheFile()
        if (!Files.isRegularFile(file)) return false
        return try {
            GZIPInputStream(Files.newInputStream(file)).bufferedReader(Charsets.UTF_8).use { fileReader ->
                JsonReader(fileReader).use { json ->
                    if (json.peek() != JsonToken.BEGIN_OBJECT) return false
                    json.beginObject()
                    var schemaOk = false
                    var versionOk = false
                    while (json.hasNext()) {
                        val name = json.nextName()
                        when (name) {
                            "schemaVersion" -> schemaOk = runCatching { json.nextInt() }.getOrDefault(-1) == SCHEMA_VERSION
                            "pluginVersion" -> versionOk = runCatching { json.nextString() }.getOrNull() == pluginVersion
                            else -> {
                                if (schemaOk && versionOk && section(name, json)) {
                                    // consumed by the callback
                                } else {
                                    json.skipValue()
                                }
                            }
                        }
                        if (!schemaOk || !versionOk) {
                            // Envelope headers precede payload sections; a mismatch
                            // means the remainder is irrelevant.
                            if (name == "schemaVersion" && !schemaOk) return false
                            if (name == "pluginVersion" && !versionOk) return false
                        }
                    }
                    json.endObject()
                    schemaOk && versionOk
                }
            }
        } catch (cause: Exception) {
            false
        }
    }

    /** Removes the cache file if present; failures are ignored. */
    fun clear() {
        try {
            Files.deleteIfExists(cacheFile())
        } catch (ignored: Exception) {
        }
    }

    /**
     * Keeps the knowledge cache out of version control without touching any
     * user-owned ignore file: a nested `.gitignore` inside the cache directory
     * is honored by git for everything beneath it.
     */
    private fun ensureCacheIsIgnoredByGit(directory: Path) {
        try {
            val ignoreFile = directory.resolve(".gitignore")
            if (!Files.isRegularFile(ignoreFile)) {
                Files.write(ignoreFile, "*\n".toByteArray(Charsets.UTF_8))
            }
        } catch (ignored: Exception) {
        }
    }

    companion object {
        const val DIR_NAME = ".jmix-workbench"
        const val FILE_NAME = "graph-cache.json.gz"
        const val SCHEMA_VERSION = 2
    }
}
