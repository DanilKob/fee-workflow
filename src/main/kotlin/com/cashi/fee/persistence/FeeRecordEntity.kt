package com.cashi.fee.persistence

import com.cashi.fee.domain.AssetType
import com.cashi.fee.domain.FeeRecord
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * One `fee_record` row (V1 migration). A plain class with an assigned id and deliberately no
 * `@Version` and no `Persistable`: `save` then merges, so the `record` step replays cleanly after
 * a commit whose journal entry was lost. Either one would turn that replay into a duplicate-key
 * loop that ends paused.
 *
 * The `LocalDateTime` fields carry no zone: every value is UTC (D8).
 */
@Entity
@Table(name = "fee_record")
class FeeRecordEntity(
    @Id
    @Column(name = "transaction_id", length = 64)
    var transactionId: String,
    @Column(name = "amount", precision = 38, scale = 18)
    var amount: BigDecimal,
    @Column(name = "asset")
    var asset: String,
    @Column(name = "asset_type")
    var assetType: String,
    @Column(name = "type")
    var type: String,
    @Column(name = "fee", precision = 38, scale = 18)
    var fee: BigDecimal,
    @Column(name = "rate", precision = 38, scale = 18)
    var rate: BigDecimal,
    @Column(name = "description")
    var description: String,
    @Column(name = "rule_id")
    var ruleId: String,
    @Column(name = "rule_version")
    var ruleVersion: Int,
    @Column(name = "created_at")
    var createdAt: LocalDateTime,
    @Column(name = "charge_id")
    var chargeId: UUID,
    @Column(name = "charged_at")
    var chargedAt: LocalDateTime,
    @Column(name = "state")
    var state: String,
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fingerprint", length = 64)
    var fingerprint: String,
    @Column(name = "recorded_at")
    var recordedAt: LocalDateTime,
) {
    fun toDomain(): FeeRecord =
        FeeRecord(
            transactionId = transactionId,
            amount = amount,
            asset = asset,
            assetType = AssetType.valueOf(assetType),
            type = type,
            fee = fee,
            rate = rate,
            description = description,
            ruleId = ruleId,
            ruleVersion = ruleVersion,
            createdAt = createdAt,
            chargeId = chargeId,
            chargedAt = chargedAt,
            recordedAt = recordedAt,
            fingerprint = fingerprint,
        )

    companion object {
        fun from(record: FeeRecord): FeeRecordEntity =
            FeeRecordEntity(
                transactionId = record.transactionId,
                amount = record.amount,
                asset = record.asset,
                assetType = record.assetType.name,
                type = record.type,
                fee = record.fee,
                rate = record.rate,
                description = record.description,
                ruleId = record.ruleId,
                ruleVersion = record.ruleVersion,
                createdAt = record.createdAt,
                chargeId = record.chargeId,
                chargedAt = record.chargedAt,
                state = FeeRecord.SETTLED,
                fingerprint = record.fingerprint,
                recordedAt = record.recordedAt,
            )
    }
}
