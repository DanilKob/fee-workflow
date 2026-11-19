package com.cashi.fee

import dev.restate.sdk.springboot.EnableRestate
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/** `@ConfigurationPropertiesScan` binds and validates each package's settings, such as `fees.*`. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableRestate
class FeeWorkflowApplication

fun main(args: Array<String>) {
    runApplication<FeeWorkflowApplication>(*args)
}
