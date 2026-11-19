package com.cashi.fee.api

import com.cashi.fee.service.FeeProperties
import org.springframework.boot.webmvc.autoconfigure.WebMvcProperties
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Stops startup unless `fees.sync-wait` plus [GRACE] is below `spring.mvc.async.request-timeout`.
 *
 * A slow workflow must get its 202 before Spring MVC times the request out with a 503. The wait can
 * outlast `fees.sync-wait`: the last poll gets 500 ms more, a record lookup may wait Hikari's 2 s
 * for a connection, and a duplicate reads the run's status twice. [GRACE] covers all of it.
 *
 * An unset request timeout leaves the bound to the servlet container. It can't be checked, so it is
 * refused too.
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
        /** The longest a request may take beyond `fees.sync-wait`. */
        val GRACE: Duration = Duration.ofSeconds(5)
    }
}
