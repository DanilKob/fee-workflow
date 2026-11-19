package com.cashi.fee.domain

/**
 * The system of record (D4), as the workflow and the service see it. `persistence` implements it.
 * The workflow's `record` step is its only writer.
 */
interface FeeRecordRepository {

    fun find(transactionId: String): FeeRecord?

    /** Writes the record. Safe to replay: saving the same record again changes nothing. */
    fun save(record: FeeRecord)
}
