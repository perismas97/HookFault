package com.hookfault.endpoints

import com.hookfault.http.WebhookDestinationPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class WebhookEndpointService(
    private val repository: WebhookEndpointRepository,
    private val destinations: WebhookDestinationPolicy,
) {
    @Transactional
    fun register(name: String, url: String): WebhookEndpoint {
        destinations.requireAllowed(url)
        return repository.create(UUID.randomUUID(), name, url)
    }
}
