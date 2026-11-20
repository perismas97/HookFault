package com.hookfault.delivery

import com.hookfault.events.DemoUserCreatedPayload
import com.hookfault.events.SerializedWebhookEvent
import com.hookfault.events.WebhookEventEnvelope
import com.hookfault.events.WebhookEventSerializer
import com.hookfault.events.WebhookEventType
import com.hookfault.http.MAX_JSON_REQUEST_BYTES
import com.hookfault.http.MAX_WEBHOOK_RESPONSE_BYTES
import com.hookfault.http.WebhookDestinationPolicy
import com.hookfault.http.WebhookDestinationProperties
import com.hookfault.http.WebhookDestinationRejectedException
import com.hookfault.http.WebhookHttpConfiguration
import com.hookfault.http.WebhookRequestFactory
import com.hookfault.signing.DemoSigningKey
import com.hookfault.signing.SignedWebhookEvent
import com.hookfault.signing.WebhookEventSigner
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WebhookDeliveryClientTest {
    private val servers = mutableListOf<HttpServer>()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val http = WebhookHttpConfiguration().webhookHttpClient()
    private val testKey = ByteArray(32) { it.toByte() }
    private val signer = WebhookEventSigner(DemoSigningKey(MockEnvironment()))
    private val serializer = WebhookEventSerializer(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
    private val envelope = WebhookEventEnvelope(
        1, UUID.randomUUID(), WebhookEventType.DEMO_USER_CREATED, Instant.parse("2026-11-20T10:00:00Z"),
        UUID.randomUUID(), DemoUserCreatedPayload(UUID.randomUUID(), "Demo \u03b1 \"quoted\""),
    )
    private val event = serializer.serialize(envelope)
    private val sentAt = Instant.parse("2026-11-20T10:00:05Z")
    private val signed = signer.sign(event, testKey, sentAt)

    @AfterEach
    fun closeResources() {
        servers.forEach { it.stop(0) }
        executor.shutdownNow()
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        http.close()
    }

    @Test
    fun wiresTheDeliveryClientWithTheExistingLocalHttpConfiguration() {
        ApplicationContextRunner().withUserConfiguration(
            WebhookHttpConfiguration::class.java, WebhookDestinationPolicy::class.java,
            WebhookRequestFactory::class.java, WebhookDeliveryClient::class.java,
        ).withPropertyValues("hookfault.webhooks.allowed-origins[0]=http://127.0.0.1:8090")
            .run { context -> assertThat(context).hasNotFailed().hasSingleBean(WebhookDeliveryClient::class.java) }
    }

    @Test
    fun deliversTheConfiguredSignatureAndExactUtf8BytesWithoutRegeneratingTheEvent(@TempDir directory: Path) {
        val receivedBodies = CopyOnWriteArrayList<ByteArray>()
        val receivedSignatures = CopyOnWriteArrayList<String>()
        val keyFile = Files.write(directory.resolve("test-key"), testKey)
        val configuredSigner = WebhookEventSigner(DemoSigningKey(
            MockEnvironment().withProperty("HOOKFAULT_DEMO_SIGNING_KEY_FILE", keyFile.toString()),
        ))
        val receiver = server { exchange ->
            val bytes = exchange.requestBody.readAllBytes()
            val timestamp = exchange.requestHeaders.getFirst("X-HookFault-Timestamp")
            val signature = exchange.requestHeaders.getFirst("X-HookFault-Signature")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(testKey, "HmacSHA256"))
            mac.update("$timestamp.".toByteArray(UTF_8))
            val expected = "v1=${HexFormat.of().formatHex(mac.doFinal(bytes))}"
            val valid = exchange.requestMethod == "POST" &&
                exchange.requestHeaders["Content-Type"] == listOf("application/json") &&
                exchange.requestHeaders["X-HookFault-Timestamp"]?.size == 1 &&
                exchange.requestHeaders["X-HookFault-Signature"]?.size == 1 && signature == expected
            receivedBodies.add(bytes)
            receivedSignatures.add(signature ?: "missing")
            exchange.sendResponseHeaders(if (valid) 204 else 401, -1)
        }
        val origin = origin(receiver)
        val delivery = delivery(origin)
        for (time in listOf(sentAt, sentAt.plusSeconds(1))) {
            val prepared = configuredSigner.sign(event, time)
            assertThat(response(delivery.deliver("$origin/webhooks?demo=1", prepared)).statusCode).isEqualTo(204)
        }
        assertThat(receivedBodies).hasSize(2)
        receivedBodies.forEach { assertThat(it).isEqualTo(event.bodyBytes()) }
        assertThat(receivedSignatures.toSet()).hasSize(2)
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 400, 401, 429, 500])
    fun preservesHttpRejectionsAndServerErrorsAsResponsesWithoutRetrying(status: Int) {
        val calls = AtomicInteger()
        val body = "receiver \u03b1".toByteArray(UTF_8)
        val receiver = server { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.readAllBytes()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.write(body)
        }
        val origin = origin(receiver)
        val result = response(delivery(origin).deliver("$origin/webhooks", signed))
        assertThat(result.statusCode).isEqualTo(status)
        assertThat(result.bodyBytes()).isEqualTo(body)
        result.bodyBytes().fill(0)
        assertThat(result.bodyBytes()).isEqualTo(body)
        assertThat(calls.get()).isEqualTo(1)
    }

    @Test
    fun acceptsANoContentResponse() {
        val receiver = server { it.requestBody.readAllBytes(); it.sendResponseHeaders(204, -1) }
        val origin = origin(receiver)
        val result = response(delivery(origin).deliver("$origin/webhooks", signed))
        assertThat(result.statusCode).isEqualTo(204)
        assertThat(result.bodyBytes()).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun acceptsExactlyTheByteLimitWithOrWithoutContentLength(chunked: Boolean) {
        val bytes = "\u03b1".repeat(MAX_WEBHOOK_RESPONSE_BYTES / 2).toByteArray(UTF_8)
        val receiver = server { exchange ->
            exchange.requestBody.readAllBytes()
            exchange.sendResponseHeaders(200, if (chunked) 0 else bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
        val origin = origin(receiver)
        assertThat(response(delivery(origin).deliver("$origin/webhooks", signed)).bodyBytes()).isEqualTo(bytes)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun rejectsOneByteOverTheLimitWithOrWithoutContentLengthAndKeepsTheClientUsable(chunked: Boolean) {
        val bytes = ByteArray(MAX_WEBHOOK_RESPONSE_BYTES + 1)
        val receiver = server { exchange ->
            exchange.requestBody.readAllBytes()
            if (exchange.requestURI.path == "/ok") {
                exchange.sendResponseHeaders(204, -1)
            } else {
                exchange.sendResponseHeaders(200, if (chunked) 0 else bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        val origin = origin(receiver)
        val delivery = delivery(origin)
        assertThat(delivery.deliver("$origin/large", signed)).isEqualTo(
            WebhookDeliveryResult.Failure(WebhookDeliveryFailure.RESPONSE_TOO_LARGE, 200),
        )
        assertThat(response(delivery.deliver("$origin/ok", signed)).statusCode).isEqualTo(204)
    }

    @ParameterizedTest
    @ValueSource(ints = [301, 302, 303, 307, 308])
    fun doesNotFollowRelativeAllowedOrBlockedRedirects(status: Int) {
        val targetCalls = AtomicInteger()
        val target = server { targetCalls.incrementAndGet(); it.sendResponseHeaders(204, -1) }
        val redirector = server { exchange ->
            exchange.requestBody.readAllBytes()
            val location = when (exchange.requestURI.path) {
                "/relative" -> "/target"
                "/allowed" -> "${origin(target)}/target"
                "/blocked" -> "http://example.com/webhooks"
                else -> null
            }
            if (location == null) targetCalls.incrementAndGet()
            else exchange.responseHeaders.add("Location", location)
            exchange.sendResponseHeaders(status, -1)
        }
        val origin = origin(redirector)
        val delivery = delivery(origin, origin(target))
        for (path in listOf("relative", "allowed", "blocked")) {
            assertThat(response(delivery.deliver("$origin/$path", signed)).statusCode).isEqualTo(status)
        }
        assertThat(targetCalls.get()).isZero()
    }

    @Test
    fun appliesTheProductionDeadlineWhenTheReceiverNeverSendsHeaders() {
        val release = CountDownLatch(1)
        val receiver = server {
            it.requestBody.readAllBytes()
            release.await(10, TimeUnit.SECONDS)
        }
        val origin = origin(receiver)
        val started = System.nanoTime()
        try {
            val result = delivery(origin).deliver("$origin/webhooks", signed)
            assertThat(result).isEqualTo(WebhookDeliveryResult.Failure(WebhookDeliveryFailure.TIMEOUT))
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(8))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun boundsTheWholeBodyReadEvenWhileBytesKeepArriving() {
        val chunks = AtomicInteger()
        val receiver = server { exchange ->
            exchange.requestBody.readAllBytes()
            if (exchange.requestURI.path == "/ok") {
                exchange.sendResponseHeaders(204, -1)
            } else {
                exchange.sendResponseHeaders(200, 0)
                repeat(200) {
                    exchange.responseBody.write(byteArrayOf(1))
                    exchange.responseBody.flush()
                    chunks.incrementAndGet()
                    Thread.sleep(50)
                }
            }
        }
        val origin = origin(receiver)
        val delivery = delivery(origin, timeout = Duration.ofSeconds(1))
        val started = System.nanoTime()
        assertThat(delivery.deliver("$origin/slow", signed)).isEqualTo(
            WebhookDeliveryResult.Failure(WebhookDeliveryFailure.TIMEOUT, 200),
        )
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3))
        assertThat(chunks.get()).isGreaterThan(2)
        assertThat(response(delivery.deliver("$origin/ok", signed)).statusCode).isEqualTo(204)
    }

    @Test
    fun reportsConnectionFailureWithoutInventingAnHttpStatusOrLeakingTheUrl() {
        val port = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val origin = "http://127.0.0.1:$port"
        assertThat(delivery(origin).deliver("$origin/webhooks?sensitive-query=test-only", signed)).isEqualTo(
            WebhookDeliveryResult.Failure(WebhookDeliveryFailure.NETWORK_ERROR),
        )
    }

    @Test
    fun distinguishesATruncatedBodyFromACompleteHttpResponse() {
        val receiver = server { exchange ->
            exchange.requestBody.readAllBytes()
            exchange.sendResponseHeaders(500, 20)
            exchange.responseBody.write(byteArrayOf(1))
        }
        val origin = origin(receiver)
        assertThat(delivery(origin).deliver("$origin/webhooks", signed)).isEqualTo(
            WebhookDeliveryResult.Failure(WebhookDeliveryFailure.NETWORK_ERROR, 500),
        )
    }

    @Test
    fun rechecksTheDestinationAndPayloadBeforeAnyNetworkCall() {
        val calls = AtomicInteger()
        val receiver = server { calls.incrementAndGet(); it.sendResponseHeaders(204, -1) }
        val origin = origin(receiver)
        val delivery = delivery(origin)
        assertThatThrownBy { delivery.deliver("http://example.com/webhooks", signed) }
            .isInstanceOf(WebhookDestinationRejectedException::class.java)
        val oversized = signer.sign(SerializedWebhookEvent(envelope, ByteArray(MAX_JSON_REQUEST_BYTES + 1)), testKey, sentAt)
        assertThatThrownBy { delivery.deliver("$origin/webhooks", oversized) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(calls.get()).isZero()
    }

    @Test
    fun interruptionCancelsTheExchangeAndPreservesTheInterruptFlag() {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val receiver = server {
            it.requestBody.readAllBytes()
            received.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val origin = origin(receiver)
        val delivery = delivery(origin)
        val error = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val worker = Thread {
            try {
                delivery.deliver("$origin/webhooks", signed)
            } catch (caught: Throwable) {
                error.set(caught)
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        worker.start()
        try {
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue()
            worker.interrupt()
            worker.join(3000)
            assertThat(worker.isAlive).isFalse()
            assertThat(error.get()).isInstanceOf(InterruptedException::class.java)
            assertThat(interrupted.get()).isTrue()
        } finally {
            worker.interrupt()
            release.countDown()
            worker.join(3000)
        }
    }

    private fun response(result: WebhookDeliveryResult): WebhookDeliveryResult.Response {
        assertThat(result).isInstanceOf(WebhookDeliveryResult.Response::class.java)
        return result as WebhookDeliveryResult.Response
    }

    private fun origin(server: HttpServer) = "http://127.0.0.1:${server.address.port}"

    private fun delivery(vararg origins: String, timeout: Duration? = null): WebhookDeliveryClient {
        val factory = WebhookRequestFactory(WebhookDestinationPolicy(WebhookDestinationProperties(origins.toList())))
        return if (timeout == null) WebhookDeliveryClient(http, factory) else WebhookDeliveryClient(http, factory, timeout)
    }

    private fun server(handler: HttpHandler): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
            server.executor = executor
            server.createContext("/") { exchange: HttpExchange ->
                try {
                    exchange.use { handler.handle(it) }
                } catch (_: IOException) {
                    // Cancellation and intentionally truncated replies close the test socket.
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            server.start()
            servers.add(server)
        }
}
