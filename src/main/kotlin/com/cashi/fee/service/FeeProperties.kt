package com.cashi.fee.service

import jakarta.validation.constraints.NotNull
import org.hibernate.validator.constraints.time.DurationMin
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * The `fees.*` settings, validated at startup, so a bad value stops the application.
 *
 * [syncWait] is how long `POST /transaction/fee` waits for the workflow before it answers 202. It
 * only stops the polling: nothing on the server is cancelled. `SyncWaitCheck` keeps it well below
 * the request timeout, so the 202 comes before the request times out.
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
