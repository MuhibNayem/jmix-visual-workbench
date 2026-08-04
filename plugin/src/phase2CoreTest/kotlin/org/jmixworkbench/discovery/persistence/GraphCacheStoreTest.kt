package org.jmixworkbench.discovery.persistence

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphCacheStoreTest {
    private val pluginVersion = "1.0.0-jvw-graph-1"

    private fun GraphCacheStore.writeSample(files: Int = 3): Boolean =
        save { json ->
            json.name("inventory")
            json.beginObject()
            json.name("fileCount").value(files)
            json.endObject()
            json.name("contributions")
            json.beginObject()
            repeat(files) { index ->
                json.name("src/File$index.kt")
                json.beginObject()
                json.name("fingerprint").value("fp-$index")
                json.endObject()
            }
            json.endObject()
        }

    private class Loaded {
        var fileCount: Int = -1
        val contributions = linkedMapOf<String, String>()
    }

    private fun GraphCacheStore.readSample(): Loaded? {
        val loaded = Loaded()
        val ok = load { name, reader ->
            when (name) {
                "inventory" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "fileCount") loaded.fileCount = reader.nextInt()
                        else reader.skipValue()
                    }
                    reader.endObject()
                    true
                }
                "contributions" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val path = reader.nextName()
                        reader.beginObject()
                        var fingerprint = ""
                        while (reader.hasNext()) {
                            if (reader.nextName() == "fingerprint") fingerprint = reader.nextString()
                            else reader.skipValue()
                        }
                        reader.endObject()
                        loaded.contributions[path] = fingerprint
                    }
                    reader.endObject()
                    true
                }
                else -> false
            }
        }
        return if (ok) loaded else null
    }

    @Test
    fun `save then load round-trips streamed sections`() {
        val store = GraphCacheStore(createTempDirectory("jvw-cache"), pluginVersion)
        assertTrue(store.writeSample())
        val loaded = store.readSample()
        assertEquals(3, loaded?.fileCount)
        assertEquals(3, loaded?.contributions?.size)
        assertEquals("fp-1", loaded?.contributions?.get("src/File1.kt"))
    }

    @Test
    fun `load fails when the cache is missing`() {
        val store = GraphCacheStore(createTempDirectory("jvw-cache"), pluginVersion)
        assertEquals(null, store.readSample())
    }

    @Test
    fun `load rejects corrupt cache files instead of failing`() {
        val store = GraphCacheStore(createTempDirectory("jvw-cache"), pluginVersion)
        Files.createDirectories(store.cacheDirectory())
        store.cacheFile().writeText("{ not valid json !!!")
        assertEquals(null, store.readSample())
    }

    @Test
    fun `load rejects caches written by another plugin version`() {
        val root = createTempDirectory("jvw-cache")
        assertTrue(GraphCacheStore(root, "0.9.0-old").writeSample())
        assertEquals(null, GraphCacheStore(root, pluginVersion).readSample())
    }

    @Test
    fun `save overwrites an existing cache and stays readable`() {
        val store = GraphCacheStore(createTempDirectory("jvw-cache"), pluginVersion)
        assertTrue(store.writeSample(files = 2))
        assertTrue(store.writeSample(files = 5))
        assertEquals(5, store.readSample()?.fileCount)
    }

    @Test
    fun `clear removes the cache file`() {
        val store = GraphCacheStore(createTempDirectory("jvw-cache"), pluginVersion)
        assertTrue(store.writeSample())
        store.clear()
        assertFalse(Files.exists(store.cacheFile()))
        assertEquals(null, store.readSample())
    }
}
