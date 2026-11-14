package com.openminis.app.mikova.selfhost

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SelfHostProbeTest {
    @Test fun normalizesAndRejectsUnsupportedAddresses() {
        assertEquals("https://example.com/", EndpointPolicy.normalize(" example.com "))
        assertEquals("http://127.0.0.1:8080/health", EndpointPolicy.normalize("http://127.0.0.1:8080/health"))
        for (url in listOf("", "ftp://example.com", "https://user:pass@example.com", "https://example.com/?key=secret", "https://example.com/#secret", "https://example.com:70000", "https://example.com/a b")) {
            assertNotNull(url, EndpointPolicy.error(url))
        }
        assertNotNull(EndpointPolicy.error("http://127.0.0.1/", credential = true))
        assertNull(EndpointPolicy.error("https://example.com/", credential = true))
    }

    @Test fun actualHttpSuccessNeverClaimsRemoteExecution() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("unused body"))
            server.start()
            val result = SelfHostProbe.test(server.url("/health").toString())
            assertTrue(result.message, result.ok)
            assertTrue(result.message.contains("仅证明"))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/health", request.path)
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun unauthorizedResponseDoesNotEchoBodyOrKey() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private-upstream-secret"))
            server.start()
            val result = SelfHostProbe.test(server.url("/").toString())
            assertFalse(result.ok)
            assertTrue(result.message.contains("401"))
            assertFalse(result.message.contains("private-upstream-secret"))
        }
    }

    @Test fun redirectsAreNotFollowedAndInsecureKeysNeverSent() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/private"))
            server.start()
            val result = SelfHostProbe.test(server.url("/").toString())
            assertFalse(result.ok)
            assertEquals(1, server.requestCount)
            assertTrue(result.message.contains("重定向"))
            assertFalse(SelfHostProbe.test(server.url("/").toString(), "secret").ok)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun timeoutIsRecoverableAndDoesNotExposeExceptionContents() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(300, TimeUnit.MILLISECONDS).setResponseCode(200))
            server.start()
            val result = SelfHostProbe.test(server.url("/").toString(), timeoutMs = 50)
            assertFalse(result.ok)
            assertTrue(result.message.contains("超时"))
        }
    }
}
