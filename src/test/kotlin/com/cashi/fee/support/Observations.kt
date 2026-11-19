package com.cashi.fee.support

import com.cashi.fee.support.RestateAdmin.Companion.literal
import com.cashi.fee.workflow.model.Charge
import kotlinx.serialization.json.Json
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

/** What the system holds after a request: the FeeCharge state, the workflow journal, the fee_record row. */
object Observations {

    /** The charge FeeCharge keeps for [transactionId], read from Restate's `state` table; null when none. */
    fun charge(transactionId: String): Charge? = chargeJson(transactionId)?.let { Json.decodeFromString<Charge>(it) }

    /** The charge FeeCharge keeps for [transactionId], as the JSON text Restate stores; null when none. */
    fun chargeJson(transactionId: String): String? =
        TestInfrastructure.admin
            .query(
                "SELECT value_utf8 FROM state WHERE service_name = 'FeeCharge' AND service_key = ${literal(transactionId)} AND key = 'charge'",
            )
            .singleOrNull()
            ?.get("value_utf8")
            ?.asString()

    /**
     * Every invocation Restate knows for the key [transactionId], of any service (FeeWorkflow,
     * FeeCharge), as `Service/handler status`. Empty when nothing was ever started for it (AC26).
     */
    fun invocations(transactionId: String): List<String> =
        TestInfrastructure.admin
            .query(
                """
                SELECT target_service_name, target_handler_name, status FROM sys_invocation
                WHERE target_service_key = ${literal(transactionId)}
                """.trimIndent(),
            )
            .map { "${it.get("target_service_name").asString()}/${it.get("target_handler_name").asString()} ${it.get("status").asString()}" }

    /** One journal command of the workflow run: `Run` with its name, or `Call` with `Service/handler`. */
    data class JournalCommand(val kind: String, val label: String)

    /** The commands the FeeWorkflow run for [transactionId] journaled, in order. */
    fun workflowJournal(transactionId: String): List<JournalCommand> =
        TestInfrastructure.admin
            .query(
                """
                SELECT j.index, j.entry_type, j.name, j.entry_lite_json
                FROM sys_journal j JOIN sys_invocation i ON j.id = i.id
                WHERE i.target_service_name = 'FeeWorkflow' AND i.target_service_key = ${literal(transactionId)}
                ORDER BY j.index
                """.trimIndent(),
            )
            .mapNotNull { row ->
                when (row.get("entry_type").asString()) {
                    "Command: Run" -> JournalCommand("Run", row.get("name").asString())
                    "Command: Call" -> {
                        val target = JSON.readTree(row.get("entry_lite_json").asString())
                            .path("Command").path("Call").path("invocation_target").properties().first().value
                        JournalCommand("Call", "${target.get("name").asString()}/${target.get("handler").asString()}")
                    }
                    else -> null
                }
            }

    /** Every `fee_record` row for [transactionId], each column as Postgres renders it as text. */
    fun feeRecords(jdbc: JdbcTemplate, transactionId: String): List<Map<String, String>> =
        jdbc.queryForList(
            """
            SELECT transaction_id, amount::text, asset, asset_type, type, fee::text, rate::text, description, rule_id,
                   rule_version::text, created_at::text, charge_id::text, charged_at::text, state, fingerprint,
                   recorded_at::text
            FROM fee_record WHERE transaction_id = ?
            """.trimIndent(),
            transactionId,
        ).map { row -> row.mapValues { (_, value) -> value.toString() } }

    /** A `timestamp` column's text (`2026-11-19 00:29:19.50831`) as a local date-time. */
    fun timestamp(text: String): LocalDateTime = LocalDateTime.parse(text.replace(' ', 'T'))

    private val JSON = tools.jackson.databind.json.JsonMapper.builder().build()
}
