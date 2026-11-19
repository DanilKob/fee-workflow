package com.cashi.fee.support

import com.cashi.fee.domain.AssetRegistry
import com.cashi.fee.domain.DefaultAssetRegistry
import com.cashi.fee.domain.DefaultFeeSchedule
import com.cashi.fee.domain.FeeCalculationServiceImpl
import com.cashi.fee.domain.Transaction
import com.cashi.fee.domain.TransactionCreation
import com.cashi.fee.domain.TransactionFactory
import com.cashi.fee.domain.TransactionFactoryImpl
import com.cashi.fee.domain.TransactionInput
import java.math.BigDecimal

/**
 * Transactions for tests, built the one way production builds them: through [TransactionFactoryImpl]
 * over the shipped asset registry and fee schedule. The defaults are the brief's sample.
 *
 * A test that needs a transaction the factory refuses (an input only a direct workflow call could
 * carry) copies a valid one: `Transaction.copy` is internal to the module, and the tests share it.
 */
object TestTransactions {

    val ASSETS: AssetRegistry = DefaultAssetRegistry()

    val FACTORY: TransactionFactory = TransactionFactoryImpl(ASSETS, FeeCalculationServiceImpl(ASSETS, DefaultFeeSchedule()))

    fun transaction(
        transactionId: String = "txn_001",
        amount: BigDecimal = BigDecimal("1000"),
        asset: String = "USD",
        assetType: String = "FIAT",
        type: String = "Mobile Top Up",
        state: String = Transaction.ELIGIBLE_STATE,
        createdAt: String = "2023-08-30 15:42:17.610059",
    ): Transaction =
        when (val creation = FACTORY.create(TransactionInput(transactionId, amount, asset, assetType, type, state, createdAt))) {
            is TransactionCreation.Created -> creation.transaction
            is TransactionCreation.Invalid -> error("the factory refused this test transaction: ${creation.violations}")
        }
}
