package com.cashi.fee.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** Spring Data access to `fee_record`. Only [JpaFeeRecordRepository] uses it. */
interface FeeRecordJpaRepository : JpaRepository<FeeRecordEntity, String>
