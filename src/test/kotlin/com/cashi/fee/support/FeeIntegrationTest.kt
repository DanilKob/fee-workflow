package com.cashi.fee.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration

/**
 * The one Spring context every IT shares: the real app on a random port, the `it` profile, and
 * [TestInfrastructure]'s Postgres (a `@ServiceConnection`) and Restate. Every IT class (and the Cucumber configuration)
 * carries exactly this annotation and nothing that changes the context, so Spring's test context
 * cache builds the context once per JVM.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Import(TestInfrastructure.Connections::class)
@ContextConfiguration(initializers = [TestInfrastructure.Initializer::class])
annotation class FeeIntegrationTest
