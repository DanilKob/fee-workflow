package com.cashi.fee.workflow.model

import com.cashi.fee.service.RejectionReason
import dev.restate.sdk.common.TerminalException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RejectionMetadataTest {

    @Test
    fun `a tagged failure carries the wire tag under the reason key`() {
        assertEquals(mapOf("reason" to "payload_conflict"), RejectionMetadata.of(RejectionReason.PAYLOAD_CONFLICT))
        assertEquals(mapOf("reason" to "rule_rejected"), RejectionMetadata.of(RejectionReason.RULE_REJECTED))
    }

    @Test
    fun `reads the tag from the reason metadata`() {
        val failure = TerminalException(
            409,
            RejectionReason.PAYLOAD_CONFLICT_DETAIL,
            RejectionMetadata.of(RejectionReason.PAYLOAD_CONFLICT),
        )

        assertEquals(RejectionReason.PAYLOAD_CONFLICT, RejectionMetadata.reasonOf(failure))
    }

    @Test
    fun `falls back to a message prefix`() {
        assertEquals(RejectionReason.RULE_REJECTED, RejectionMetadata.reasonOf(null, "rule_rejected: type: unknown type"))
        assertEquals("type: unknown type", RejectionMetadata.detailOf("rule_rejected: type: unknown type"))
        assertEquals("plain detail", RejectionMetadata.detailOf("plain detail"))
    }

    @Test
    fun `an untagged failure, such as Restate's own cancel, has no tag`() {
        assertNull(RejectionMetadata.reasonOf(TerminalException(409, "cancelled")))
        assertNull(RejectionMetadata.reasonOf(mapOf("reason" to "something_else"), "something_else: x"))
    }
}
