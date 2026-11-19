package com.cashi.fee.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.validation.BindValidationException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.time.Duration

/** `fees.*` binds as configured and refuses a value that can't be a wait. */
class FeePropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FeeProperties::class)
    class Config

    private val runner = ApplicationContextRunner().withUserConfiguration(Config::class.java)

    private fun syncWaitFor(vararg properties: String): Duration? {
        var bound: Duration? = null
        runner.withPropertyValues(*properties).run { context ->
            assertEquals(null, context.startupFailure) { "the context failed: ${context.startupFailure}" }
            bound = context.getBean(FeeProperties::class.java).syncWait
        }
        return bound
    }

    @Test
    fun `sync-wait defaults to 10 seconds (D11)`() {
        assertEquals(Duration.ofSeconds(10), syncWaitFor())
    }

    @Test
    fun `sync-wait binds Spring's duration forms`() {
        assertEquals(Duration.ofSeconds(1), syncWaitFor("fees.sync-wait=1s"))
        assertEquals(Duration.ofMillis(1500), syncWaitFor("fees.sync-wait=1500ms"))
        assertEquals(Duration.ofSeconds(10), syncWaitFor("fees.sync-wait=PT10S"))
    }

    @Test
    fun `a zero or negative sync-wait stops the application`() {
        listOf("0s", "-1s", "0ms").forEach { value ->
            runner.withPropertyValues("fees.sync-wait=$value").run { context ->
                val failure: Throwable? = context.startupFailure
                assertNotNull(failure) { "fees.sync-wait=$value was accepted" }
                assertTrue(generateSequence(failure) { it.cause }.any { it is BindValidationException }) { "fees.sync-wait=$value: $failure" }
            }
        }
    }

    /** A blank value (an empty env var) falls back to the default: the wait is never missing. */
    @Test
    fun `a blank sync-wait keeps the default`() {
        assertEquals(Duration.ofSeconds(10), syncWaitFor("fees.sync-wait="))
    }

    @Test
    fun `a sync-wait that isn't a duration stops the application`() {
        runner.withPropertyValues("fees.sync-wait=soon").run { context ->
            assertNotNull(context.startupFailure) { "fees.sync-wait=soon was accepted" }
        }
    }
}
