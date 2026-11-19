package com.cashi.fee.api

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The API's description for `/v3/api-docs` and the Swagger UI at `/swagger-ui.html`. Only the
 * API info lives here; each operation documents itself on [FeeController].
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    fun feeOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("Transaction fee API")
                .version("v1")
                .description(
                    "Charges the fee of a settled transaction exactly once and records it. " +
                        "A transaction_id is charged once: resending the same payload answers the same body; " +
                        "the same transaction_id with a different payload is a 409.",
                ),
        )
}
