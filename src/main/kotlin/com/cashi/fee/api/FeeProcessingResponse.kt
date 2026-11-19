package com.cashi.fee.api

import com.fasterxml.jackson.annotation.JsonPropertyOrder

/**
 * The 202 body: the run is still going. It comes with `Location: /transaction/{id}/fee` and
 * `Retry-After`, from `POST` at the deadline and from `GET` while the run lasts.
 */
@JsonPropertyOrder("transactionId", "status")
data class FeeProcessingResponse(val transactionId: String) {
    val status: String = PROCESSING

    companion object {
        const val PROCESSING: String = "PROCESSING"
    }
}
