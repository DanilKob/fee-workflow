package com.cashi.fee.api

/** One invalid request field, as the problem's `errors[]` lists it. */
data class InvalidField(val field: String, val message: String)
