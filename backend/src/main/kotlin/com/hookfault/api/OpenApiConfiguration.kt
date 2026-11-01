package com.hookfault.api

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
    @Bean
    fun hookFaultOpenApi(): OpenAPI = OpenAPI().info(
        Info().title("HookFault API").version("0.1.0")
            .description("Local webhook endpoint registration and inspection. Registration does not send a webhook."),
    )
}
