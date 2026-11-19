package com.cashi.fee.workflow.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Money crosses the Restate journal as a JSON string (AC8), never a JSON number that a reader
 * could turn into a `double`. The scale is kept as it is: `1.50` stays `1.50`.
 */
object BigDecimalAsString : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.cashi.fee.BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())

    override fun deserialize(decoder: Decoder): BigDecimal = BigDecimal(decoder.decodeString())
}

/**
 * Times cross the journal as ISO local date-times with exactly six fractional digits, so the
 * journaled shape never depends on how many trailing zeros a value happens to have.
 */
object LocalDateTimeAsString : KSerializer<LocalDateTime> {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS")

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.cashi.fee.LocalDateTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDateTime) = encoder.encodeString(FORMAT.format(value))

    override fun deserialize(decoder: Decoder): LocalDateTime = LocalDateTime.parse(decoder.decodeString(), FORMAT)
}
