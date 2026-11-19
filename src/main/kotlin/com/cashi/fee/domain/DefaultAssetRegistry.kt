package com.cashi.fee.domain

/**
 * The registry this service ships: the AC9 table, fiat and crypto (D-assets). A test may hand it
 * other [assets]; their codes must be unique.
 */
class DefaultAssetRegistry(assets: Collection<Asset> = SHIPPED) : AssetRegistry {

    private val byCode: Map<String, Asset> = assets.associateBy { it.code }

    init {
        require(byCode.size == assets.size) { "asset codes must be unique" }
    }

    override val assets: Collection<Asset> get() = byCode.values

    override fun find(code: String): Asset? = byCode[code]

    private companion object {
        val SHIPPED: List<Asset> = listOf(
            Asset("USD", AssetType.FIAT, 2),
            Asset("EUR", AssetType.FIAT, 2),
            Asset("GBP", AssetType.FIAT, 2),
            Asset("JPY", AssetType.FIAT, 0),
            Asset("BTC", AssetType.CRYPTO, 8),
            Asset("ETH", AssetType.CRYPTO, 18),
            Asset("USDC", AssetType.CRYPTO, 6),
            Asset("USDT", AssetType.CRYPTO, 6),
        )
    }
}
