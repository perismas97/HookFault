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

    @Transactional(readOnly = true)
    fun get(id: UUID): WebhookEndpoint = repository.findById(id) ?: throw WebhookEndpointNotFoundException()

    @Transactional(readOnly = true)
    fun list(limit: Int, offset: Int): WebhookEndpointListResponse {
        if (limit !in 1..MAX_ENDPOINT_LIMIT || offset < 0) throw InvalidEndpointPaginationException()
        val rows = repository.findPage(limit + 1, offset)
        return WebhookEndpointListResponse(rows.take(limit).map { it.toResponse() }, limit, offset, rows.size > limit)
    }
}
