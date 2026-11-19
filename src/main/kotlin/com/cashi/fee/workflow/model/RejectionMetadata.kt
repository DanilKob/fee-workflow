package com.cashi.fee.workflow.model

import com.cashi.fee.service.RejectionReason
import dev.restate.sdk.common.TerminalException

/**
 * How a [RejectionReason] travels on a Restate terminal failure: as the wire tag in its `reason`
 * metadata. The reader also accepts a `reason: ` message prefix, in case the metadata is lost.
 */
object RejectionMetadata {

    const val REASON_KEY: String = "reason"

    /** The metadata a tagged [TerminalException] carries. */
    fun of(reason: RejectionReason): Map<String, String> = mapOf(REASON_KEY to reason.wire)

    /** The failure's tag, from its metadata or else its message prefix. Null when untagged. */
    fun reasonOf(metadata: Map<String, String>?, message: String?): RejectionReason? {
        RejectionReason.fromWire(metadata?.get(REASON_KEY))?.let { return it }
        return RejectionReason.entries.firstOrNull { message?.startsWith("${it.wire}: ") == true }
    }

    fun reasonOf(failure: TerminalException): RejectionReason? = reasonOf(failure.metadata, failure.message)

    /** The failure's message without a tag prefix. */
    fun detailOf(message: String?): String {
        val text = message.orEmpty()
        val prefix = RejectionReason.entries.firstOrNull { text.startsWith("${it.wire}: ") } ?: return text
        return text.removePrefix("${prefix.wire}: ")
    }
}
