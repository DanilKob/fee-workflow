package com.cashi.fee.api

import com.cashi.fee.service.FeeProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.webmvc.autoconfigure.WebMvcProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/** The application refuses to start when `fees.sync-wait` could outlast the async request timeout (AC28). */
class SyncWaitCheckTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FeeProperties::class, WebMvcProperties::class)
    @Import(SyncWaitCheck::class)
    class Config

    private val runner = ApplicationContextRunner().withUserConfiguration(Config::class.java)

    @ParameterizedTest(name = "sync-wait {0}, request-timeout {1}")
    @CsvSource(
        "10s, 30s", // application.yml
        "1s, 30s", // the IT profile
        "1s, 6001ms", // just inside the grace
    )
    fun `a sync-wait that leaves the grace before the request timeout starts`(syncWait: String, requestTimeout: String) {
        runner.withPropertyValues("fees.sync-wait=$syncWait", "spring.mvc.async.request-timeout=$requestTimeout").run { context ->
            assertNull(context.startupFailure) { "the context failed: ${context.startupFailure}" }
            assertEquals(1, context.getBeansOfType(SyncWaitCheck::class.java).size)
        }
    }

    @ParameterizedTest(name = "sync-wait {0}, request-timeout {1}")
    @CsvSource(
        "40s, 30s", // the wait outlasts the timeout
        "25s, 30s", // the wait plus the grace reaches the timeout
        "1s, 6s",
        "10s, 5s",
    )
    fun `a sync-wait that could outlast the request timeout stops the application`(syncWait: String, requestTimeout: String) {
        runner.withPropertyValues("fees.sync-wait=$syncWait", "spring.mvc.async.request-timeout=$requestTimeout").run { context ->
            assertStartupRefused(context.startupFailure, "fees.sync-wait=$syncWait, request-timeout=$requestTimeout")
        }
    }

    @Test
    fun `an unset request timeout can't be checked, and stops the application`() {
        runner.withPropertyValues("fees.sync-wait=1s").run { context ->
            assertStartupRefused(context.startupFailure, "no request timeout")
        }
    }

    private fun assertStartupRefused(failure: Throwable?, case: String) {
        assertNotNull(failure) { "$case was accepted" }
        val refusal = generateSequence(failure) { it.cause }.filterIsInstance<IllegalStateException>().firstOrNull()
        assertNotNull(refusal) { "$case: $failure" }
        assertTrue(refusal!!.message!!.contains("spring.mvc.async.request-timeout")) { refusal.message }
    }
}
