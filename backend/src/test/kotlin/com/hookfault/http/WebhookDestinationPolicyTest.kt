package com.hookfault.http

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WebhookDestinationPolicyTest {
    private val origins = listOf(
        "http://localhost:8090", "http://127.0.0.1:8090", "http://[::1]:8090",
        "http://demo-receiver:8090", "http://host.docker.internal:8090",
    )
    private val policy = WebhookDestinationPolicy(WebhookDestinationProperties(origins))

    @ParameterizedTest
    @ValueSource(strings = [
        "http://localhost:8090/webhooks", "HTTP://LOCALHOST:8090/webhooks",
        "http://127.0.0.1:8090/webhooks", "http://[::1]:8090/webhooks",
        "http://demo-receiver:8090/webhooks", "http://host.docker.internal:8090/webhooks",
        "http://localhost:8090/a%20b?version=1", "http://localhost:8090",
    ])
    fun acceptsOnlyExplicitOriginsWithoutResolvingThem(url: String) {
        assertThat(policy.requireAllowed(url).toString()).isEqualTo(url)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "https://example.com/webhooks", "http://192.168.1.2:8090/webhooks",
        "http://169.254.169.254/latest/meta-data", "http://127.0.0.2:8090/webhooks",
        "http://localhost:5432/", "http://localhost:8080/actuator/health", "http://localhost/",
        "https://localhost:8090/webhooks", "http://localhost.evil.test:8090/webhooks",
        "http://evil.localhost:8090/webhooks", "http://localhost.:8090/webhooks",
        "http://127.1:8090/webhooks", "http://2130706433:8090/webhooks",
        "http://0177.0.0.1:8090/webhooks", "http://0x7f000001:8090/webhooks",
        "http://[::ffff:127.0.0.1]:8090/webhooks", "http://[::1%25lo]:8090/webhooks",
        "http://local%68ost:8090/webhooks", "http://user:password@localhost:8090/webhooks",
        "http://localhost:8090@evil.test/webhooks", "http://evil.test@localhost:8090/webhooks",
        "http://localhost:8090/webhooks#fragment", "http://localhost:8090\\@evil.test/webhooks",
        "//localhost:8090/webhooks", "file:///etc/passwd", "ftp://localhost:8090/webhooks",
        "http://localhost:0/", "http://localhost:65536/", "http:///webhooks", "",
    ])
    fun rejectsOtherOriginsAndParserBypassAttempts(url: String) {
        assertThatThrownBy { policy.requireAllowed(url) }
            .isInstanceOf(WebhookDestinationRejectedException::class.java)
            .hasMessage("Webhook destination is not allowed")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "*", "http://*.localhost:8090", "http://example.com:8090", "http://192.168.1.2:8090",
        "http://127.0.0.2:8090", "http://localhost:8090/webhooks", "http://localhost:8090?x=1",
        "http://localhost:8090#fragment", "http://user:secret@localhost:8090", "file:///tmp/webhooks",
    ])
    fun rejectsUnsafeOriginConfiguration(origin: String) {
        assertThatThrownBy { WebhookDestinationPolicy(WebhookDestinationProperties(listOf(origin))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Webhook origins must be explicit HTTP(S) origins on supported local demo hosts")
    }

    @Test
    fun rejectsAnEmptyAllowlist() {
        assertThatThrownBy { WebhookDestinationPolicy(WebhookDestinationProperties(emptyList())) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun canReplaceDefaultPortsWithoutGrantingAccessToOtherPorts() {
        val custom = WebhookDestinationPolicy(WebhookDestinationProperties(listOf("http://127.0.0.1:9000")))
        assertThat(custom.requireAllowed("http://127.0.0.1:9000/webhooks").port).isEqualTo(9000)
        assertThatThrownBy { custom.requireAllowed("http://127.0.0.1:8090/webhooks") }
            .isInstanceOf(WebhookDestinationRejectedException::class.java)
    }

    @Test
    fun comparesEffectiveDefaultPortsAndRetainsTheScheme() {
        val custom = WebhookDestinationPolicy(WebhookDestinationProperties(listOf("https://localhost")))
        assertThat(custom.requireAllowed("https://LOCALHOST:443/webhooks").port).isEqualTo(443)
        assertThatThrownBy { custom.requireAllowed("http://localhost:443/webhooks") }
            .isInstanceOf(WebhookDestinationRejectedException::class.java)
    }
}
