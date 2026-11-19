package com.cashi.fee.domain

/** `FIAT` or `CRYPTO`, matched case-sensitively against the request. */
enum class AssetType { FIAT, CRYPTO }

/** A currency or token: its code, its asset type, and the scale its fees are rounded to. */
data class Asset(val code: String, val type: AssetType, val scale: Int) {
    init {
        require(code.isNotBlank()) { "asset code must not be blank" }
        require(scale in 0..Money.MAX_SCALE) { "scale of $code must be within 0..${Money.MAX_SCALE}" }
    }
}

/**
 * The assets fees can be charged in, looked up by exact code. Each code has one asset type, so a
 * request whose `asset_type` disagrees is refused. It holds data, not logic, so it isn't a service.
 */
interface AssetRegistry {

    val assets: Collection<Asset>

    fun find(code: String): Asset?
}
