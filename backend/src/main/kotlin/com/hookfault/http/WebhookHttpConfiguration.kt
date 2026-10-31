package com.hookfault.http

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.ProxySelector
import java.net.http.HttpClient

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookDestinationProperties::class)
class WebhookHttpConfiguration {
    @Bean(destroyMethod = "close")
    fun webhookHttpClient(): HttpClient = HttpClient.newBuilder()
        .connectTimeout(WEBHOOK_CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(ProxySelector.of(null))
        .build()
}
