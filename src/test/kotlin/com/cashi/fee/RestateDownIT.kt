package com.cashi.fee

import com.cashi.fee.support.Http
import com.cashi.fee.support.Observations
import com.cashi.fee.support.TestInfrastructure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket

/**
 * With the Restate ingress unreachable (a closed port), `POST` answers a 503 that says nothing was
 * started, and nothing is charged or recorded.
 *
 * It has its own Spring context, so the closed port can't break the shared one. It also needs its
 * own SDK port, because the shared context holds [TestInfrastructure.sdkPort] for the whole JVM.
 * It reads the real Restate server only to show nothing was started there.
 *
 * Postgres is wired by datasource properties, never as the container bean of
 * [TestInfrastructure.Connections]. Spring Boot stops a container bean when its context closes,
 * which would stop the Postgres every other IT shares.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@ContextConfiguration(initializers = [RestateDownIT.ClosedIngress::class])
@DirtiesContext
class RestateDownIT {

    @Value("\${local.server.port}")
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val json = JsonMapper.builder().build()

    @Test
    fun `with Restate unreachable, POST is a 503 problem that says nothing was started, and nothing is charged or recorded`() {
        val id = "txn_restate_down"

        val response = Http.postJson("http://localhost:$port/transaction/fee", BRIEF_REQUEST.replace("txn_001", id))

        assertEquals(503, response.statusCode(), response.body())
        assertEquals("application/problem+json", response.headers().firstValue("content-type").orElse(null))
        val problem = json.readTree(response.body())
        assertEquals("/problems/unavailable", problem.get("type").asString())
        assertEquals(503, problem.get("status").asInt())
        assertEquals("Restate unreachable; nothing was started; resend the same request", problem.get("detail").asString())
        assertEquals("5", response.headers().firstValue("retry-after").orElse(null))
        assertEquals(emptyList<String>(), Observations.invocations(id), "nothing may have reached Restate")
        assertNull(Observations.charge(id), "nothing may be charged")
        assertEquals(0, Observations.feeRecords(jdbc, id).size, "nothing may be recorded")
    }

    @Test
    fun `with Restate unreachable, GET of an unrecorded id is a 503 problem, not a 404`() {
        val response = Http.get("http://localhost:$port/transaction/txn_restate_down_get/fee")

        assertEquals(503, response.statusCode(), response.body())
        assertEquals("application/problem+json", response.headers().firstValue("content-type").orElse(null))
        assertEquals("fee workflow status unavailable; resending the same request is safe", json.readTree(response.body()).get("detail").asString())
    }

    /**
     * Points the Restate client at a port nothing listens on, so every connection is refused.
     * Nothing registers the app's endpoint: Restate is "down" here.
     */
    class ClosedIngress : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(context: ConfigurableApplicationContext) {
            // Two ports free a moment ago, held open together so they differ; once the sockets
            // close, connecting to the second is refused.
            val (sdkPort, closedPort) = ServerSocket(0).use { sdk -> ServerSocket(0).use { closed -> sdk.localPort to closed.localPort } }
            val postgres = TestInfrastructure.postgres
            TestPropertyValues.of(
                "restate.sdk.http.port=$sdkPort",
                "restate.client.base-uri=http://127.0.0.1:$closedPort",
                "spring.datasource.url=${postgres.jdbcUrl}",
                "spring.datasource.username=${postgres.username}",
                "spring.datasource.password=${postgres.password}",
            ).applyTo(context.environment)
        }
    }

    private companion object {
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
