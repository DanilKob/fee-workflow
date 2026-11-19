package com.cashi.fee.workflow

import com.cashi.fee.support.ControllableFeeRecordRepository
import com.cashi.fee.support.FeeIntegrationTest
import com.cashi.fee.support.Http
import com.cashi.fee.support.TestInfrastructure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import tools.jackson.databind.JsonNode
import java.time.Duration

/**
 * The retry policy the durability guarantees rest on (plan task 6.1, AC23, R3), read back from the
 * Restate server through the admin API, so it shows what the server will do, not what the YAML says.
 *
 * - `restate.components.FeeWorkflow.retry-policy` (the IT profile) takes effect: it reaches the
 *   server in the endpoint manifest as FeeWorkflow's service-level policy, and no handler overrides
 *   it. Its `on_max_attempts` is `Pause`; nothing anywhere is `Kill`.
 * - `max-attempts` counts the first attempt: with 5, a record that fails 4 times still lands on the
 *   5th attempt. `durability.feature` pins the other side: the 5th failure pauses the run.
 */
@FeeIntegrationTest
class RetryPolicyIT {

    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired
    private lateinit var records: ControllableFeeRecordRepository

    @Test
    fun `FeeWorkflow's effective retry policy is the pinned budget, and it pauses, never kills`() {
        val service = TestInfrastructure.admin.service("FeeWorkflow")
        val policy = service.get("retry_policy")

        assertEquals(BUDGET, policy.get("max_attempts").asInt(), policy.toString())
        assertEquals("Pause", policy.get("on_max_attempts").asString(), policy.toString())
        assertEquals("100ms", policy.get("initial_interval").asString(), policy.toString())
        assertEquals("1s", policy.get("max_interval").asString(), policy.toString())
        assertNoHandlerKills(service)
    }

    /** FeeCharge keeps the server default (Pause after 70 attempts): a killed charge would fail the run after it. */
    @Test
    fun `FeeCharge's effective retry policy pauses, never kills`() {
        val service = TestInfrastructure.admin.service("FeeCharge")

        assertEquals("Pause", service.get("retry_policy").get("on_max_attempts").asString(), service.get("retry_policy").toString())
        assertNoHandlerKills(service)
    }

    @Test
    fun `the budget counts the first attempt, so a record that fails one time fewer than the budget still lands`() {
        val id = "txn_retry_budget_minus_one"
        records.failNextSave(id, BUDGET - 1)

        val post = Http.postJson("http://localhost:$port/transaction/fee", BRIEF_REQUEST.replace("txn_001", id))
        assertTrue(post.statusCode() in setOf(200, 202)) { "${post.statusCode()} ${post.body()}" }

        awaitRun(id, wanted = "completed", forbidden = "paused")
        assertEquals(BUDGET - 1, records.fired(id), "every armed fault fired, one per failed attempt")
        val fee = Http.get("http://localhost:$port/transaction/$id/fee")
        assertEquals(200, fee.statusCode(), fee.body())
    }

    /** A handler-level override would win over the service's policy: none may say Kill. */
    private fun assertNoHandlerKills(service: JsonNode) {
        service.get("handlers").values().forEach { handler ->
            val onMaxAttempts = handler.path("retry_policy").path("on_max_attempts")
            if (!onMaxAttempts.isMissingNode && !onMaxAttempts.isNull) {
                assertNotEquals("Kill", onMaxAttempts.asString(), "${service.get("name")}/${handler.get("name")}: $handler")
            }
        }
    }

    private fun awaitRun(id: String, wanted: String, forbidden: String) {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        var status: String? = null
        while (System.nanoTime() < deadline) {
            status = TestInfrastructure.admin.invocationStatus("FeeWorkflow", id, "run")
            if (status == wanted) return
            if (status == forbidden) fail<Unit>("the FeeWorkflow run for $id is $status, expected $wanted")
            Thread.sleep(100)
        }
        fail<Unit>("the FeeWorkflow run for $id is $status after 30 s, expected $wanted")
    }

    private companion object {
        /** `restate.components.FeeWorkflow.retry-policy.max-attempts` in `application-it.yml`. */
        const val BUDGET = 5

        val BRIEF_REQUEST = """
            {
              "transaction_id": "txn_001",
              "amount": 1000,
              "asset": "USD",
              "asset_type": "FIAT",
              "type": "Mobile Top Up",
              "state": "SETTLED - PENDING FEE",
              "created_at": "2023-08-30 15:42:17.610059"
            }
        """.trimIndent()
    }
}
