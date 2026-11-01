package com.hookfault.endpoints

import com.hookfault.http.WebhookDestinationPolicy
import com.hookfault.http.WebhookDestinationProperties
import com.hookfault.http.WebhookDestinationRejectedException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

class WebhookEndpointServiceTest {
    private val repository = Mockito.mock(WebhookEndpointRepository::class.java)
    private val service = WebhookEndpointService(repository,
        WebhookDestinationPolicy(WebhookDestinationProperties(listOf("http://localhost:8090"))))

    @Test
    fun retrievesStoredEndpointsWithoutApplyingTheCurrentDeliveryPolicy() {
        val endpoint = WebhookEndpoint(UUID.randomUUID(), "Old receiver", "http://example.com/webhooks", Instant.now())
        Mockito.`when`(repository.findById(endpoint.id)).thenReturn(endpoint)
        assertThat(service.get(endpoint.id)).isEqualTo(endpoint)
    }

    @Test
    fun reportsMissingEndpoints() {
        assertThatThrownBy { service.get(UUID.randomUUID()) }.isInstanceOf(WebhookEndpointNotFoundException::class.java)
    }

    @Test
    fun fetchesOneExtraRowToReportWhetherMoreItemsExist() {
        val rows = (1..3).map { WebhookEndpoint(UUID.randomUUID(), "Receiver $it", "http://localhost:8090/webhooks", Instant.now()) }
        Mockito.`when`(repository.findPage(3, 10)).thenReturn(rows)
        val page = service.list(2, 10)
        assertThat(page.items.map { it.id }).containsExactly(rows[0].id, rows[1].id)
        assertThat(page.hasMore).isTrue()
        assertThat(page.limit).isEqualTo(2)
        assertThat(page.offset).isEqualTo(10)
        Mockito.verify(repository).findPage(3, 10)
    }

    @Test
    fun reportsTheFinalPageWithoutAHypotheticalNextItem() {
        Mockito.`when`(repository.findPage(21, 0)).thenReturn(emptyList())
        assertThat(service.list(20, 0).hasMore).isFalse()
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 0, 101, Int.MAX_VALUE])
    fun rejectsInvalidLimitsWithoutExecutingAQuery(limit: Int) {
        assertThatThrownBy { service.list(limit, 0) }.isInstanceOf(InvalidEndpointPaginationException::class.java)
        Mockito.verifyNoInteractions(repository)
    }

    @Test
    fun rejectsNegativeOffsetsWithoutExecutingAQuery() {
        assertThatThrownBy { service.list(20, -1) }.isInstanceOf(InvalidEndpointPaginationException::class.java)
        Mockito.verifyNoInteractions(repository)
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://example.com/webhooks", "http://localhost:5432/", "http://127.0.0.2:8090/"])
    fun rejectsDisallowedDestinationsBeforeCallingTheRepository(url: String) {
        val repository = Mockito.mock(WebhookEndpointRepository::class.java)
        val policy = WebhookDestinationPolicy(WebhookDestinationProperties(listOf("http://localhost:8090")))
        val service = WebhookEndpointService(repository, policy)
        assertThatThrownBy { service.register("Demo receiver", url) }
            .isInstanceOf(WebhookDestinationRejectedException::class.java)
        Mockito.verifyNoInteractions(repository)
    }
}
