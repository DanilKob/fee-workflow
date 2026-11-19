package com.cashi.fee.support

import com.cashi.fee.workflow.FeeCharge
import com.cashi.fee.workflow.model.BigDecimalAsString
import com.cashi.fee.workflow.model.Charge
import com.cashi.fee.workflow.model.ChargeRequest
import dev.restate.sdk.annotation.Handler
import dev.restate.sdk.annotation.Service
import dev.restate.sdk.kotlin.virtualObject
import dev.restate.sdk.springboot.RestateComponent
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** What to charge FeeCharge with, keyed by [transactionId]. */
@Serializable
data class SeedCharge(
    val transactionId: String,
    @Serializable(with = BigDecimalAsString::class) val fee: BigDecimal,
    val asset: String,
    val fingerprint: String,
)

/**
 * Test-only public Restate service that charges the ingress-private FeeCharge directly, with any
 * fee, asset and fingerprint. Scenarios use it to repeat a charge and to plant an orphan charge.
 * It lives in `src/test`, so only the test context registers it.
 */
@RestateComponent
@Service
class FeeChargeSeeder {

    @Handler
    suspend fun seed(request: SeedCharge): Charge =
        virtualObject<FeeCharge>(request.transactionId).charge(ChargeRequest(request.fee, request.asset, request.fingerprint))
}
