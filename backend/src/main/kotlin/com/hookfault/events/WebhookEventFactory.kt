package com.hookfault.events

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.UUID

class WebhookEventFactory(private val clock: Clock) {
    fun userCreated(runId: UUID, userId: UUID): WebhookEventEnvelope = WebhookEventEnvelope(
        schemaVersion = WEBHOOK_EVENT_SCHEMA_VERSION,
        id = UUID.randomUUID(),
        type = WebhookEventType.DEMO_USER_CREATED,
        occurredAt = clock.instant(),
        runId = runId,
        payload = DemoUserCreatedPayload(userId, "Demo user"),
    )
}

@Configuration(proxyBeanMethods = false)
class WebhookEventConfiguration {
    @Bean
    fun webhookEventFactory(): WebhookEventFactory = WebhookEventFactory(Clock.systemUTC())
}
