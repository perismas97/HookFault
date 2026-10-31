package com.hookfault.endpoints

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class WebhookEndpointService(private val repository: WebhookEndpointRepository) {
    @Transactional
    fun register(name: String, url: String): WebhookEndpoint = repository.create(UUID.randomUUID(), name, url)
}
