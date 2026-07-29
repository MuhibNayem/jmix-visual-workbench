package org.jmixworkbench.toolwindow

import org.cef.callback.CefCallback
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackagedWorkbenchResourceHandlerTest {
    private val resources = mapOf(
        "/webui/index.html" to "<html>workbench</html>".encodeToByteArray(),
        "/webui/assets/workbench.js" to "console.log('workbench')".encodeToByteArray(),
        "/webui/assets/workbench.css" to "body { color: black; }".encodeToByteArray(),
        "/webui/assets/unknown.bin" to byteArrayOf(1, 2, 3),
    )

    private fun provider(
        maximumResourceBytes: Int = 1024,
    ) = PackagedWorkbenchResourceProvider(
        resourceLookup = resources::get,
        maximumResourceBytes = maximumResourceBytes,
    )

    @Test
    fun `serves index javascript and css only from the private origin`() {
        val provider = provider()

        val index = provider.respond("GET", PACKAGED_WORKBENCH_ORIGIN)
        val explicitIndex = provider.respond("GET", "$PACKAGED_WORKBENCH_ORIGIN/index.html")
        val flowUiEditor = provider.respond("GET", PACKAGED_FLOW_UI_EDITOR_ENTRY_URL)
        val javascript = provider.respond("GET", "$PACKAGED_WORKBENCH_ORIGIN/assets/workbench.js")
        val css = provider.respond("GET", "$PACKAGED_WORKBENCH_ORIGIN/assets/workbench.css")

        assertEquals(200, index.status)
        assertContentEquals(resources.getValue("/webui/index.html"), index.body)
        assertContentEquals(index.body, explicitIndex.body)
        assertContentEquals(index.body, flowUiEditor.body)
        assertEquals("text/html; charset=utf-8", index.mimeType)
        assertEquals("text/html; charset=utf-8", index.headers.getValue("Content-Type"))
        assertEquals("text/javascript; charset=utf-8", javascript.mimeType)
        assertEquals("text/css; charset=utf-8", css.mimeType)
    }

    @Test
    fun `head returns metadata without a response body`() {
        val response = provider().respond("HEAD", "$PACKAGED_WORKBENCH_ORIGIN/assets/workbench.js")

        assertEquals(200, response.status)
        assertTrue(response.body.isEmpty())
        assertEquals(
            resources.getValue("/webui/assets/workbench.js").size.toString(),
            response.headers.getValue("Content-Length"),
        )
    }

    @Test
    fun `rejects off-origin and ambiguous private-origin URLs`() {
        listOf(
            "http://jmix-workbench.invalid/index.html",
            "https://example.invalid/index.html",
            "https://jmix-workbench.invalid:443/index.html",
            "https://jmix-workbench.invalid:444/index.html",
            "https://user@jmix-workbench.invalid/index.html",
            "$PACKAGED_WORKBENCH_ORIGIN/index.html?asset=other",
            "$PACKAGED_WORKBENCH_ORIGIN/index.html#fragment",
        ).forEach { url ->
            assertEquals(404, provider().respond("GET", url).status, url)
        }
    }

    @Test
    fun `rejects traversal separators controls and filesystem forms`() {
        listOf(
            "/../index.html",
            "/./index.html",
            "/assets/../index.html",
            "/assets//workbench.js",
            "/assets\\workbench.js",
            "/%2e%2e/index.html",
            "/assets/%2E%2E/index.html",
            "/assets%2fworkbench.js",
            "/assets%5cworkbench.js",
            "/C:/Windows/win.ini",
            "/%00index.html",
            "/index.html%0a",
        ).forEach { path ->
            assertEquals(404, provider().respond("GET", PACKAGED_WORKBENCH_ORIGIN + path).status, path)
        }
    }

    @Test
    fun `returns deterministic not found responses for missing unknown and oversized resources`() {
        val missing = provider().respond("GET", "$PACKAGED_WORKBENCH_ORIGIN/assets/missing.js")
        val unknown = provider().respond("GET", "$PACKAGED_WORKBENCH_ORIGIN/assets/unknown.bin")
        val oversized = provider(maximumResourceBytes = 4)
            .respond("GET", "$PACKAGED_WORKBENCH_ORIGIN/index.html")

        listOf(missing, unknown, oversized).forEach { response ->
            assertEquals(404, response.status)
            assertEquals("0", response.headers.getValue("Content-Length"))
            assertTrue(response.body.isEmpty())
        }
    }

    @Test
    fun `allows only get and head with defensive headers`() {
        val unsupported = provider().respond("POST", PACKAGED_WORKBENCH_ENTRY_URL)
        val lowercase = provider().respond("get", PACKAGED_WORKBENCH_ENTRY_URL)
        val success = provider().respond("GET", PACKAGED_WORKBENCH_ENTRY_URL)

        assertEquals(405, unsupported.status)
        assertEquals(405, lowercase.status)
        assertEquals("GET, HEAD", unsupported.headers.getValue("Allow"))
        listOf(unsupported, success).forEach { response ->
            assertEquals("no-store", response.headers.getValue("Cache-Control"))
            assertEquals("nosniff", response.headers.getValue("X-Content-Type-Options"))
            assertEquals("no-referrer", response.headers.getValue("Referrer-Policy"))
            assertEquals("DENY", response.headers.getValue("X-Frame-Options"))
            val policy = response.headers.getValue("Content-Security-Policy")
            assertTrue(policy.contains("default-src 'none'"))
            assertTrue(policy.contains("img-src 'self' data:"))
            assertTrue(policy.contains("form-action 'none'"))
            assertTrue(policy.contains("frame-ancestors 'none'"))
            assertTrue(policy.contains("object-src 'none'"))
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `cef adapter supports the callback generation declared by the running host`() {
        val provider = provider()
        val legacyHandler = createPackagedWorkbenchCefResourceHandler(
            provider,
            "GET",
            PACKAGED_WORKBENCH_ENTRY_URL,
        )
        var continued = false
        val callback = object : CefCallback {
            override fun Continue() {
                continued = true
            }

            override fun cancel() = Unit
        }

        assertTrue(legacyHandler.processRequest(null, callback))
        assertTrue(continued)
        val legacyLength = IntRef()
        legacyHandler.getResponseHeaders(null, legacyLength, null)
        assertEquals(resources.getValue("/webui/index.html").size, legacyLength.get())
        val legacyBytes = ByteArray(8)
        val legacyBytesRead = IntRef()
        assertTrue(legacyHandler.readResponse(legacyBytes, legacyBytes.size, legacyBytesRead, null))
        assertTrue(legacyBytesRead.get() > 0)

        val modernOpen = legacyHandler.javaClass.methods.singleOrNull { it.name == "open" }
        val modernRead = legacyHandler.javaClass.methods.singleOrNull { it.name == "read" }
        if (modernOpen != null && modernRead != null) {
            val modernHandler = createPackagedWorkbenchCefResourceHandler(
                provider,
                "GET",
                PACKAGED_WORKBENCH_ENTRY_URL,
            )
            val handleRequest = BoolRef()
            assertEquals(true, modernOpen.invoke(modernHandler, null, handleRequest, null))
            assertTrue(handleRequest.get())
            val modernBytes = ByteArray(8)
            val modernBytesRead = IntRef()
            assertEquals(
                true,
                modernRead.invoke(
                    modernHandler,
                    modernBytes,
                    modernBytes.size,
                    modernBytesRead,
                    null,
                ),
            )
            assertTrue(modernBytesRead.get() > 0)
        }
    }
}
