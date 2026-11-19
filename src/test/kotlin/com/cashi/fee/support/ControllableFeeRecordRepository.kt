package com.cashi.fee.support

import com.cashi.fee.domain.FeeRecord
import com.cashi.fee.domain.FeeRecordRepository
import com.cashi.fee.persistence.JpaFeeRecordRepository
import org.springframework.context.annotation.Primary
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Test-only: the [FeeRecordRepository] every bean of the shared IT context gets (`@Primary`), a
 * decorator over the real [JpaFeeRecordRepository] that can make `save` slow or failing (plan §4).
 *
 * Faults are keyed by `transaction_id` and apply to `save` only, so a run left over from another
 * scenario can't use up a scenario's faults. Each armed fault fires once per save it affects, and
 * [fired] counts them, so a scenario can assert that what it armed is what happened. Cucumber
 * [reset]s it before every scenario. With nothing armed it only delegates.
 */
@Component
@Primary
class ControllableFeeRecordRepository(private val delegate: JpaFeeRecordRepository) : FeeRecordRepository {

    private class Faults {
        val failBefore = AtomicInteger()
        val failBeforeWith = AtomicReference<() -> RuntimeException> { injected("before the save") }
        val failAfter = AtomicInteger()
        val delay = AtomicReference<Duration?>()
        val fired = AtomicInteger()
    }

    private val faults = ConcurrentHashMap<String, Faults>()

    override fun find(transactionId: String): FeeRecord? = delegate.find(transactionId)

    override fun save(record: FeeRecord) {
        val armed = faults[record.transactionId]
        if (armed != null) {
            armed.delay.getAndSet(null)?.let { delay ->
                armed.fired.incrementAndGet()
                Thread.sleep(delay.toMillis())
            }
            if (armed.failBefore.takeOne()) {
                armed.fired.incrementAndGet()
                throw armed.failBeforeWith.get()()
            }
        }
        delegate.save(record)
        if (armed != null && armed.failAfter.takeOne()) {
            armed.fired.incrementAndGet()
            throw injected("after the save committed")
        }
    }

    /** The next [times] saves for [transactionId] throw [exception] before reaching the database. */
    fun failNextSave(transactionId: String, times: Int, exception: () -> RuntimeException = { injected("before the save") }) {
        faultsOf(transactionId).apply {
            failBeforeWith.set(exception)
            failBefore.set(times)
        }
    }

    /** The next [times] saves for [transactionId] commit, then throw, as a lost commit acknowledgement would. */
    fun failAfterSave(transactionId: String, times: Int) {
        faultsOf(transactionId).failAfter.set(times)
    }

    /** The next save for [transactionId] waits [delay] before it reaches the database. */
    fun delaySave(transactionId: String, delay: Duration) {
        faultsOf(transactionId).delay.set(delay)
    }

    /**
     * Disarms every fault left for [transactionId], as an operator's fix would, and keeps the count
     * of those that already fired.
     */
    fun disarm(transactionId: String) {
        faults[transactionId]?.apply {
            failBefore.set(0)
            failAfter.set(0)
            delay.set(null)
        }
    }

    /** How many armed faults (failures and delays) have fired for [transactionId]. */
    fun fired(transactionId: String): Int = faults[transactionId]?.fired?.get() ?: 0

    /** Disarms every fault and forgets every count. */
    fun reset() {
        faults.clear()
    }

    private fun faultsOf(transactionId: String): Faults = faults.computeIfAbsent(transactionId) { Faults() }

    /** Takes one unit when any is left; false when none is. */
    private fun AtomicInteger.takeOne(): Boolean = getAndUpdate { if (it > 0) it - 1 else 0 } > 0

    private companion object {
        fun injected(where: String): RuntimeException = TransientDataAccessResourceException("injected fee_record fault $where")
    }
}
