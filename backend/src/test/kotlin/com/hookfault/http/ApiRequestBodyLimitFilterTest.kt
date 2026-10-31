package com.hookfault.http

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.nio.charset.StandardCharsets.UTF_8

class ApiRequestBodyLimitFilterTest {
    private val filter = ApiRequestBodyLimitFilter()

    @Test
    fun preservesAnExactLimitBodyForTheController() {
        val bytes = "x".repeat(MAX_JSON_REQUEST_BYTES).toByteArray(UTF_8)
        val request = request(bytes)
        val response = MockHttpServletResponse()
        var calls = 0
        filter.doFilter(request, response, FilterChain { wrapped, _ ->
            calls++
            assertThat(wrapped.contentLengthLong).isEqualTo(MAX_JSON_REQUEST_BYTES.toLong())
            assertThat(wrapped.inputStream.readAllBytes()).isEqualTo(bytes)
        })
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun rejectsAnOversizedDeclaredLengthWithoutReadingTheBody() {
        val request = object : HttpServletRequestWrapper(request(byteArrayOf())) {
            override fun getContentLengthLong(): Long = (MAX_JSON_REQUEST_BYTES + 1).toLong()
            override fun getInputStream(): ServletInputStream = error("Body must not be read")
        }
        assertRejected(request)
    }

    @Test
    fun rejectsAnOversizedBodyWhenContentLengthIsUnknown() {
        assertRejected(withUnknownLength(request(ByteArray(MAX_JSON_REQUEST_BYTES + 1))))
    }

    @Test
    fun acceptsAnExactLimitBodyWhenContentLengthIsUnknown() {
        val request = withUnknownLength(request(ByteArray(MAX_JSON_REQUEST_BYTES)))
        var calls = 0
        filter.doFilter(request, MockHttpServletResponse(), FilterChain { wrapped, _ ->
            calls++
            assertThat(wrapped.inputStream.readAllBytes()).hasSize(MAX_JSON_REQUEST_BYTES)
        })
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun countsBytesRatherThanCharacters() {
        val text = "\u03b1".repeat(MAX_JSON_REQUEST_BYTES / 2 + 1)
        assertThat(text.length).isLessThan(MAX_JSON_REQUEST_BYTES)
        assertRejected(request(text.toByteArray(UTF_8)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/webhook-endpoints", "/%61pi/v1/webhook-endpoints", "/other/path"])
    fun cannotBypassTheLimitWithAnEncodedOrDifferentPath(path: String) {
        val request = request(ByteArray(MAX_JSON_REQUEST_BYTES + 1)).apply { requestURI = path }
        assertRejected(request)
    }

    @Test
    fun forwardsEmptyHealthRequests() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        var calls = 0
        filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> calls++ })
        assertThat(calls).isEqualTo(1)
    }

    private fun assertRejected(request: HttpServletRequest) {
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, _ -> error("Controller must not be called") })
        assertThat(response.status).isEqualTo(413)
        assertThat(response.contentType).startsWith("application/problem+json")
        assertThat(response.contentAsString).contains("16384").doesNotContain("\u03b1")
    }

    private fun request(bytes: ByteArray): MockHttpServletRequest =
        MockHttpServletRequest("POST", "/api/v1/webhook-endpoints").apply {
            contentType = "application/json"
            characterEncoding = UTF_8.name()
            setContent(bytes)
        }

    private fun withUnknownLength(request: HttpServletRequest): HttpServletRequest =
        object : HttpServletRequestWrapper(request) {
            override fun getContentLength(): Int = -1
            override fun getContentLengthLong(): Long = -1
        }
}
