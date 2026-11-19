package com.cashi.fee.domain

class FeeScheduleValidationServiceImpl(private val assets: AssetRegistry) : FeeScheduleValidationService {

    override fun problems(schedule: FeeSchedule): List<String> {
        val duplicates = schedule.types.groupBy { it.label }.filterValues { it.size > 1 }.keys
        val duplicateProblems = duplicates.map { "the transaction type label \"$it\" is registered more than once" }
        return duplicateProblems + schedule.types.flatMap(::ruleProblems)
    }

    override fun requireValid(schedule: FeeSchedule): FeeSchedule {
        val problems = problems(schedule)
        require(problems.isEmpty()) { "the fee schedule is invalid: ${problems.joinToString("; ")}" }
        return schedule
    }

    private fun ruleProblems(type: TransactionType): List<String> =
        when (val rule = type.rule) {
            is FeeRule.Fixed -> rule.perAsset.mapNotNull { (code, fee) ->
                val asset = assets.find(code)
                when (asset) {
                    null -> "${type.label} has a fixed fee for $code, which is not a registered asset"
                    // The fee is charged as Money in its asset, so it must fit that asset's bounds.
                    else -> Money.check(fee, asset)?.let { "${type.label}'s fixed fee of ${fee.toPlainString()} $code ${it.message}" }
                }
            }
            // These check themselves when built, and need no asset.
            is FeeRule.Percentage, is FeeRule.NoFee -> emptyList()
        }
}
