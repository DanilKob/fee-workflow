package com.cashi.fee.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The shipped asset registry, and the bounds every asset must respect. */
class DefaultAssetRegistryTest {

    private val registry = DefaultAssetRegistry()

    @Test
    fun `the shipped registry is exactly the AC9 table`() {
        assertEquals(
            mapOf(
                "USD" to (AssetType.FIAT to 2),
                "EUR" to (AssetType.FIAT to 2),
                "GBP" to (AssetType.FIAT to 2),
                "JPY" to (AssetType.FIAT to 0),
                "BTC" to (AssetType.CRYPTO to 8),
                "ETH" to (AssetType.CRYPTO to 18),
                "USDC" to (AssetType.CRYPTO to 6),
                "USDT" to (AssetType.CRYPTO to 6),
            ),
            registry.assets.associate { it.code to (it.type to it.scale) },
        )
    }

    /** A fee is stored in `numeric(38,18)`, so no asset may round to more decimals than it keeps. */
    @Test
    fun `every shipped scale fits the stored money columns`() {
        registry.assets.forEach { asset ->
            assertTrue(asset.scale in 0..Money.MAX_SCALE) { "${asset.code} has scale ${asset.scale}, over ${Money.MAX_SCALE}" }
        }
    }

    @Test
    fun `an asset with a scale the money columns can't hold, or a duplicate code, is refused`() {
        assertThrows<IllegalArgumentException> { Asset("XYZ", AssetType.CRYPTO, Money.MAX_SCALE + 1) }
        assertThrows<IllegalArgumentException> { Asset("XYZ", AssetType.CRYPTO, -1) }
        assertThrows<IllegalArgumentException> {
            DefaultAssetRegistry(listOf(Asset("USD", AssetType.FIAT, 2), Asset("USD", AssetType.CRYPTO, 6)))
        }
    }

    @Test
    fun `codes match exactly (Q11)`() {
        assertEquals(Asset("BTC", AssetType.CRYPTO, 8), registry.find("BTC"))
        assertNull(registry.find("btc"))
        assertNull(registry.find(" BTC"))
        assertNull(registry.find("XYZ"))
    }
}
