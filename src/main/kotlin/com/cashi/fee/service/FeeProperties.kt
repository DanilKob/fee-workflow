package com.cashi.fee.service

import jakarta.validation.constraints.NotNull
import org.hibernate.validator.constraints.time.DurationMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * The `fees.*` settings, validated when the application starts (KS-C2): a bad value stops it.
 *
 * [syncWait] is how long `POST /transaction/fee` waits for the workflow before it answers 202 with
 * a status link (D11, AC28). It bounds the polling only; nothing on the server is cancelled. It
 * must stay below `spring.mvc.async.request-timeout`, with a grace margin, so the 202 comes before
 * the request times out; the API's `SyncWaitCheck` refuses to start otherwise.
 */
@ConfigurationProperties("fees")
@Validated
class FeeProperties(
    @field:NotNull
    @field:DurationMin(nanos = 1, message = "must be positive")
    val syncWait: Duration = DEFAULT_SYNC_WAIT,
) {
    companion object {
        val DEFAULT_SYNC_WAIT: Duration = Duration.ofSeconds(10)
    }
}
