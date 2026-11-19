package com.cashi.fee.config

import com.cashi.fee.domain.AssetRegistry
import com.cashi.fee.domain.DefaultAssetRegistry
import com.cashi.fee.domain.DefaultFeeSchedule
import com.cashi.fee.domain.FeeCalculationService
import com.cashi.fee.domain.FeeCalculationServiceImpl
import com.cashi.fee.domain.FeeSchedule
import com.cashi.fee.domain.FeeScheduleValidationService
import com.cashi.fee.domain.FeeScheduleValidationServiceImpl
import com.cashi.fee.domain.PayloadFingerprintService
import com.cashi.fee.domain.PayloadFingerprintServiceImpl
import com.cashi.fee.domain.TransactionFactory
import com.cashi.fee.domain.TransactionFactoryImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Builds every domain bean here, so the domain itself needs no Spring annotation. The schedule is
 * validated while its bean is built, so a malformed schedule stops the application at startup.
 */
@Configuration(proxyBeanMethods = false)
class DomainConfig {

    @Bean
    fun assetRegistry(): AssetRegistry = DefaultAssetRegistry()

    @Bean
    fun feeScheduleValidationService(assetRegistry: AssetRegistry): FeeScheduleValidationService =
        FeeScheduleValidationServiceImpl(assetRegistry)

    @Bean
    fun feeSchedule(feeScheduleValidationService: FeeScheduleValidationService): FeeSchedule =
        feeScheduleValidationService.requireValid(DefaultFeeSchedule())

    @Bean
    fun feeCalculationService(assetRegistry: AssetRegistry, feeSchedule: FeeSchedule): FeeCalculationService =
        FeeCalculationServiceImpl(assetRegistry, feeSchedule)

    @Bean
    fun payloadFingerprintService(): PayloadFingerprintService = PayloadFingerprintServiceImpl()

    @Bean
    fun transactionFactory(assetRegistry: AssetRegistry, feeCalculationService: FeeCalculationService): TransactionFactory =
        TransactionFactoryImpl(assetRegistry, feeCalculationService)
}
