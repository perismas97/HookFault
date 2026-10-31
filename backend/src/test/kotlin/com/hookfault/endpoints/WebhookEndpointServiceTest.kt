package com.hookfault.endpoints

import com.hookfault.http.WebhookDestinationPolicy
import com.hookfault.http.WebhookDestinationProperties
import com.hookfault.http.WebhookDestinationRejectedException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito

class WebhookEndpointServiceTest {
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
