package org.jmixworkbench.toolwindow

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceHandler
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.misc.IntRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.URI

internal const val PACKAGED_WORKBENCH_ORIGIN = "https://jmix-workbench.invalid"
internal const val PACKAGED_WORKBENCH_ENTRY_URL = "$PACKAGED_WORKBENCH_ORIGIN/index.html"
internal const val PACKAGED_FLOW_UI_EDITOR_ENTRY_URL = "$PACKAGED_WORKBENCH_ORIGIN/flowui-editor.html"

private const val DEFAULT_MAXIMUM_RESOURCE_BYTES = 32 * 1024 * 1024
private const val CONTENT_SECURITY_POLICY =
    "default-src 'none'; " +
        "script-src 'self'; " +
        "style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data:; " +
        "font-src 'self'; " +
        "connect-src 'none'; " +
        "media-src 'self'; " +
        "worker-src 'self'; " +
        "manifest-src 'self'; " +
        "object-src 'none'; " +
        "base-uri 'none'; " +
        "form-action 'none'; " +
        "frame-ancestors 'none'"

internal data class PackagedWorkbenchResponse(
    val status: Int,
    val statusText: String,
    val mimeType: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

internal fun isPackagedWorkbenchOriginUrl(url: String): Boolean =
    parsePackagedWorkbenchUri(url) != null

private fun parsePackagedWorkbenchUri(url: String): URI? {
    if (url.isBlank() || url.any { it.code < 0x20 || it.code == 0x7f }) {
        return null
    }
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    return uri.takeIf {
        it.scheme == "https" &&
            it.rawAuthority == "jmix-workbench.invalid" &&
            it.host == "jmix-workbench.invalid" &&
            it.port == -1 &&
            it.userInfo == null &&
            it.rawQuery == null &&
            it.rawFragment == null
    }
}

/**
 * Pure policy and classpath-resource boundary for the privileged packaged UI.
 *
 * Chromium never sees a JVM classloader URL. It sees only a reserved synthetic
 * HTTPS origin, while this provider maps a deliberately small URL vocabulary to
 * immutable resources under `/webui/`.
 */
internal class PackagedWorkbenchResourceProvider(
    private val resourceLookup: (String) -> ByteArray?,
    private val maximumResourceBytes: Int = DEFAULT_MAXIMUM_RESOURCE_BYTES,
) {
    init {
        require(maximumResourceBytes > 0) {
            "maximumResourceBytes must be positive"
        }
    }

    fun isPrivateOriginUrl(url: String): Boolean = resolveResourcePath(url) != null

    fun respond(method: String, url: String): PackagedWorkbenchResponse {
        val resourcePath = resolveResourcePath(url) ?: return notFound()
        if (method != "GET" && method != "HEAD") {
            return response(
                status = 405,
                statusText = "Method Not Allowed",
                mimeType = "text/plain; charset=utf-8",
                body = byteArrayOf(),
                extraHeaders = mapOf("Allow" to "GET, HEAD"),
            )
        }

        val mimeType = mimeType(resourcePath) ?: return notFound()
        val resource = runCatching { resourceLookup(resourcePath) }.getOrNull()
            ?: return notFound()
        if (resource.size > maximumResourceBytes) {
            return notFound()
        }

        return response(
            status = 200,
            statusText = "OK",
            mimeType = mimeType,
            body = if (method == "HEAD") byteArrayOf() else resource,
            contentLength = resource.size,
        )
    }

    private fun resolveResourcePath(url: String): String? {
        val uri = parsePackagedWorkbenchUri(url) ?: return null

        val rawPath = uri.rawPath.orEmpty().ifEmpty { "/" }
        if (
            !rawPath.startsWith("/") ||
            rawPath.contains('%') ||
            rawPath.contains('\\') ||
            rawPath.contains(':') ||
            rawPath.contains("//")
        ) {
            return null
        }
        val segments = rawPath.removePrefix("/").split('/')
        if (segments.any { it == "." || it == ".." || it.isEmpty() && rawPath != "/" }) {
            return null
        }
        if (rawPath.any { it.code < 0x20 || it.code == 0x7f }) {
            return null
        }

        val relativePath = when (rawPath) {
            "/", "/index.html" -> "index.html"
            "/flowui-editor.html" -> "index.html"
            else -> rawPath.removePrefix("/")
        }
        return "/webui/$relativePath"
    }

    private fun mimeType(resourcePath: String): String? = when (resourcePath.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "wasm" -> "application/wasm"
        else -> null
    }

    private fun notFound(): PackagedWorkbenchResponse = response(
        status = 404,
        statusText = "Not Found",
        mimeType = "text/plain; charset=utf-8",
        body = byteArrayOf(),
    )

    private fun response(
        status: Int,
        statusText: String,
        mimeType: String,
        body: ByteArray,
        contentLength: Int = body.size,
        extraHeaders: Map<String, String> = emptyMap(),
    ): PackagedWorkbenchResponse {
        val headers = linkedMapOf(
            "Cache-Control" to "no-store",
            "Content-Length" to contentLength.toString(),
            "Content-Type" to mimeType,
            "Content-Security-Policy" to CONTENT_SECURITY_POLICY,
            "Cross-Origin-Opener-Policy" to "same-origin",
            "Cross-Origin-Resource-Policy" to "same-origin",
            "Referrer-Policy" to "no-referrer",
            "X-Content-Type-Options" to "nosniff",
            "X-Frame-Options" to "DENY",
        )
        headers.putAll(extraHeaders)
        return PackagedWorkbenchResponse(
            status = status,
            statusText = statusText,
            mimeType = mimeType,
            headers = headers,
            body = body,
        )
    }
}

/**
 * JCEF adapter for [PackagedWorkbenchResourceProvider].
 *
 * Every request receives a resource handler and disables default handling, so an
 * unexpected URL can never fall through to DNS, the filesystem, or the network.
 */
internal class PackagedWorkbenchRequestHandler(
    private val provider: PackagedWorkbenchResourceProvider,
) : CefRequestHandlerAdapter() {
    private val resourceRequestHandler = object : CefResourceRequestHandlerAdapter() {
        override fun getResourceHandler(
            browser: CefBrowser?,
            frame: CefFrame?,
            request: CefRequest?,
        ): CefResourceHandler = createPackagedWorkbenchCefResourceHandler(
            provider = provider,
            method = request?.method.orEmpty(),
            url = request?.url.orEmpty(),
        )
    }

    override fun onBeforeBrowse(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest?,
        userGesture: Boolean,
        isRedirect: Boolean,
    ): Boolean = request == null || !provider.isPrivateOriginUrl(request.url.orEmpty())

    override fun onOpenURLFromTab(
        browser: CefBrowser?,
        frame: CefFrame?,
        targetUrl: String?,
        userGesture: Boolean,
    ): Boolean = true

    override fun getResourceRequestHandler(
        browser: CefBrowser?,
        frame: CefFrame?,
        request: CefRequest?,
        isNavigation: Boolean,
        isDownload: Boolean,
        requestInitiator: String?,
        disableDefaultHandling: BoolRef?,
    ): CefResourceRequestHandler {
        disableDefaultHandling?.set(true)
        return resourceRequestHandler
    }
}

/**
 * Creates a handler that supports both JCEF callback generations without linking
 * older hosts to callback classes introduced after IDEA 2025.3.
 *
 * IDEA 2025.3 calls `processRequest/readResponse`; IDEA 2026.2 may call
 * `open/read/skip`. A runtime proxy implements whichever methods the host's
 * `CefResourceHandler` interface actually declares.
 */
internal fun createPackagedWorkbenchCefResourceHandler(
    provider: PackagedWorkbenchResourceProvider,
    method: String,
    url: String,
): CefResourceHandler {
    val handler = PackagedWorkbenchCefResourceInvocationHandler(
        response = provider.respond(method, url),
    )
    return Proxy.newProxyInstance(
        CefResourceHandler::class.java.classLoader,
        arrayOf(CefResourceHandler::class.java),
        handler,
    ) as CefResourceHandler
}

private class PackagedWorkbenchCefResourceInvocationHandler(
    private val response: PackagedWorkbenchResponse,
) : InvocationHandler {
    private var offset = 0

    override fun invoke(proxy: Any, method: Method, arguments: Array<out Any?>?): Any? = when (method.name) {
        "processRequest" -> {
            (arguments?.getOrNull(1) as? CefCallback)?.Continue()
            true
        }
        "open" -> {
            (arguments?.getOrNull(1) as? BoolRef)?.set(true)
            true
        }
        "getResponseHeaders" -> {
            writeResponseHeaders(
                cefResponse = arguments?.getOrNull(0) as? CefResponse,
                responseLength = arguments?.getOrNull(1) as? IntRef,
            )
            null
        }
        "readResponse", "read" -> read(
            dataOut = arguments?.getOrNull(0) as? ByteArray,
            bytesToRead = arguments?.getOrNull(1) as? Int ?: 0,
            bytesRead = arguments?.getOrNull(2) as? IntRef,
        )
        "skip" -> false
        "cancel" -> {
            offset = response.body.size
            null
        }
        "toString" -> "PackagedWorkbenchCefResourceHandler(${response.status})"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === arguments?.firstOrNull()
        else -> defaultValue(method.returnType)
    }

    private fun writeResponseHeaders(
        cefResponse: CefResponse?,
        responseLength: IntRef?,
    ) {
        cefResponse?.status = response.status
        cefResponse?.statusText = response.statusText
        cefResponse?.mimeType = response.mimeType.substringBefore(';')
        cefResponse?.setHeaderMap(response.headers.toMutableMap())
        responseLength?.set(response.body.size)
    }

    private fun read(
        dataOut: ByteArray?,
        bytesToRead: Int,
        bytesRead: IntRef?,
    ): Boolean {
        if (dataOut == null || bytesToRead <= 0 || offset >= response.body.size) {
            bytesRead?.set(0)
            return false
        }
        val count = minOf(bytesToRead, dataOut.size, response.body.size - offset)
        response.body.copyInto(
            destination = dataOut,
            destinationOffset = 0,
            startIndex = offset,
            endIndex = offset + count,
        )
        offset += count
        bytesRead?.set(count)
        return true
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0.0f
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        else -> null
    }
}
