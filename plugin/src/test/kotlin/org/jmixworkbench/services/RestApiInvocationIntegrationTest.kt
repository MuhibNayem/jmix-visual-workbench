package org.jmixworkbench.services

import com.intellij.testFramework.HeavyPlatformTestCase
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestApiInvocationIntegrationTest : HeavyPlatformTestCase() {
    fun testRejectsNonLoopbackTargetBeforeNetworkAccess() {
        val response = RestApiWorkspaceService(project).invoke(
            RestApiInvocationRequest(
                baseUrl = "https://example.com",
                path = "/rest/entities/User",
                method = "GET",
            ),
        )

        assertFalse(response.accepted)
        assertEquals("JVW-REST-INVOKE-TARGET-REJECTED", response.errorCode)
    }

    fun testInvokesBoundedLoopbackApiAndCapturesResponse() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responder = thread(name = "rest-api-workbench-fixture") {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (reader.readLine()?.isNotEmpty() == true) {
                    // Consume the bounded HTTP request headers.
                }
                val body = """{"status":"ok"}"""
                socket.getOutputStream().bufferedWriter().use { writer ->
                    writer.write("HTTP/1.1 200 OK\r\n")
                    writer.write("Content-Type: application/json\r\n")
                    writer.write("Content-Length: ${body.toByteArray().size}\r\n")
                    writer.write("Connection: close\r\n\r\n")
                    writer.write(body)
                    writer.flush()
                }
            }
        }
        try {
            val response = RestApiWorkspaceService(project).invoke(
                RestApiInvocationRequest(
                    baseUrl = "http://127.0.0.1:${server.localPort}",
                    path = "/rest/health",
                    method = "GET",
                    timeoutMillis = 5_000,
                ),
            )

            assertTrue(response.accepted, "${response.errorCode}: ${response.message}")
            assertEquals(200, response.status)
            assertEquals("""{"status":"ok"}""", response.body)
            assertFalse(response.truncated)
        } finally {
            server.close()
            responder.join(5_000)
        }
    }
}
