package com.cashi.fee.workflow

import dev.restate.sdk.kotlin.Restate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.toJavaInstant

/**
 * Now, as a UTC local date-time at the microsecond precision of `timestamp(6)`. The instant comes
 * from `Clock.Restate`, which journals it, so a replay sees the same value (AC22).
 */
@OptIn(ExperimentalTime::class)
internal suspend fun restateNowUtc(): LocalDateTime =
    LocalDateTime.ofInstant(Clock.Restate.now().toJavaInstant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
