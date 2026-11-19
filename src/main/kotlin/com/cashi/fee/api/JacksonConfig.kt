package com.cashi.fee.api

import io.swagger.v3.core.jackson.ModelResolver
import io.swagger.v3.core.util.Json31
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.StreamWriteFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies as Jackson2PropertyNamingStrategies

/**
 * The HTTP JSON contract, on the Jackson 3 `JsonMapper` Spring MVC uses (Boot 4; Restate keeps its
 * own Jackson 2): snake_case keys (AC2), JSON floats read as `BigDecimal` so nothing passes
 * through `double` (AC8), and `BigDecimal` written in plain form, never `1E+3` (AC3).
 *
 * The OpenAPI document (springdoc) reads the DTOs through swagger-core's own Jackson 2 mapper, which
 * knows nothing of the one above; [openApiSnakeCase] gives it the same snake_case naming, so the
 * documented schemas name the keys the API really sends.
 */
@Configuration(proxyBeanMethods = false)
class JacksonConfig {

    @Bean
    fun feeJsonMapperCustomizer(): JsonMapperBuilderCustomizer = JsonMapperBuilderCustomizer { configure(it) }

    @Bean
    fun openApiSnakeCase(): ModelResolver =
        ModelResolver(Json31.mapper().copy().setPropertyNamingStrategy(Jackson2PropertyNamingStrategies.SNAKE_CASE)).openapi31(true)

    companion object {
        fun configure(builder: JsonMapper.Builder): JsonMapper.Builder =
            builder
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
    }
}
