package com.cashi.fee.workflow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HelloWorkflowTest {

    @Test
    fun `greets by name`() {
        assertEquals("Hello, Cashi!", HelloWorkflow.greet("Cashi"))
    }
}
