package com.cashi.fee.support

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.ServerSocket

/**
 * The real Postgres and the real Restate server every IT runs against, started once per JVM, and
 * the wiring of the one shared Spring context to them (see [FeeIntegrationTest]).
 *
 * Postgres reaches the context through [Connections]' `@ServiceConnection` bean; Restate has no
 * service connection, so [Initializer] sets its two properties. The app's Restate endpoint listens
 * on [sdkPort], a free host port exposed to the containers before any of them starts. Once the
 * context is up, its endpoint is registered with the Restate server through the admin API.
 */
object TestInfrastructure {

    /** Host port of the app's Restate endpoint, exposed to containers before they start. */
    val sdkPort: Int = ServerSocket(0).use { it.localPort }.also { Testcontainers.exposeHostPorts(it) }

    // Started here, not by the Testcontainers JUnit extension, so that they outlive any one test
    // class and serve the shared context. Ryuk removes them when the JVM exits.
    val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine").apply { start() }

    val restate: GenericContainer<*> =
        GenericContainer(System.getProperty("restate.image", "docker.restate.dev/restatedev/restate:1.8.1"))
            .withExposedPorts(8080, 9070)
            .withAccessToHost(true)
            .waitingFor(Wait.forHttp("/health").forPort(9070))
            .apply { start() }

    val ingressUrl: String get() = "http://${restate.host}:${restate.getMappedPort(8080)}"

    val admin: RestateAdmin by lazy { RestateAdmin("http://${restate.host}:${restate.getMappedPort(9070)}") }

    /**
     * The JVM-wide Postgres as a service connection. The bean is the singleton itself, so every
     * context sees the same container; `destroyMethod = ""` keeps a closing context from stopping
     * it (Ryuk does, when the JVM exits).
     */
    @TestConfiguration(proxyBeanMethods = false)
    class Connections {
        @Bean(destroyMethod = "")
        @ServiceConnection
        fun postgres(): PostgreSQLContainer = TestInfrastructure.postgres
    }

    /** Points the context at the Restate server and registers its Restate endpoint once it is ready. */
    class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(context: ConfigurableApplicationContext) {
            TestPropertyValues.of(
                "restate.sdk.http.port=$sdkPort",
                "restate.client.base-uri=$ingressUrl",
            ).applyTo(context.environment)

            context.addApplicationListener(
                object : ApplicationListener<ApplicationReadyEvent> {
                    override fun onApplicationEvent(event: ApplicationReadyEvent) {
                        admin.registerDeployment("http://host.testcontainers.internal:$sdkPort")
                    }
                },
            )
        }
    }
}
