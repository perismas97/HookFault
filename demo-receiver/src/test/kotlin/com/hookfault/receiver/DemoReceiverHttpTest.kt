package com.hookfault.receiver

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.time.Duration

@SpringBootTest(webEnvironment = RANDOM_PORT)
class DemoReceiverHttpTest @Autowired constructor(private val objectMapper: ObjectMapper) {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun healthReportsUpWithoutADatabase() {
        val response = send(request("/actuator/health").GET().build())
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(objectMapper.readTree(response.body()).path("status").asString()).isEqualTo("UP")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun readsRawBytesAtTheLimitButRejectsUnverifiedRequests(chunked: Boolean) {
        val response = post(ByteArray(16 * 1024) { 'x'.code.toByte() }, chunked)
        assertProblem(response, 503, "Webhook verification is not enabled.")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun rejectsOversizedBodiesWithOrWithoutContentLength(chunked: Boolean) {
        val response = post(ByteArray(16 * 1024 + 1) { 'x'.code.toByte() }, chunked)
        assertProblem(response, 413, "Webhook body must not exceed 16384 bytes.")
    }

    @Test
    fun signatureHeadersAloneDoNotAuthorizeAnEventOrGetEchoed() {
        val signature = "v1=" + "ab".repeat(32)
        val timestamp = "1793959205"
        val response = send(
            request("/webhooks")
                .header("Content-Type", "application/json")
                .header("X-HookFault-Signature", signature)
                .header("X-HookFault-Timestamp", timestamp)
                .POST(BodyPublishers.ofString("""{"privateField":"not-for-echo"}"""))
                .build(),
        )

        assertProblem(response, 503, "Webhook verification is not enabled.")
        assertThat(response.body()).doesNotContain("not-for-echo", signature, timestamp)
    }

    @Test
    fun rejectsOtherContentTypes() {
        val response = send(
            request("/webhooks").header("Content-Type", "text/plain").POST(BodyPublishers.ofString("{}")).build(),
        )
        assertThat(response.statusCode()).isEqualTo(415)
    }

    @Test
    fun rejectsGetOnTheWebhookRoute() {
        assertThat(send(request("/webhooks").GET().build()).statusCode()).isEqualTo(405)
    }

    private fun post(bytes: ByteArray, chunked: Boolean): HttpResponse<String> {
        val publisher = if (chunked) {
            BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) }
        } else {
            BodyPublishers.ofByteArray(bytes)
        }
        return send(request("/webhooks").header("Content-Type", "application/json").POST(publisher).build())
    }

    private fun assertProblem(response: HttpResponse<String>, status: Int, detail: String) {
        assertThat(response.statusCode()).isEqualTo(status)
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json")
        val problem = objectMapper.readTree(response.body())
        assertThat(problem.path("status").asInt()).isEqualTo(status)
        assertThat(problem.path("detail").asString()).isEqualTo(detail)
    }

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))

    private fun send(request: HttpRequest): HttpResponse<String> =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()
            .use { client -> client.send(request, HttpResponse.BodyHandlers.ofString()) }
}
