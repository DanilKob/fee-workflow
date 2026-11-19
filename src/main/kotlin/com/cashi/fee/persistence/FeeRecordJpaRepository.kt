package com.cashi.fee.persistence

import org.springframework.data.jpa.repository.JpaRepository

/** Only [JpaFeeRecordRepository] uses it. */
interface FeeRecordJpaRepository : JpaRepository<FeeRecordEntity, String>
