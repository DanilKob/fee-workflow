package com.cashi.fee.api

import com.cashi.fee.service.FeeProperties
import org.springframework.boot.webmvc.autoconfigure.WebMvcProperties
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Stops the application at startup unless `fees.sync-wait` plus [GRACE] is below
 * `spring.mvc.async.request-timeout` (KS-C2).
 *
 * A slow workflow must get its 202 with a status link (AC28) before Spring MVC gives up on the
 * request and answers the timeout's 503. The wait itself can outlast `fees.sync-wait`: the poll
 * that lands on the deadline gets 500 ms more, a record lookup may wait up to Hikari's 2 s
 * `connection-timeout` for a connection, and a duplicate reads the run's status before and after
 * waiting. [GRACE] covers all of it. An unset request timeout leaves the bound to the servlet
 * container, so it can't be checked and is refused too.
 */
@Component
class SyncWaitCheck(feeProperties: FeeProperties, webMvcProperties: WebMvcProperties) {

    init {
        val syncWait = feeProperties.syncWait
        val requestTimeout: Duration? = webMvcProperties.async.requestTimeout
        check(requestTimeout != null && syncWait + GRACE < requestTimeout) {
            "spring.mvc.async.request-timeout ($requestTimeout) must be longer than fees.sync-wait ($syncWait) plus $GRACE, " +
                "so a slow workflow gets its 202 before the request times out (AC28)"
        }
    }

    companion object {
        /** The longest a request may take beyond `fees.sync-wait` (see the class comment). */
        val GRACE: Duration = Duration.ofSeconds(5)
    }
}
