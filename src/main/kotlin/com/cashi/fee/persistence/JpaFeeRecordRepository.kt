package com.cashi.fee.persistence

import com.cashi.fee.domain.FeeRecord
import com.cashi.fee.domain.FeeRecordRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** Blocking JDBC: callers in suspend code must run it under `Dispatchers.IO`. */
@Component
class JpaFeeRecordRepository(private val repository: FeeRecordJpaRepository) : FeeRecordRepository {

    override fun find(transactionId: String): FeeRecord? = repository.findByIdOrNull(transactionId)?.toDomain()

    /** An assigned-id save merges, so saving the same record again is a no-op, not a duplicate key. */
    override fun save(record: FeeRecord) {
        repository.save(FeeRecordEntity.from(record))
    }
}
