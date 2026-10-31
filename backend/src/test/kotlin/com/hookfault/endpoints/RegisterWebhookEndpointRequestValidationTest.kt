package com.hookfault.endpoints

import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegisterWebhookEndpointRequestValidationTest {
    private val factory = Validation.buildDefaultValidatorFactory()
    private val validator = factory.validator

    @AfterAll
    fun closeValidatorFactory() {
        factory.close()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://localhost:8090/webhooks",
            "https://example.com/webhooks",
            "http://127.0.0.1:8090/webhooks",
            "http://[::1]:8090/webhooks",
            "http://receiver:8090/webhooks",
            "http://receiver:1/webhooks",
            "http://receiver:65535/webhooks",
            "HTTPS://EXAMPLE.COM/webhooks",
            "https://example.com/webhooks?version=1",
            "https://example.com/webhooks/a%20b",
        ],
    )
    fun acceptsWellFormedHttpUrls(url: String) {
        assertThat(validator.validate(RegisterWebhookEndpointRequest("Demo receiver", url))).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "   ",
            "\t",
            "\u00a0",
            "\u2007",
            "\u202f",
            "/webhooks",
            "//example.com/webhooks",
            "localhost:8090/webhooks",
            "ftp://example.com/webhooks",
            "file:///tmp/webhooks",
            "mailto:someone@example.com",
            "http:///webhooks",
            "http://",
            "https://example.com/a b",
            "http://example.com:0/webhooks",
            "http://example.com:65536/webhooks",
            "http://example.com:-1/webhooks",
            "http://user:password@example.com/webhooks",
            "https://example.com/webhooks#fragment",
            "https://example.com/%ZZ",
            "https://example.com\\@localhost/webhooks",
        ],
    )
    fun rejectsInvalidWebhookUrls(url: String) {
        val violations = validator.validate(RegisterWebhookEndpointRequest("Demo receiver", url))

        assertThat(violations).isNotEmpty()
        assertThat(violations.map { it.propertyPath.toString() }).containsOnly("url")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "\t", "\r\n"])
    fun rejectsBlankNames(name: String) {
        val violations = validator.validate(RegisterWebhookEndpointRequest(name, "http://localhost:8090/webhooks"))

        assertThat(violations.map { it.propertyPath.toString() }).contains("name")
    }

    @Test
    fun acceptsNameAndUrlAtTheirLengthLimits() {
        val prefix = "https://example.com/"
        val request = RegisterWebhookEndpointRequest("n".repeat(100), prefix + "x".repeat(2048 - prefix.length))

        assertThat(validator.validate(request)).isEmpty()
    }

    @Test
    fun rejectsOverlongNames() {
        val request = RegisterWebhookEndpointRequest("n".repeat(101), "http://localhost:8090/webhooks")

        assertThat(validator.validate(request).map { it.propertyPath.toString() }).contains("name")
    }

    @Test
    fun rejectsOverlongUrls() {
        val request = RegisterWebhookEndpointRequest("Demo receiver", "https://example.com/" + "x".repeat(2048))

        assertThat(validator.validate(request).map { it.propertyPath.toString() }).contains("url")
    }
}
