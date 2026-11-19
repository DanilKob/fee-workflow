package com.cashi.fee.api

import com.cashi.fee.support.FeeIntegrationTest
import com.cashi.fee.support.Http
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The OpenAPI document and Swagger UI (plan task 6.4), served by the running app (the `it` profile
 * turns them on; they are off by default): both endpoints with every status they answer, the brief's
 * sample as the request example, and the schemas in the snake_case the API really speaks.
 */
@FeeIntegrationTest
class OpenApiIT {

    @Value("\${local.server.port}")
    private var port: Int = 0

    private val json = JsonMapper.builder().build()

    private fun apiDocs(): JsonNode {
        val response = Http.get("http://localhost:$port/v3/api-docs")
        assertEquals(200, response.statusCode(), response.body())
        return json.readTree(response.body())
    }

    @Test
    fun `the document lists POST transaction fee with every status it answers`() {
        val post = apiDocs().path("paths").path("/transaction/fee").path("post")

        assertEquals(setOf("200", "202", "400", "409", "500", "503"), post.path("responses").propertyNames().toSet(), post.toString())
    }

    @Test
    fun `the document lists GET transaction fee with every status it answers`() {
        val get = apiDocs().path("paths").path("/transaction/{transactionId}/fee").path("get")

        assertEquals(setOf("200", "202", "400", "404", "409", "500", "503"), get.path("responses").propertyNames().toSet(), get.toString())
    }

    @Test
    fun `the request example is the brief's sample, and tells the reader to change transaction_id before resending`() {
        val example = apiDocs().path("paths").path("/transaction/fee").path("post")
            .path("requestBody").path("content").path("application/json").path("examples").path("brief")

        assertEquals(json.readTree(FeeApiExamples.REQUEST), example.path("value"), example.toString())
        assertTrue(example.path("description").asString().contains("change transaction_id before resending")) { example.toString() }
    }

    @Test
    fun `the schemas name the snake_case keys the API sends`() {
        val schemas = apiDocs().path("components").path("schemas")

        assertEquals(
            setOf("transaction_id", "amount", "asset", "type", "fee", "rate", "description"),
            schemas.path("TransactionFeeResponse").path("properties").propertyNames().toSet(),
        )
        assertEquals(
            setOf("transaction_id", "amount", "asset", "asset_type", "type", "state", "created_at"),
            schemas.path("TransactionFeeRequest").path("properties").propertyNames().toSet(),
        )
        assertEquals(setOf("transaction_id", "status"), schemas.path("FeeProcessingResponse").path("properties").propertyNames().toSet())
    }

    /**
     * The request's fields are nullable in Kotlin only for the validator (AC27); with springdoc's
     * kotlin-nullable-property-customizer off (application.yml) the document shows each as required
     * and never null. A misspelt or renamed key would turn them optional and nullable here.
     */
    @Test
    fun `the request schema marks every field required and none nullable`() {
        val request = apiDocs().path("components").path("schemas").path("TransactionFeeRequest")
        val keys = setOf("transaction_id", "amount", "asset", "asset_type", "type", "state", "created_at")

        val required = request.path("required")
        assertEquals(keys, (0 until required.size()).map { required.get(it).asString() }.toSet(), request.toString())
        val properties = request.path("properties")
        for (name in properties.propertyNames()) {
            val property = properties.path(name)
            val type = property.path("type")
            val types = if (type.isArray) (0 until type.size()).map { type.get(it).asString() } else listOf(type.asString())
            assertFalse("null" in types, "$name: $property")
            assertFalse(property.path("nullable").asBoolean(false), "$name: $property")
        }
    }

    @Test
    fun `the Swagger UI is served at swagger-ui html`() {
        val redirect = Http.get("http://localhost:$port/swagger-ui.html")
        assertEquals(302, redirect.statusCode(), redirect.body())
        val location = redirect.headers().firstValue("location").orElse("")
        assertEquals("/swagger-ui/index.html", location)

        val page = Http.get("http://localhost:$port$location")
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("Swagger UI")) { page.body().take(300) }
    }
}
