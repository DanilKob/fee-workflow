package com.cashi.fee

import dev.restate.sdk.springboot.EnableRestate
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableRestate
class FeeWorkflowApplication

fun main(args: Array<String>) {
    runApplication<FeeWorkflowApplication>(*args)
}
