package com.cashi.fee.service

/**
 * Where in a fee request a call failed, for the one log line a 5xx gets (O1): the fee record lookup
 * that comes before the workflow ([RECORD_LOOKUP]), or one of the workflow gateway's calls.
 */
enum class GatewayPhase { RECORD_LOOKUP, SEND, AWAIT_OUTPUT, STATUS }
