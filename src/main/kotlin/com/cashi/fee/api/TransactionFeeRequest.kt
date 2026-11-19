package com.cashi.fee.api

import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * The raw `POST /transaction/fee` body. Every field is a nullable [JsonNode], so binding never fails
 * on a field. The validator can then list a missing field and a wrong JSON type (a string `amount`)
 * together. Only a body that isn't a JSON object fails to bind.
 *
 * `@Schema` tells the OpenAPI document each field's JSON type, which `JsonNode` can't.
 */
data class TransactionFeeRequest(
    @field:Schema(
        implementation = String::class,
        requiredMode = RequiredMode.REQUIRED,
        description = "1 to 64 characters of A-Z, a-z, 0-9, _ or -. One charge per transaction_id.",
        example = "txn_001",
    )
    val transactionId: JsonNode? = null,
    @field:Schema(
        implementation = BigDecimal::class,
        requiredMode = RequiredMode.REQUIRED,
        description = "A JSON number greater than zero, at most 20 integer digits, and no more decimals than the asset allows.",
        example = "1000",
    )
    val amount: JsonNode? = null,
    @field:Schema(implementation = String::class, requiredMode = RequiredMode.REQUIRED, description = "A registered asset code.", example = "USD")
    val asset: JsonNode? = null,
    @field:Schema(implementation = String::class, requiredMode = RequiredMode.REQUIRED, description = "FIAT or CRYPTO, matching the asset.", example = "FIAT")
    val assetType: JsonNode? = null,
    @field:Schema(
        implementation = String::class,
        requiredMode = RequiredMode.REQUIRED,
        description = "A registered transaction type, spelled exactly.",
        example = "Mobile Top Up",
    )
    val type: JsonNode? = null,
    @field:Schema(implementation = String::class, requiredMode = RequiredMode.REQUIRED, description = "Must be SETTLED - PENDING FEE.", example = "SETTLED - PENDING FEE")
    val state: JsonNode? = null,
    @field:Schema(
        implementation = String::class,
        requiredMode = RequiredMode.REQUIRED,
        description = "UTC, in the format yyyy-MM-dd HH:mm:ss.SSSSSS.",
        example = "2023-08-30 15:42:17.610059",
    )
    val createdAt: JsonNode? = null,
)
