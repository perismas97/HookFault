package com.hookfault.http

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiRequestBodyLimitFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.contentLengthLong > MAX_JSON_REQUEST_BYTES) {
            reject(response)
            return
        }

        // Read at most one byte beyond the limit, including chunked requests without Content-Length.
        val body = request.inputStream.readNBytes(MAX_JSON_REQUEST_BYTES + 1)
        if (body.size > MAX_JSON_REQUEST_BYTES) {
            reject(response)
            return
        }
        chain.doFilter(BufferedRequest(request, body), response)
    }

    private fun reject(response: HttpServletResponse) {
        response.status = 413
        response.contentType = "application/problem+json"
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(
            """{"type":"about:blank","title":"Content Too Large","status":413,"detail":"Request body must not exceed $MAX_JSON_REQUEST_BYTES bytes."}""",
        )
    }

    private class BufferedRequest(request: HttpServletRequest, private val body: ByteArray) :
        HttpServletRequestWrapper(request) {
        private val stream = object : ServletInputStream() {
            private val input = ByteArrayInputStream(body)
            override fun read(): Int = input.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = input.read(bytes, offset, length)
            override fun isFinished(): Boolean = input.available() == 0
            override fun isReady(): Boolean = true
            override fun setReadListener(listener: ReadListener) {
                throw UnsupportedOperationException("Only blocking request reads are supported")
            }
        }

        override fun getInputStream(): ServletInputStream = stream
        override fun getReader(): BufferedReader =
            BufferedReader(InputStreamReader(stream, characterEncoding ?: StandardCharsets.UTF_8.name()))
        override fun getContentLength(): Int = body.size
        override fun getContentLengthLong(): Long = body.size.toLong()
    }
}
