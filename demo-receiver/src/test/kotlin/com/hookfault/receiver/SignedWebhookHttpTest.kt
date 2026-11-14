package com.hookfault.receiver

import com.hookfault.receiver.events.DemoEventProcessor
import com.hookfault.receiver.events.DemoUserCreated
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import(ReceiverPostgresTestConfiguration::class)
class SignedWebhookHttpTest @Autowired internal constructor(
    private val jdbc: JdbcTemplate,
    private val processor: DemoEventProcessor,
    private val transactions: TransactionTemplate,
) {
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
    private val runId = UUID.randomUUID()
    private val eventId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val body = eventBody()

    @Test
    fun acceptsVerifiedRawBytesAndRecordsExactlyOneWelcomeNotification() {
        val response = send(signedRequest().build())
        assertThat(response.statusCode()).isEqualTo(204)
        assertThat(response.body()).isEmpty()
        assertCounts(1, 1)
        assertThat(jdbc.queryForObject(
            "select user_id from hookfault_receiver.welcome_notifications where run_id = ? and event_id = ?",
            UUID::class.java, runId, eventId,
        )).isEqualTo(userId)
    }

    @Test
    fun retriesWithANewSignatureDoNotRepeatTheAction() {
        val timestamp = Instant.now().minusSeconds(1)
        assertThat(send(signedRequest(sentAt = timestamp).build()).statusCode()).isEqualTo(204)
        assertThat(send(signedRequest(sentAt = timestamp.plusSeconds(1)).build()).statusCode()).isEqualTo(204)
        assertCounts(1, 1)
    }

    @Test
    fun concurrentDeliveriesCreateOnlyOneNotification() {
        val workers = 8
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures = (1..workers).map {
                executor.submit<Int> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    send(signedRequest().build()).statusCode()
                }
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            assertThat(futures.map { it.get(15, TimeUnit.SECONDS) }).containsOnly(204)
            assertCounts(1, 1)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun theSameEventIdInADifferentRunIsIndependent() {
        val otherRun = UUID.randomUUID()
        assertThat(send(signedRequest().build()).statusCode()).isEqualTo(204)
        assertThat(send(signedRequest(bytes = eventBody(run = otherRun)).build()).statusCode()).isEqualTo(204)
        assertCounts(1, 1)
        assertCounts(1, 1, otherRun)
    }

    @Test
    fun rollbackReleasesTheClaimAndTheNextDeliveryCanProcessIt() {
        transactions.executeWithoutResult { transaction ->
            processor.process(DemoUserCreated(runId, eventId, userId))
            assertCounts(1, 1)
            transaction.setRollbackOnly()
        }
        assertCounts(0, 0)
        assertThat(send(signedRequest().build()).statusCode()).isEqualTo(204)
        assertCounts(1, 1)
    }

    @Test
    fun signedInvalidEventsAreRejectedBeforeClaimingTheEvent() {
        val invalid = body.toString(Charsets.UTF_8)
            .replace("demo.user.created", "demo.unknown").toByteArray(Charsets.UTF_8)
        val response = send(signedRequest(bytes = invalid).build())
        assertThat(response.statusCode()).isEqualTo(400)
        assertCounts(0, 0)
        assertThat(send(signedRequest().build()).statusCode()).isEqualTo(204)
        assertCounts(1, 1)
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

    private fun signedRequest(sentAt: Instant = Instant.now(), bytes: ByteArray = body): HttpRequest.Builder {
        val timestamp = sentAt.epochSecond.toString()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(testKey, "HmacSHA256"))
        val digest = mac.doFinal("$timestamp.".toByteArray(Charsets.US_ASCII) + bytes)
        return request(bytes)
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
        assertCounts(0, 0)
    }

    private fun eventBody(run: UUID = runId): ByteArray =
        ("""{"schemaVersion":1,"id":"$eventId","type":"demo.user.created","occurredAt":"2026-11-06T10:00:00Z","runId":"$run","payload":{"userId":"$userId","displayName":"Demo """ +
            "\u03b1\"}}").toByteArray(Charsets.UTF_8)

    private fun assertCounts(processed: Int, notifications: Int, run: UUID = runId) {
        assertThat(jdbc.queryForObject(
            "select count(*) from hookfault_receiver.processed_events where run_id = ?", Int::class.java, run,
        )).isEqualTo(processed)
        assertThat(jdbc.queryForObject(
            "select count(*) from hookfault_receiver.welcome_notifications where run_id = ?", Int::class.java, run,
        )).isEqualTo(notifications)
    }
}
