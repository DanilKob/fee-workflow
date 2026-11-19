package com.cashi.fee.workflow

import com.cashi.fee.service.RejectionReason
import com.cashi.fee.workflow.model.Charge
import com.cashi.fee.workflow.model.ChargeRequest
import com.cashi.fee.workflow.model.RejectionMetadata
import dev.restate.sdk.annotation.Handler
import dev.restate.sdk.annotation.VirtualObject
import dev.restate.sdk.common.TerminalException
import dev.restate.sdk.kotlin.random
import dev.restate.sdk.kotlin.state
import dev.restate.sdk.kotlin.stateKey
import dev.restate.sdk.springboot.RestateComponent

/**
 * The charge itself (D2): a Virtual Object keyed by `transaction_id`, so Restate runs one charge
 * at a time per id and keeps it in state indefinitely, beyond the workflow's retention (AC19).
 *
 * - The first call mints the charge id and time, journaled, and stores them with the fee, asset
 *   and payload fingerprint (AC25). A zero fee is charged like any other (Q3).
 * - A repeat with the same fingerprint returns the stored charge unchanged, even when a rule
 *   change has moved the fee since (Q9).
 * - A repeat with a different fingerprint changes nothing and fails with a tagged 409.
 *
 * It is ingress-private (`restate.components.FeeCharge.ingress-private`): only other Restate
 * handlers, in production only [FeeWorkflow], can call it.
 */
@RestateComponent
@VirtualObject
class FeeCharge {

    @Handler
    suspend fun charge(request: ChargeRequest): Charge {
        val stored = state().get(CHARGE)
        if (stored != null) {
            if (stored.fingerprint != request.fingerprint) refuseOtherPayload()
            return stored
        }
        val charge = Charge(
            chargeId = random().nextUUID().toString(),
            fee = request.fee,
            asset = request.asset,
            fingerprint = request.fingerprint,
            chargedAt = restateNowUtc(),
        )
        state().set(CHARGE, charge)
        return charge
    }

    /** The fingerprint check: the one terminal failure FeeCharge raises. */
    private fun refuseOtherPayload(): Nothing =
        throw TerminalException(
            RejectionReason.PAYLOAD_CONFLICT.httpStatus,
            RejectionReason.PAYLOAD_CONFLICT_DETAIL,
            RejectionMetadata.of(RejectionReason.PAYLOAD_CONFLICT),
        )

    private companion object {
        val CHARGE = stateKey<Charge>("charge")
    }
}
