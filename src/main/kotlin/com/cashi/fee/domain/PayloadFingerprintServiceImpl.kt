package com.cashi.fee.domain

import java.security.MessageDigest

/**
 * SHA-256 in lowercase hex over the fields joined by the unit separator: the amount as
 * [Money.plain] renders it, asset, asset type, type, state, and `created_at` as [DateFormatter]
 * formats it.
 */
class PayloadFingerprintServiceImpl : PayloadFingerprintService {

    override fun of(transaction: Transaction): String = sha256Hex(canonical(transaction))

    internal fun canonical(transaction: Transaction): String =
        listOf(
            transaction.amount.plain(),
            transaction.asset,
            transaction.assetType.name,
            transaction.type,
            transaction.state,
            DateFormatter.format(transaction.createdAt),
        ).joinToString(SEPARATOR)

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SEPARATOR = "\u001F"
    }
}
