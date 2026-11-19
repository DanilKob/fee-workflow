package com.cashi.fee.domain

/** `FIAT` or `CRYPTO`, spelled exactly as the API accepts it (Q11). */
enum class AssetType { FIAT, CRYPTO }

/** A currency or token: its code, its asset type, and the scale its fees are rounded to (D9). */
data class Asset(val code: String, val type: AssetType, val scale: Int) {
    init {
        require(code.isNotBlank()) { "asset code must not be blank" }
        require(scale in 0..Money.MAX_SCALE) { "scale of $code must be within 0..${Money.MAX_SCALE}" }
    }
}

/**
 * The assets fees can be charged in, looked up by their exact code (Q11). Each code has one asset
 * type, so a request whose `asset_type` disagrees with it is refused (AC11,
 * [FeeCalculationService.checkRules]). A registry holds data, not logic, so it is no service (Q16).
 */
interface AssetRegistry {

    val assets: Collection<Asset>

    fun find(code: String): Asset?
}
