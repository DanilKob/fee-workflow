package com.cashi.fee.service

import com.cashi.fee.service.FeeWorkflowGateway.GatewayFailure
import com.cashi.fee.service.GatewayFailureMappingService.Outcome
import org.springframework.stereotype.Service

/** Plan §3 step 4's failure table, case by case. */
@Service
class GatewayFailureMappingServiceImpl : GatewayFailureMappingService {

    override fun outcomeOf(phase: GatewayPhase, failure: GatewayFailure, payloadChecked: Boolean): Outcome =
        when (phase) {
            GatewayPhase.SEND -> Outcome.Unavailable(
                when (failure) {
                    GatewayFailure.Unreachable -> Unavailability.NOTHING_STARTED
                    GatewayFailure.OutcomeUnknown -> Unavailability.OUTCOME_UNKNOWN
                    is GatewayFailure.Status -> Unavailability.SEND_REFUSED
                },
            )
            GatewayPhase.STATUS -> Outcome.Unavailable(Unavailability.STATUS_UNAVAILABLE)
            GatewayPhase.AWAIT_OUTPUT -> when (failure) {
                GatewayFailure.Unreachable, GatewayFailure.OutcomeUnknown ->
                    if (payloadChecked) Outcome.Processing else Outcome.RereadStatus
                is GatewayFailure.Status -> Outcome.RunFailed
            }
            GatewayPhase.RECORD_LOOKUP -> recordLookupFailed()
        }

    override fun recordLookupFailed(): Outcome.Unavailable = Outcome.Unavailable(Unavailability.RECORDS_UNAVAILABLE)
}
