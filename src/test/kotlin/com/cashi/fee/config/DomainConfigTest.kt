package com.cashi.fee.config

import com.cashi.fee.domain.Asset
import com.cashi.fee.domain.AssetType
import com.cashi.fee.domain.DefaultAssetRegistry
import com.cashi.fee.domain.DefaultFeeSchedule
import com.cashi.fee.domain.FeeCalculation
import com.cashi.fee.domain.Money
import com.cashi.fee.domain.TransactionCreation
import com.cashi.fee.domain.TransactionInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * Every domain bean is built here, each after the beans it needs. The schedule is validated while
 * its bean is built, so a schedule that could misprice stops startup.
 */
class DomainConfigTest {

    private val config = DomainConfig()

    @Test
    fun `the schedule bean is the shipped schedule, validated against the shipped assets`() {
        val validation = config.feeScheduleValidationService(config.assetRegistry())

        assertEquals(DefaultFeeSchedule().types, config.feeSchedule(validation).types)
    }

    @Test
    fun `a schedule that doesn't fit the asset registry fails while its bean is built`() {
        // Bank Transfer has a fixed fee in JPY, which this registry doesn't hold.
        val withoutJpy = DefaultAssetRegistry(listOf(Asset("USD", AssetType.FIAT, 2), Asset("EUR", AssetType.FIAT, 2), Asset("GBP", AssetType.FIAT, 2)))

        val failure = assertThrows<IllegalArgumentException> { config.feeSchedule(config.feeScheduleValidationService(withoutJpy)) }

        assertTrue("Bank Transfer has a fixed fee for JPY, which is not a registered asset" in failure.message!!) { failure.message }
    }

    @Test
    fun `the calculation bean prices with the registry and the validated schedule`() {
        val assets = config.assetRegistry()
        val calculator = config.feeCalculationService(assets, config.feeSchedule(config.feeScheduleValidationService(assets)))

        val quoted = assertInstanceOf(FeeCalculation.Quoted::class.java, calculator.quote(Money(BigDecimal("1000"), assets.find("USD")!!), "Mobile Top Up"))

        assertEquals(BigDecimal("1.50"), quoted.quote.fee.amount)
    }

    @Test
    fun `the transaction factory checks with the registry and the calculation service's rules`() {
        val assets = config.assetRegistry()
        val factory = config.transactionFactory(assets, config.feeCalculationService(assets, config.feeSchedule(config.feeScheduleValidationService(assets))))

        val sample = TransactionInput("txn_001", BigDecimal("1000"), "USD", "FIAT", "Mobile Top Up", "SETTLED - PENDING FEE", "2023-08-30 15:42:17.610059")
        val created = assertInstanceOf(TransactionCreation.Created::class.java, factory.create(sample))
        val refused = assertInstanceOf(TransactionCreation.Invalid::class.java, factory.create(sample.copy(type = "Bank Transfer", asset = "BTC", assetType = "CRYPTO")))

        assertEquals(Money(BigDecimal("1000"), assets.find("USD")!!), created.transaction.amount)
        assertEquals(listOf("Bank Transfer does not allow CRYPTO; allowed asset types: FIAT"), refused.violations.map { it.message })
    }
}
