package com.cashi.fee.support

import com.cashi.fee.domain.DefaultFeeSchedule
import com.cashi.fee.domain.FeeRule
import com.cashi.fee.domain.FeeSchedule
import com.cashi.fee.domain.FeeScheduleValidationService
import com.cashi.fee.domain.TransactionType
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference

/**
 * Test-only [FeeSchedule] whose rules a scenario can swap mid-flight, as a redeploy with an edited
 * schedule would. It is `@Primary`, so the validator and the workflow's `calculate` step both see it.
 *
 * It holds the shipped rules until a scenario calls [switchTo], which validates the new rules like
 * the shipped ones. Cucumber calls [reset] before and after every scenario, so no other test sees
 * the switched rules.
 */
@Component
@Primary
class SwitchableFeeSchedule(private val validation: FeeScheduleValidationService) : FeeSchedule {

    private val shipped: FeeSchedule = validation.requireValid(DefaultFeeSchedule())
    private val current = AtomicReference(shipped)

    override val types: List<TransactionType> get() = current.get().types

    /** From now on, every lookup sees [schedule]'s rules. */
    fun switchTo(schedule: FeeSchedule) {
        current.set(validation.requireValid(schedule))
    }

    /** Back to the shipped rules. */
    fun reset() {
        current.set(shipped)
    }

    companion object {
        /** The shipped schedule with Mobile Top Up repriced at [rate], as rule [version]. */
        fun mobileTopUpAt(rate: BigDecimal, version: Int): FeeSchedule {
            val types = DefaultFeeSchedule().types.map { type ->
                if (type.label == MOBILE_TOP_UP) type.copy(rule = FeeRule.Percentage(rate), version = version) else type
            }
            return object : FeeSchedule {
                override val types: List<TransactionType> = types
            }
        }

        const val MOBILE_TOP_UP = "Mobile Top Up"
    }
}
