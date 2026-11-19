package com.cashi.fee.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RejectionReasonTest {

    @Test
    fun `the wire tags and statuses never change`() {
        assertEquals(
            listOf("payload_conflict" to 409, "rule_rejected" to 400),
            RejectionReason.entries.map { it.wire to it.httpStatus },
        )
    }

    @Test
    fun `a wire tag reads back as its reason, and anything else as none`() {
        RejectionReason.entries.forEach { assertEquals(it, RejectionReason.fromWire(it.wire)) }
        assertNull(RejectionReason.fromWire("something_else"))
        assertNull(RejectionReason.fromWire("PAYLOAD_CONFLICT"))
        assertNull(RejectionReason.fromWire(null))
    }
}
