package com.cashi.fee.api

/**
 * The examples the OpenAPI document shows on [FeeController]'s operations: the brief's sample
 * request and its answers. Swagger UI pre-fills "Try it out" with [REQUEST].
 */
internal object FeeApiExamples {

    const val REQUEST_DESCRIPTION: String =
        "The brief's sample. A transaction_id is charged once: change transaction_id before resending it " +
            "(1 to 64 characters of A-Z, a-z, 0-9, _ or -), or the answer is the first run's body, " +
            "or a 409 if any other field changed."

    const val REQUEST: String = """{
  "transaction_id": "txn_001",
  "amount": 1000,
  "asset": "USD",
  "asset_type": "FIAT",
  "type": "Mobile Top Up",
  "state": "SETTLED - PENDING FEE",
  "created_at": "2023-08-30 15:42:17.610059"
}"""

    const val RESPONSE: String = """{
  "transaction_id": "txn_001",
  "amount": 1000,
  "asset": "USD",
  "type": "Mobile Top Up",
  "fee": 1.5,
  "rate": 0.0015,
  "description": "Standard fee rate of 0.15%"
}"""

    const val PROCESSING: String = """{
  "transaction_id": "txn_001",
  "status": "PROCESSING"
}"""

    const val INVALID: String = """{
  "type": "/problems/invalid-request",
  "title": "Invalid request",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "instance": "/transaction/fee",
  "errors": [
    {"field": "amount", "message": "must be greater than zero"}
  ]
}"""

    const val PAYLOAD_CONFLICT: String = """{
  "type": "/problems/payload-conflict",
  "title": "Payload conflict",
  "status": 409,
  "detail": "this transaction_id was already used with a different payload",
  "instance": "/transaction/fee"
}"""

    const val NOT_FOUND: String = """{
  "type": "/problems/fee-not-found",
  "title": "Fee not found",
  "status": 404,
  "detail": "No fee is known for this transaction_id.",
  "instance": "/transaction/txn_001/fee"
}"""

    const val UNAVAILABLE: String = """{
  "type": "/problems/unavailable",
  "title": "Service unavailable",
  "status": 503,
  "detail": "Restate unreachable; nothing was started; resend the same request",
  "instance": "/transaction/fee"
}"""
}
