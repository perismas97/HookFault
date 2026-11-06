package com.hookfault.events

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.core.json.JsonWriteFeature
import tools.jackson.core.JacksonException
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.TimeZone
import java.util.UUID

class WebhookEventSerializerTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val serializer = WebhookEventSerializer(mapper)
    private val event = WebhookEventEnvelope(
        schemaVersion = 1,
        id = UUID.fromString("00000000-0000-4000-8000-000000000001"),
        type = WebhookEventType.DEMO_USER_CREATED,
        occurredAt = Instant.parse("2026-11-06T10:00:00.123456789Z"),
        runId = UUID.fromString("00000000-0000-4000-8000-000000000002"),
        payload = DemoUserCreatedPayload(UUID.fromString("00000000-0000-4000-8000-000000000003"), "Demo user"),
    )

    @Test
    fun producesTheExactCompactVersionOneWireFormat() {
        val expected = """{"schemaVersion":1,"id":"00000000-0000-4000-8000-000000000001","type":"demo.user.created","occurredAt":"2026-11-06T10:00:00.123456789Z","runId":"00000000-0000-4000-8000-000000000002","payload":{"userId":"00000000-0000-4000-8000-000000000003","displayName":"Demo user"}}"""
        val result = serializer.serialize(event)
        assertThat(result.bodyBytes()).isEqualTo(expected.toByteArray(UTF_8))
        assertThat(result.size).isEqualTo(expected.toByteArray(UTF_8).size)
        assertThat(result.envelope).isSameAs(event)
        assertThat(mapper.readValue(result.bodyBytes(), WebhookEventEnvelope::class.java)).isEqualTo(event)
    }

    @Test
    fun repeatedSerializationAndRetryReadsKeepTheOriginalBytesAndIdentity() {
        val first = serializer.serialize(event)
        val retry = first.bodyBytes()
        assertThat(first.bodyBytes()).isEqualTo(retry).isNotSameAs(retry)
        assertThat(serializer.serialize(event).bodyBytes()).isEqualTo(retry)
        assertThat(first.envelope.id).isEqualTo(event.id)
        assertThat(first.envelope.occurredAt).isEqualTo(event.occurredAt)
    }

    @Test
    fun callersCannotMutateTheRetainedBytes() {
        val result = serializer.serialize(event)
        val original = result.bodyBytes()
        val exposed = result.bodyBytes()
        exposed.fill(0)
        assertThat(result.bodyBytes()).isEqualTo(original)
    }

    @Test
    fun alsoCopiesTheInitialByteArray() {
        val original = "{}".toByteArray(UTF_8)
        val retained = SerializedWebhookEvent(event, original)
        original.fill(0)
        assertThat(retained.bodyBytes()).isEqualTo("{}".toByteArray(UTF_8))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Demo user", "Demo \"quoted\" user", "Demo\\user\nline\ttab", "\u0394\u03bf\u03ba\u03b9\u03bc\u03ae", "Demo \ud83d\ude80"])
    fun roundTripsEscapingAndUtf8WithoutChangingThePayload(name: String) {
        val source = event.copy(payload = event.payload.copy(displayName = name))
        val serialized = serializer.serialize(source)
        assertThat(mapper.readValue(serialized.bodyBytes(), WebhookEventEnvelope::class.java)).isEqualTo(source)
        assertThat(serialized.size).isEqualTo(serialized.bodyBytes().size)
        assertThat(serialized.size).isLessThan(16 * 1024)
    }

    @Test
    fun keepsTheEventWireFormatIndependentOfCommonApiFormattingOptions() {
        val configured = JsonMapper.builder().addModule(KotlinModule.Builder().build())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .defaultTimeZone(TimeZone.getTimeZone("Europe/Athens"))
            .build()
        val source = event.copy(payload = event.payload.copy(displayName = "\u0394\u03bf\u03ba\u03b9\u03bc\u03ae"))
        assertThat(WebhookEventSerializer(configured).serialize(source).bodyBytes()).isEqualTo(serializer.serialize(source).bodyBytes())
        assertThat(configured.isEnabled(SerializationFeature.INDENT_OUTPUT)).isTrue()
        assertThat(configured.isEnabled(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "\"schemaVersion\":2", "\"schemaVersion\":0", "\"schemaVersion\":null",
        "\"type\":\"unknown.event\"", "\"type\":null", "\"occurredAt\":\"not-a-time\"", "\"id\":\"not-a-uuid\"",
    ])
    fun rejectsUnsupportedMetadataWhenReadingTheDocumentedModel(replacement: String) {
        val original = serializer.serialize(event).bodyBytes().toString(UTF_8)
        val tree = mapper.readTree(original) as tools.jackson.databind.node.ObjectNode
        val replacementTree = mapper.readTree("{$replacement}")
        val key = replacementTree.propertyNames().single()
        tree.set(key, replacementTree.path(key))
        assertThatThrownBy { mapper.treeToValue(tree, WebhookEventEnvelope::class.java) }.isInstanceOf(JacksonException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["schemaVersion", "id", "type", "occurredAt", "runId", "payload"])
    fun rejectsMissingEnvelopeFields(field: String) {
        val tree = mapper.readTree(serializer.serialize(event).bodyBytes()) as tools.jackson.databind.node.ObjectNode
        tree.remove(field)
        assertThatThrownBy { mapper.treeToValue(tree, WebhookEventEnvelope::class.java) }.isInstanceOf(JacksonException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["userId", "displayName"])
    fun rejectsMissingPayloadFields(field: String) {
        val tree = mapper.readTree(serializer.serialize(event).bodyBytes()) as tools.jackson.databind.node.ObjectNode
        (tree.path("payload") as tools.jackson.databind.node.ObjectNode).remove(field)
        assertThatThrownBy { mapper.treeToValue(tree, WebhookEventEnvelope::class.java) }.isInstanceOf(JacksonException::class.java)
    }
}
