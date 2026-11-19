package com.cashi.fee.bdd

import com.cashi.fee.domain.AssetRegistry
import com.cashi.fee.domain.FeeCalculation
import com.cashi.fee.domain.FeeCalculationService
import com.cashi.fee.domain.Money
import com.cashi.fee.support.ControllableFeeRecordRepository
import com.cashi.fee.support.Observations
import com.cashi.fee.support.SwitchableFeeSchedule
import com.cashi.fee.support.TestInfrastructure
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.fail
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessResourceException
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Duration

/**
 * Durability (AC21–AC23, R2, R3, R13): faults after the charge, the retries that follow, a paused
 * invocation and its resume, and a fee schedule switched mid-flight. Every observation is made
 * from outside: Restate's admin API, the fee_record table, and `GET /transaction/{id}/fee`.
 */
class DurabilitySteps(
    private val context: ScenarioContext,
    private val records: ControllableFeeRecordRepository,
    private val schedule: SwitchableFeeSchedule,
    private val calculator: FeeCalculationService,
    private val assets: AssetRegistry,
    private val jdbc: JdbcTemplate,
) {

    /** Every scenario starts with the shipped rules, and leaves them for whatever runs next. */
    @Before
    fun shippedRulesBefore() {
        schedule.reset()
    }

    @After
    fun shippedRulesAfter() {
        schedule.reset()
    }

    @Given("the next {int} fee_record save(s) for {string} fail(s) before reaching the database")
    fun theNextSavesFail(times: Int, transactionId: String) {
        records.failNextSave(transactionId, times)
    }

    /** R3: a constraint-style failure is retried like any other, never turned terminal. */
    @Given("the next fee_record save for {string} fails with a DataIntegrityViolationException")
    fun theNextSaveFailsWithAnIntegrityViolation(transactionId: String) {
        records.failNextSave(transactionId, 1) { DataIntegrityViolationException("injected fee_record integrity violation") }
    }

    /** The commit lands and its acknowledgement is lost: the step fails although the row is written. */
    @Given("the next fee_record save for {string} commits, then fails")
    fun theNextSaveCommitsThenFails(transactionId: String) {
        records.failAfterSave(transactionId, 1)
    }

    /**
     * AC22: the switch happens inside the first `record` attempt, so after `calculate` has been
     * journaled and before the retry that replays it. That attempt then fails, so the run must
     * replay `calculate` under the new rules.
     */
    @Given("the fee schedule reprices Mobile Top Up at {bigdecimal} as rule version {int} when the first fee_record save for {string} runs, which then fails")
    fun theScheduleSwitchesInTheFirstSave(rate: BigDecimal, version: Int, transactionId: String) {
        records.failNextSave(transactionId, 1) {
            schedule.switchTo(SwitchableFeeSchedule.mobileTopUpAt(rate, version))
            TransientDataAccessResourceException("injected fee_record fault, after the fee schedule was switched")
        }
    }

    /** The switch took effect: a fresh quote of the brief's sample uses the new rule, so the scenario isn't vacuous. */
    @Then("the live fee schedule now prices the brief's sample at {bigdecimal} under rule version {int}")
    fun theLiveSchedulePricesTheSample(fee: BigDecimal, version: Int) {
        val sample = Money(BigDecimal("1000"), checkNotNull(assets.find("USD")))
        val quote = assertInstanceOf(FeeCalculation.Quoted::class.java, calculator.quote(sample, SwitchableFeeSchedule.MOBILE_TOP_UP)).quote
        assertEquals(0, fee.compareTo(quote.fee.amount)) { "the live schedule priced it at ${quote.fee.plain()}" }
        assertEquals(version, quote.ruleVersion)
    }

    /**
     * The POST answered while the run was retrying: the 200 body GET now returns, or, when the
     * retries outlasted `fees.sync-wait`, the status link (AC28).
     */
    @Then("the POST got this same body, or the status link if the retries outlasted its wait")
    fun thePostGotTheSameBodyOrTheStatusLink() {
        val post = context.postResponse()
        when (post.statusCode()) {
            200 -> assertEquals(context.response().body(), post.body())
            202 -> assertEquals("/transaction/${submittedId()}/fee", post.headers().firstValue("location").orElse(null))
            else -> fail("the POST was refused: ${post.statusCode()} ${post.body()}")
        }
    }

    /** AC23: the retry budget is spent, and Restate paused the invocation instead of failing it. */
    @When("the FeeWorkflow run for {string} is paused")
    fun theRunIsPaused(transactionId: String) {
        awaitRunStatus(transactionId, "paused", forbidden = "completed")
    }

    /** AC21: the run finished, and never paused on the way. */
    @Then("the FeeWorkflow run for {string} completes without pausing")
    fun theRunCompletesWithoutPausing(transactionId: String) {
        awaitRunStatus(transactionId, "completed", forbidden = "paused")
    }

    @Then("the FeeWorkflow run for {string} completes")
    fun theRunCompletes(transactionId: String) {
        awaitRunStatus(transactionId, "completed", forbidden = null)
    }

    /** The operator's fix: whatever made the save fail is gone. Faults that already fired stay counted. */
    @When("the fault on the fee_record saves for {string} is cleared")
    fun theFaultIsCleared(transactionId: String) {
        records.disarm(transactionId)
    }

    /** The runbook's resume: `PATCH /invocations/{id}/resume` on the admin API (9070). */
    @When("the FeeWorkflow run for {string} is resumed through the Restate admin API")
    fun theRunIsResumed(transactionId: String) {
        val invocation = checkNotNull(TestInfrastructure.admin.invocationId(WORKFLOW, transactionId, "run")) { "no FeeWorkflow run for $transactionId" }
        TestInfrastructure.admin.resumeInvocation(invocation)
    }

    /**
     * One charge: the journal holds one call to FeeCharge, so every retry replayed it instead of
     * charging again, and Restate ran FeeCharge's `charge` once for this id.
     */
    @Then("FeeCharge was charged exactly once for {string}")
    fun chargedExactlyOnce(transactionId: String) {
        val calls = Observations.workflowJournal(transactionId).filter { it.kind == "Call" }
        assertEquals(listOf(Observations.JournalCommand("Call", "FeeCharge/charge")), calls)
        val charges = Observations.invocations(transactionId).filter { it.startsWith("FeeCharge/charge ") }
        assertEquals(1, charges.size) { "FeeCharge/charge invocations for $transactionId: $charges" }
    }

    /** The row says what the 200 body says, field by field; money compared as numbers. */
    @Then("that fee_record row holds the fields of the 200 body")
    fun theRowHoldsTheBodysFields() {
        val response = context.response()
        assertEquals(200, response.statusCode(), response.body())
        val body = JSON.readTree(response.body())
        val row = Observations.feeRecords(jdbc, submittedId()).single()
        listOf("transaction_id", "asset", "type", "description").forEach { field ->
            assertEquals(body.get(field).asString(), row[field], field)
        }
        listOf("amount", "fee", "rate").forEach { field ->
            assertEquals(0, body.get(field).decimalValue().compareTo(BigDecimal(row.getValue(field)))) { "$field: body ${body.get(field)}, row ${row[field]}" }
        }
    }

    private fun awaitRunStatus(transactionId: String, wanted: String, forbidden: String?) {
        val deadline = System.nanoTime() + STATUS_LIMIT.toNanos()
        var status: String? = null
        while (System.nanoTime() < deadline) {
            status = TestInfrastructure.admin.invocationStatus(WORKFLOW, transactionId, "run")
            if (status == wanted) return
            if (status != null && status == forbidden) fail<Unit>("the FeeWorkflow run for $transactionId is $status, expected $wanted")
            Thread.sleep(STATUS_POLL.toMillis())
        }
        fail<Unit>("the FeeWorkflow run for $transactionId is $status after $STATUS_LIMIT, expected $wanted")
    }

    private fun submittedId(): String = JSON.readTree(context.submittedBody()).get("transaction_id").asString()

    private companion object {
        const val WORKFLOW = "FeeWorkflow"
        val STATUS_POLL: Duration = Duration.ofMillis(100)
        val STATUS_LIMIT: Duration = Duration.ofSeconds(30)
        val JSON: JsonMapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
    }
}
