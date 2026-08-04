package org.jmixworkbench.discovery.persistence

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphCacheStoreTest {
    private val pluginVersion = "1.0.0-jvw-graph-1"

    private fun bundle() = GraphCacheBundle(
        inventoryJson = """{"stamps":[],"rootKeys":[],"sourceRootKeys":[]}""",
        contributionsJson = """{"a/b.kt":{"fingerprint":"abc","contribution":{"artifacts":[]}}}""",
        responseJson = """{"artifacts":[],"relationships":[],"diagnostics":[]}""",
        savedAtEpochMillis = 1_700_000_000_000L,
        pluginVersion = pluginVersion,
    )

    @Test
    fun `save then load round-trips the persisted bundle`() {
        val root = createTempDirectory("jvw-cache")
        val store = GraphCacheStore(root, pluginVersion)

        assertTrue(store.save(bundle()))
        val loaded = assertNotNull(store.load())
        assertEquals(bundle().inventoryJson, loaded.inventoryJson)
        assertEquals(bundle().contributionsJson, loaded.contributionsJson)
        assertEquals(bundle().responseJson, loaded.responseJson)
        assertEquals(bundle().savedAtEpochMillis, loaded.savedAtEpochMillis)
    }

    @Test
    fun `load returns null when the cache is missing`() {
        val root = createTempDirectory("jvw-cache")
        assertNull(GraphCacheStore(root, pluginVersion).load())
    }

    @Test
    fun `load rejects corrupt cache files instead of failing`() {
        val root = createTempDirectory("jvw-cache")
        val store = GraphCacheStore(root, pluginVersion)
        Files.createDirectories(store.cacheDirectory())
        store.cacheFile().writeText("{ not valid json !!!")
        assertNull(store.load())
    }

    @Test
    fun `load rejects caches written by another plugin version`() {
        val root = createTempDirectory("jvw-cache")
        assertTrue(GraphCacheStore(root, "0.9.0-old").save(bundle()))
        assertNull(GraphCacheStore(root, pluginVersion).load())
    }

    @Test
    fun `save overwrites an existing cache atomically enough to stay readable`() {
        val root = createTempDirectory("jvw-cache")
        val store = GraphCacheStore(root, pluginVersion)
        assertTrue(store.save(bundle()))
        val updated = bundle().copy(savedAtEpochMillis = 2_000_000_000_000L)
        assertTrue(store.save(updated))
        assertEquals(2_000_000_000_000L, assertNotNull(store.load()).savedAtEpochMillis)
    }

    @Test
    fun `clear removes the cache file`() {
        val root = createTempDirectory("jvw-cache")
        val store = GraphCacheStore(root, pluginVersion)
        assertTrue(store.save(bundle()))
        store.clear()
        assertNull(store.load())
        assertFalse(Files.exists(store.cacheFile()))
    }
}
