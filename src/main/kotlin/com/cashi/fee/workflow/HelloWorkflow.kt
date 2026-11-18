package com.cashi.fee.workflow

import dev.restate.sdk.annotation.Workflow
import dev.restate.sdk.kotlin.runBlock
import dev.restate.sdk.springboot.RestateComponent
import kotlinx.serialization.Serializable

/**
 * Walking-skeleton workflow: proves registration with the Restate server, a journaled durable
 * step, kotlinx-serialized payloads and a call through the ingress. Replaced by the fee workflow.
 */
@RestateComponent
@Workflow
class HelloWorkflow {

    @Serializable
    data class Greeting(val name: String)

    @Serializable
    data class Reply(val message: String)

    @Workflow
    suspend fun run(greeting: Greeting): Reply = runBlock("greet") { Reply(greet(greeting.name)) }

    companion object {
        fun greet(name: String): String = "Hello, $name!"
    }
}
