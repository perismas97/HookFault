package com.hookfault.receiver

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@SpringBootTest(webEnvironment = RANDOM_PORT)
class SignedWebhookHttpTest {
    companion object {
        private val testKey = ByteArray(32) { it.toByte() }
        private val keyFile = Files.createTempFile("hookfault-receiver-test", ".key")

        @JvmStatic
        @DynamicPropertySource
        fun configureSigningKey(registry: DynamicPropertyRegistry) {
            Files.write(keyFile, testKey)
            registry.add("HOOKFAULT_DEMO_SIGNING_KEY_FILE") { keyFile.toString() }
        }

        @JvmStatic
        @AfterAll
        fun removeKeyFile() {
            Files.deleteIfExists(keyFile)
        }
    }

    @LocalServerPort
    private var port: Int = 0
    private val body = """{"message":"Demo \u03b1"}""".toByteArray(Charsets.UTF_8)

    @Test
    fun acceptsVerifiedRawBytesAndReturnsNoContent() {
        val response = send(signedRequest().build())
        assertThat(response.statusCode()).isEqualTo(204)
        assertThat(response.body()).isEmpty()
    }

    @Test
    fun rejectsUnsignedRequests() {
        assertRejected(send(request(body).build()))
    }

    @Test
    fun rejectsStaleRequestsEvenWithACorrectSignature() {
        assertRejected(send(signedRequest(sentAt = Instant.now().minusSeconds(600)).build()))
    }

    @Test
    fun rejectsDuplicateAuthenticationHeaders() {
        val duplicateTimestamp = signedRequest().header("X-HookFault-Timestamp", Instant.now().epochSecond.toString())
        val duplicateSignature = signedRequest().header("X-HookFault-Signature", "v1=" + "ab".repeat(32))
        assertRejected(send(duplicateTimestamp.build()))
        assertRejected(send(duplicateSignature.build()))
    }

    @Test
    fun rejectsAChangedBodyWithoutEchoingIt() {
        val response = send(signedRequest().POST(HttpRequest.BodyPublishers.ofString("""{"message":"not-for-echo"}""")).build())
        assertRejected(response)
        assertThat(response.body()).doesNotContain("not-for-echo")
    }

    private fun signedRequest(sentAt: Instant = Instant.now()): HttpRequest.Builder {
        val timestamp = sentAt.epochSecond.toString()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(testKey, "HmacSHA256"))
        val digest = mac.doFinal("$timestamp.".toByteArray(Charsets.US_ASCII) + body)
        return request(body)
            .header("X-HookFault-Timestamp", timestamp)
            .header("X-HookFault-Signature", "v1=${HexFormat.of().formatHex(digest)}")
    }

    private fun request(bytes: ByteArray): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/webhooks"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))

    private fun send(request: HttpRequest): HttpResponse<String> =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            .use { client -> client.send(request, HttpResponse.BodyHandlers.ofString()) }

    private fun assertRejected(response: HttpResponse<String>) {
        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("HookFault-HMAC")
        assertThat(response.body()).contains("Webhook authentication failed.")
    }
}
