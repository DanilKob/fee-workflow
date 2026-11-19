package com.cashi.fee.domain

/**
 * Checks a [FeeSchedule] against the asset registry at startup, so a schedule that could misprice
 * stops the application before it serves a request.
 *
 * It checks what a single rule can't: labels are unique, and each flat fee is a valid [Money] in a
 * registered asset. Each rule checks the rest itself when it is built.
 *
 * Whether a fixed fee covers every allowed asset is left to `FeeScheduleContractTest`. A missing
 * asset is refused per request, never priced with a default.
 */
interface FeeScheduleValidationService {

    /** Every problem of [schedule], in order; empty when it is valid. */
    fun problems(schedule: FeeSchedule): List<String>

    /** Returns [schedule] if valid, else throws an [IllegalArgumentException] listing the problems. */
    fun requireValid(schedule: FeeSchedule): FeeSchedule
}
