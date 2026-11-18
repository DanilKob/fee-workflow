package com.cashi.fee.workflow

import dev.restate.client.Client
import dev.restate.client.kotlin.workflow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Walking-skeleton end-to-end check: the real Spring app (random HTTP port), a real Postgres and a
 * real Restate server. The app's Restate endpoint is registered with the server through the admin
 * API, then the workflow is called through the ingress with the app's own Restate [Client] bean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HelloWorkflowIT {

    @Autowired
    private lateinit var restateClient: Client

    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeAll
    fun registerDeployment() {
        // The Spring context (and its Restate endpoint) is up before @BeforeAll in PER_CLASS mode.
        val response = http.send(
            HttpRequest.newBuilder(URI.create("${restateAdminUrl()}/deployments"))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"uri":"http://host.testcontainers.internal:$SDK_PORT"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertTrue(response.statusCode() in 200..299) { "deployment registration failed: ${response.body()}" }
    }

    @Test
    fun `actuator health is UP`() {
        val response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$serverPort/actuator/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"status\":\"UP\"")) { response.body() }
    }

    @Test
    fun `workflow runs through the Restate ingress`() = runTest {
        val reply = restateClient.workflow<HelloWorkflow>("wf-1").run(HelloWorkflow.Greeting("Cashi"))
        assertEquals(HelloWorkflow.Reply("Hello, Cashi!"), reply)
    }

    companion object {
        /** Free host port for the app's Restate endpoint, exposed to containers before they start. */
        private val SDK_PORT: Int = ServerSocket(0).use { it.localPort }.also { Testcontainers.exposeHostPorts(it) }

        // Started here, not by the Testcontainers JUnit extension: with PER_CLASS lifecycle Spring
        // loads the context before that extension's beforeAll would start them. Ryuk removes them.
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine").apply { start() }

        @JvmStatic
        val restate: GenericContainer<*> =
            GenericContainer(System.getProperty("restate.image", "docker.restate.dev/restatedev/restate:1.8.1"))
                .withExposedPorts(8080, 9070)
                .withAccessToHost(true)
                .waitingFor(Wait.forHttp("/health").forPort(9070))
                .apply { start() }

        private fun restateAdminUrl() = "http://${restate.host}:${restate.getMappedPort(9070)}"

        @DynamicPropertySource
        @JvmStatic
        fun restateProperties(registry: DynamicPropertyRegistry) {
            registry.add("restate.sdk.http.port") { SDK_PORT }
            registry.add("restate.client.base-uri") { "http://${restate.host}:${restate.getMappedPort(8080)}" }
        }
    }
}
