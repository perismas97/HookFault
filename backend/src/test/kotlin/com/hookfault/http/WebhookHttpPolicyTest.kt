package com.hookfault.http

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WebhookHttpPolicyTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun stopServers() {
        servers.forEach { it.stop(0) }
    }

    @Test
    fun bindsExplicitOriginsAndClosesTheClientWithItsContext() {
        var client: HttpClient? = null
        ApplicationContextRunner()
            .withUserConfiguration(WebhookHttpConfiguration::class.java, WebhookDestinationPolicy::class.java)
            .withPropertyValues("hookfault.webhooks.allowed-origins[0]=http://localhost:8090")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(WebhookDestinationPolicy::class.java)
                    .requireAllowed("http://localhost:8090/webhooks").port).isEqualTo(8090)
                val configured = context.getBean(HttpClient::class.java)
                client = configured
                assertThat(configured.connectTimeout()).contains(Duration.ofSeconds(2))
                assertThat(configured.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER)
                assertThat(configured.proxy().orElseThrow().select(URI("http://localhost:8090")))
                    .containsExactly(Proxy.NO_PROXY)
            }
        assertThat(checkNotNull(client).isTerminated).isTrue()
    }

    @Test
    fun failsStartupWithAnUnsafeConfiguredOrigin() {
        ApplicationContextRunner()
            .withUserConfiguration(WebhookHttpConfiguration::class.java, WebhookDestinationPolicy::class.java)
            .withPropertyValues("hookfault.webhooks.allowed-origins[0]=http://example.com:8090")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
            }
    }

    @Test
    fun aScalarOverrideReplacesTheYamlAllowlistRatherThanExtendingIt() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(WebhookHttpConfiguration::class.java, WebhookDestinationPolicy::class.java)
            .withPropertyValues("hookfault.webhooks.allowed-origins=http://127.0.0.1:9000,http://localhost:9001")
            .run { context ->
                assertThat(context).hasNotFailed()
                val policy = context.getBean(WebhookDestinationPolicy::class.java)
                assertThat(policy.requireAllowed("http://127.0.0.1:9000/webhooks").port).isEqualTo(9000)
                assertThat(policy.requireAllowed("http://localhost:9001/webhooks").port).isEqualTo(9001)
                assertThatThrownBy { policy.requireAllowed("http://localhost:8090/webhooks") }
                    .isInstanceOf(WebhookDestinationRejectedException::class.java)
            }
    }

    @Test
    fun preparesBoundedPostRequestsWithAFiniteTimeout() {
        val body = ByteArray(MAX_JSON_REQUEST_BYTES)
        val request = factory("http://127.0.0.1:8090").create("http://127.0.0.1:8090/webhooks", body)
        assertThat(request.method()).isEqualTo("POST")
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json")
        assertThat(request.timeout()).contains(Duration.ofSeconds(5))
        assertThat(request.bodyPublisher().orElseThrow().contentLength()).isEqualTo(MAX_JSON_REQUEST_BYTES.toLong())
    }

    @Test
    fun rejectsOversizedOutboundBodies() {
        assertThatThrownBy {
            factory("http://127.0.0.1:8090")
                .create("http://127.0.0.1:8090/webhooks", ByteArray(MAX_JSON_REQUEST_BYTES + 1))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("16384 bytes")
    }

    @Test
    fun revalidatesDestinationsWhenPreparingRequestsFromStoredUrls() {
        assertThatThrownBy {
            factory("http://127.0.0.1:8090").create("http://example.com/webhooks", "{}".toByteArray())
        }.isInstanceOf(WebhookDestinationRejectedException::class.java)
    }

    @ParameterizedTest
    @ValueSource(ints = [301, 302, 303, 307, 308])
    fun neverFollowsRelativeAllowedOrDisallowedRedirects(status: Int) {
        val targetCalls = AtomicInteger()
        val allowedTarget = server { exchange ->
            targetCalls.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val blockedTarget = server { exchange ->
            targetCalls.incrementAndGet()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val redirector = server { exchange ->
            exchange.requestBody.readAllBytes()
            val location = when (exchange.requestURI.path) {
                "/relative" -> "/target"
                "/allowed" -> "http://127.0.0.1:${allowedTarget.address.port}/target"
                "/blocked" -> "http://127.0.0.1:${blockedTarget.address.port}/target"
                else -> null
            }
            if (location == null) {
                targetCalls.incrementAndGet()
                exchange.sendResponseHeaders(204, -1)
            } else {
                exchange.responseHeaders.add("Location", location)
                exchange.sendResponseHeaders(status, -1)
            }
            exchange.close()
        }
        val origin = "http://127.0.0.1:${redirector.address.port}"
        val requests = factory(origin, "http://127.0.0.1:${allowedTarget.address.port}")
        WebhookHttpConfiguration().webhookHttpClient().use { client ->
            for (path in listOf("/relative", "/allowed", "/blocked")) {
                val response = client.send(requests.create("$origin$path", "{}".toByteArray()), HttpResponse.BodyHandlers.discarding())
                assertThat(response.statusCode()).isEqualTo(status)
            }
        }
        assertThat(targetCalls.get()).isZero()
    }

    @Test
    fun sendsASnapshotOfTheBodyRatherThanAMutableCallerBuffer() {
        val received = mutableListOf<String>()
        val receiver = server { exchange ->
            received.add(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val origin = "http://127.0.0.1:${receiver.address.port}"
        val bytes = "{}".toByteArray()
        val request = factory(origin).create("$origin/webhooks", bytes)
        bytes[0] = '!'.code.toByte()
        WebhookHttpConfiguration().webhookHttpClient().use { client ->
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204)
        }
        assertThat(received).containsExactly("{}")
    }

    @Test
    fun timesOutAReceiverThatDoesNotSendResponseHeaders() {
        val release = CountDownLatch(1)
        val receiver = server { exchange ->
            exchange.requestBody.readAllBytes()
            release.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        val origin = "http://127.0.0.1:${receiver.address.port}"
        val client = WebhookHttpConfiguration().webhookHttpClient()
        try {
            assertThatThrownBy {
                client.send(factory(origin).create("$origin/webhooks", "{}".toByteArray()), HttpResponse.BodyHandlers.discarding())
            }.isInstanceOf(HttpTimeoutException::class.java)
        } finally {
            release.countDown()
            client.close()
        }
    }

    private fun factory(vararg origins: String): WebhookRequestFactory =
        WebhookRequestFactory(WebhookDestinationPolicy(WebhookDestinationProperties(origins.toList())))

    private fun server(handler: HttpHandler): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also {
            it.createContext("/", handler)
            it.start()
            servers.add(it)
        }
}
