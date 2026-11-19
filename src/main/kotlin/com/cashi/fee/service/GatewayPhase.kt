package com.cashi.fee.service

/**
 * Where in a fee request a call failed, for the log line of a 5xx: the record lookup before the
 * workflow, or one of the gateway's calls.
 */
enum class GatewayPhase { RECORD_LOOKUP, SEND, AWAIT_OUTPUT, STATUS }
