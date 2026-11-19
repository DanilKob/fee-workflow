package com.cashi.fee.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PayloadFingerprintServiceImplTest {

    private val fingerprints = PayloadFingerprintServiceImpl()

    private val usd = Asset("USD", AssetType.FIAT, 2)

    /**
     * Built directly, not through the factory, because some copies below are transactions no
     * factory would build.
     */
    private val sample = Transaction(
        transactionId = "txn_001",
        amount = Money(BigDecimal("1000"), usd),
        assetType = AssetType.FIAT,
        type = "Mobile Top Up",
        state = Transaction.ELIGIBLE_STATE,
        createdAt = DateFormatter.parse("2023-08-30 15:42:17.610059")!!,
    )

    @Test
    fun `pins the brief sample's digest`() {
        // printf '1000\x1fUSD\x1fFIAT\x1fMobile Top Up\x1fSETTLED - PENDING FEE\x1f2023-08-30 15:42:17.610059' | shasum -a 256
        assertEquals(SAMPLE_DIGEST, fingerprints.of(sample))
    }

    @Test
    fun `the canonical string joins the normalised fields with the unit separator`() {
        assertEquals(
            "1000\u001FUSD\u001FFIAT\u001FMobile Top Up\u001FSETTLED - PENDING FEE\u001F2023-08-30 15:42:17.610059",
            fingerprints.canonical(sample),
        )
    }

    @Test
    fun `the amount is compared numerically and the transaction_id is not part of it`() {
        assertEquals(fingerprints.of(sample), fingerprints.of(sample.copy(amount = Money(BigDecimal("1000.00"), usd))))
        assertEquals(fingerprints.of(sample), fingerprints.of(sample.copy(amount = Money(BigDecimal("1E+3"), usd))))
        assertEquals(fingerprints.of(sample), fingerprints.of(sample.copy(transactionId = "txn_002")))
    }

    @Test
    fun `any other field change gives another fingerprint`() {
        val original = fingerprints.of(sample)
        listOf(
            sample.copy(amount = Money(BigDecimal("1000.01"), usd)),
            sample.copy(amount = Money(BigDecimal("1000"), Asset("EUR", AssetType.FIAT, 2))),
            sample.copy(assetType = AssetType.CRYPTO),
            sample.copy(type = "Bank Transfer"),
            sample.copy(state = "SETTLED"),
            sample.copy(createdAt = sample.createdAt.plusNanos(1_000)),
        ).forEach { assertNotEquals(original, fingerprints.of(it), it.toString()) }
    }

    private companion object {
        const val SAMPLE_DIGEST = "a0de743b9a60a3bc322ba13619efba691d84e68444823b69f41cf27ea8b09798"
    }
}
