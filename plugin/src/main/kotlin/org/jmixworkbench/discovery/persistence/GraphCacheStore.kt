package org.jmixworkbench.discovery.persistence

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persists derived application-graph knowledge inside the target project so
 * indexing knowledge survives IDE restarts and only changed files need work.
 *
 * The store is deliberately platform-free (no VFS, no IntelliJ services): it
 * owns atomic file IO and envelope validation only. Payload interpretation is
 * left to the host service layer. Any corruption, schema mismatch, or IO
 * failure is reported as "no cache" so callers fall back to a full rebuild.
 */
class GraphCacheStore(
    private val projectRoot: Path,
    private val pluginVersion: String,
) {
    private val gson = Gson()

    fun cacheDirectory(): Path = projectRoot.resolve(DIR_NAME)

    fun cacheFile(): Path = cacheDirectory().resolve(FILE_NAME)

    /**
     * Loads the persisted bundle. Returns null when the cache is missing,
     * unreadable, corrupt, or written by another schema/plugin version.
     */
    fun load(): GraphCacheBundle? {
        val file = cacheFile()
        if (!Files.isRegularFile(file)) return null
        return try {
            val text = Files.readString(file, Charsets.UTF_8)
            val envelope = JsonParser.parseString(text).asJsonObject
            if (envelope.get("schemaVersion")?.asInt != SCHEMA_VERSION) return null
            if (envelope.get("pluginVersion")?.asString != pluginVersion) return null
            val inventoryJson = envelope.get("inventory")?.toString() ?: return null
            val contributionsJson = envelope.get("contributions")?.toString() ?: return null
            val responseJson = envelope.get("response")?.toString() ?: return null
            GraphCacheBundle(
                inventoryJson = inventoryJson,
                contributionsJson = contributionsJson,
                responseJson = responseJson,
                savedAtEpochMillis = envelope.get("savedAtEpochMillis")?.asLong ?: 0L,
                pluginVersion = pluginVersion,
            )
        } catch (cause: Exception) {
            null
        }
    }

    /**
     * Persists the bundle atomically (temp file + move). Returns false when the
     * write fails; callers must treat persistence as best-effort.
     */
    fun save(bundle: GraphCacheBundle): Boolean =
        try {
            val directory = cacheDirectory()
            Files.createDirectories(directory)
            val envelope = JsonObject()
            envelope.addProperty("schemaVersion", SCHEMA_VERSION)
            envelope.addProperty("pluginVersion", pluginVersion)
            envelope.addProperty("savedAtEpochMillis", bundle.savedAtEpochMillis)
            envelope.add("inventory", JsonParser.parseString(bundle.inventoryJson))
            envelope.add("contributions", JsonParser.parseString(bundle.contributionsJson))
            envelope.add("response", JsonParser.parseString(bundle.responseJson))
            val target = cacheFile()
            val temporary = directory.resolve("$FILE_NAME.tmp-${ProcessHandle.current().pid()}")
            Files.write(temporary, gson.toJson(envelope).toByteArray(Charsets.UTF_8))
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (atomicUnsupported: Exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (cause: Exception) {
            false
        }

    /** Removes the cache file if present; failures are ignored. */
    fun clear() {
        try {
            Files.deleteIfExists(cacheFile())
        } catch (ignored: Exception) {
        }
    }

    companion object {
        const val DIR_NAME = ".jmix-workbench"
        const val FILE_NAME = "graph-cache.json"
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Persisted graph knowledge payload. The JSON strings are opaque to the store;
 * the host service layer owns their schema.
 */
data class GraphCacheBundle(
    val inventoryJson: String,
    val contributionsJson: String,
    val responseJson: String,
    val savedAtEpochMillis: Long,
    val pluginVersion: String,
)
