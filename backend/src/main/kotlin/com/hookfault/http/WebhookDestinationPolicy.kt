package com.hookfault.http

import com.hookfault.endpoints.parseWebhookUrl
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.net.URI
import java.util.Locale

@ConfigurationProperties("hookfault.webhooks")
data class WebhookDestinationProperties(val allowedOrigins: List<String>)

class WebhookDestinationRejectedException : IllegalArgumentException("Webhook destination is not allowed")

@Component
class WebhookDestinationPolicy(properties: WebhookDestinationProperties) {
    private val trustedHosts = setOf("localhost", "127.0.0.1", "[::1]", "demo-receiver", "host.docker.internal")
    private val allowedOrigins: Set<Origin> = properties.allowedOrigins.map { value ->
        val uri = parseWebhookUrl(value)
        require(uri != null && uri.host.lowercase(Locale.ROOT) in trustedHosts &&
            uri.rawPath in listOf("", "/") && uri.rawQuery == null) {
            "Webhook origins must be explicit HTTP(S) origins on supported local demo hosts"
        }
        origin(uri)
    }.toSet().also { require(it.isNotEmpty()) { "Configure at least one local webhook origin" } }

    fun requireAllowed(value: String): URI {
        val uri = parseWebhookUrl(value) ?: throw WebhookDestinationRejectedException()
        if (origin(uri) !in allowedOrigins) throw WebhookDestinationRejectedException()
        return uri
    }

    private fun origin(uri: URI): Origin {
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
        return Origin(scheme, uri.host.lowercase(Locale.ROOT), port)
    }

    private data class Origin(val scheme: String, val host: String, val port: Int)
}
