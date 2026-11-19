package com.cashi.fee.domain

/**
 * Startup validation of a [FeeSchedule] against the asset registry (D5, requirements §4):
 * `DomainConfig` runs it while it builds the schedule bean, so a schedule that could misprice stops
 * the application before it serves a request.
 *
 * It checks what a rule can't check alone: labels are unique, and each flat fee is for a
 * registered asset and is a valid [Money] in it ([Money.check]): at most its integer digits, and
 * no more significant decimals than that asset's scale (trailing zeros don't count, as for an
 * amount, Q13). What a rule can check alone (a percentage in [0, 1), no
 * negative flat fee, a description that isn't blank) already failed when the rule was built.
 *
 * Whether each fixed fee covers every allowed asset is `FeeScheduleContractTest`'s check, not this
 * one: an asset left out is refused per request (AC15), never priced with a default.
 */
interface FeeScheduleValidationService {

    /** Every problem of [schedule], in order; empty when it is valid. */
    fun problems(schedule: FeeSchedule): List<String>

    /** [schedule], once it has no problem; otherwise an [IllegalArgumentException] that lists them all. */
    fun requireValid(schedule: FeeSchedule): FeeSchedule
}
