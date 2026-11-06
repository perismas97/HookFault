package com.hookfault.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class WebhookEventFactoryTest {
    private val instant = Instant.parse("2026-11-06T10:00:00.123456789Z")
    private val factory = WebhookEventFactory(Clock.fixed(instant, ZoneOffset.UTC))

    @Test
    fun createsFictionalEventsWithExplicitRunAndSubjectIdentity() {
        val runId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val event = factory.userCreated(runId, userId)
        assertThat(event.schemaVersion).isEqualTo(1)
        assertThat(event.id.version()).isEqualTo(4)
        assertThat(event.runId).isEqualTo(runId)
        assertThat(event.occurredAt).isEqualTo(instant)
        assertThat(event.type).isEqualTo(WebhookEventType.DEMO_USER_CREATED)
        assertThat(event.payload).isEqualTo(DemoUserCreatedPayload(userId, "Demo user"))
    }

    @Test
    fun aNewFactoryCallCreatesANewEventRatherThanARetry() {
        val runId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val first = factory.userCreated(runId, userId)
        val second = factory.userCreated(runId, userId)
        assertThat(first.id).isNotEqualTo(second.id)
        assertThat(second.runId).isEqualTo(first.runId)
        assertThat(second.payload).isEqualTo(first.payload)
        assertThat(second.occurredAt).isEqualTo(first.occurredAt)
    }

    @Test
    fun registersTheFactoryAndSerializerWithoutDatabaseOrHttpDependencies() {
        ApplicationContextRunner()
            .withUserConfiguration(WebhookEventConfiguration::class.java, WebhookEventSerializer::class.java)
            .withBean(ObjectMapper::class.java, { JsonMapper.builder().addModule(KotlinModule.Builder().build()).build() })
            .run { context ->
                assertThat(context).hasNotFailed()
                val before = Instant.now()
                val event = context.getBean(WebhookEventFactory::class.java).userCreated(UUID.randomUUID(), UUID.randomUUID())
                assertThat(event.occurredAt).isBetween(before, Instant.now())
                assertThat(context.getBean(WebhookEventSerializer::class.java).serialize(event).size).isLessThan(16 * 1024)
            }
    }
}
