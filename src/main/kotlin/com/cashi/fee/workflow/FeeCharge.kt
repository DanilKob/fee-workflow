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
 * The charge itself, as a Virtual Object keyed by `transaction_id`. Restate runs one call at a time
 * per id and keeps the state indefinitely, beyond the workflow's retention.
 *
 * - The first call mints the charge id and time, journaled, and stores them with the fee, asset
 *   and fingerprint. A zero fee is charged like any other.
 * - A repeat with the same fingerprint returns the stored charge, even if a rule change has moved
 *   the fee since.
 * - A repeat with a different fingerprint changes nothing and fails with a tagged 409.
 *
 * It is ingress-private, so only other Restate handlers can call it: in production, only
 * [FeeWorkflow].
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

    /** The only terminal failure FeeCharge raises. It changes nothing. */
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
