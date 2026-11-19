package com.cashi.fee.bdd

import com.cashi.fee.support.ControllableFeeRecordRepository
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import java.time.Duration

/** Faults armed on the fee_record writes of one `transaction_id`, and what fired. */
class RecordFaultSteps(private val records: ControllableFeeRecordRepository) {

    /** Every scenario starts with nothing armed. */
    @Before
    fun disarmEveryFault() {
        records.reset()
    }

    @Given("the fee_record save for {string} is delayed by {int} seconds")
    fun theSaveIsDelayed(transactionId: String, seconds: Int) {
        records.delaySave(transactionId, Duration.ofSeconds(seconds.toLong()))
    }

    @Given("the fee_record save for {string} is delayed by {int} milliseconds")
    fun theSaveIsDelayedByMillis(transactionId: String, millis: Int) {
        records.delaySave(transactionId, Duration.ofMillis(millis.toLong()))
    }

    /** The armed delay has fired: the run is inside its `record` step, and its row isn't written yet. */
    @When("the run for {string} is held in its fee_record save")
    fun theRunIsHeldInItsSave(transactionId: String) {
        val deadline = System.nanoTime() + HOLD_LIMIT.toNanos()
        while (records.fired(transactionId) < 1) {
            check(System.nanoTime() < deadline) { "the fee_record save for $transactionId did not start within $HOLD_LIMIT" }
            Thread.sleep(10)
        }
    }

    @Then("the faults armed for {string} fired {int} time(s)")
    fun theFaultsFired(transactionId: String, times: Int) {
        assertEquals(times, records.fired(transactionId))
    }

    private companion object {
        val HOLD_LIMIT: Duration = Duration.ofSeconds(10)
    }
}
