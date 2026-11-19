package com.cashi.fee.bdd

import com.cashi.fee.domain.DateFormatter
import com.cashi.fee.domain.FeeRecordRepository
import com.cashi.fee.domain.PayloadFingerprintService
import com.cashi.fee.domain.TransactionCreation
import com.cashi.fee.domain.TransactionFactory
import com.cashi.fee.domain.TransactionInput
import com.cashi.fee.support.FeeChargeSeeder
import com.cashi.fee.support.Http
import com.cashi.fee.support.Observations
import com.cashi.fee.support.SeedCharge
import com.cashi.fee.support.TestInfrastructure
import dev.restate.client.Client
import dev.restate.client.IngressException
import dev.restate.client.kotlin.response
import dev.restate.client.kotlin.toService
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

/** What a request left behind: the workflow journal, the FeeCharge state and the fee_record row. */
class ChargeSteps(
    private val context: ScenarioContext,
    private val jdbc: JdbcTemplate,
    private val client: Client,
    private val records: FeeRecordRepository,
    private val fingerprints: PayloadFingerprintService,
    private val transactions: TransactionFactory,
) {

    @Then("the workflow journal for {string} shows, in this order:")
    fun theWorkflowJournalShows(transactionId: String, expected: List<List<String>>) {
        val journal = Observations.workflowJournal(transactionId)
        val wanted = expected.map { (kind, label) -> Observations.JournalCommand(kind, label) }

        val remaining = journal.iterator()
        val missing = wanted.filterNot { step -> remaining.asSequence().any { it == step } }
        assertTrue(missing.isEmpty()) { "not in the journal in this order: $missing; journal: $journal" }
    }

    @Then("exactly one fee_record row exists for {string}")
    fun exactlyOneRowExists(transactionId: String) {
        assertEquals(1, Observations.feeRecords(jdbc, transactionId).size)
    }

    @Then("no fee_record row exists for {string}")
    fun noRowExists(transactionId: String) {
        assertEquals(0, Observations.feeRecords(jdbc, transactionId).size)
    }

    @Then("that fee_record row holds:")
    fun thatRowHolds(expected: Map<String, String>) {
        val row = row()
        expected.forEach { (column, value) ->
            val actual = row[column]
            if (column in MONEY_COLUMNS) {
                assertEquals(0, BigDecimal(actual).compareTo(BigDecimal(value))) { "$column: expected $value, was $actual" }
            } else {
                assertEquals(value, actual, column)
            }
        }
    }

    @Then("that fee_record row's fingerprint is the submitted payload's")
    fun thatRowsFingerprint() {
        assertEquals(submittedFingerprint(), row()["fingerprint"])
    }

    @Then("that fee_record row's charge_id and charged_at are the charge in FeeCharge state")
    fun thatRowsChargeIsFeeChargesState() {
        val row = row()
        val charge = checkNotNull(Observations.charge(row.getValue("transaction_id"))) { "FeeCharge holds no charge for $row" }
        assertEquals(charge.chargeId, row["charge_id"])
        assertEquals(charge.chargedAt, Observations.timestamp(row.getValue("charged_at")))
    }

    @Then("that fee_record row's recorded_at is not before its charged_at")
    fun recordedAtIsNotBeforeChargedAt() {
        val row = row()
        assertFalse(Observations.timestamp(row.getValue("recorded_at")).isBefore(Observations.timestamp(row.getValue("charged_at")))) {
            row.toString()
        }
    }

    @Then("FeeCharge state for {string} holds a charge of {bigdecimal} {string} with the submitted payload's fingerprint")
    fun feeChargeHolds(transactionId: String, fee: BigDecimal, asset: String) {
        val charge = checkNotNull(Observations.charge(transactionId)) { "FeeCharge holds no charge for $transactionId" }
        assertEquals(0, fee.compareTo(charge.fee)) { "expected a fee of $fee, was ${charge.fee}" }
        assertEquals(asset, charge.asset)
        assertEquals(submittedFingerprint(), charge.fingerprint)
        context.chargeSnapshot = charge
    }

    /** Money crosses Restate as a JSON string, so no reader can turn it into a double. */
    @Then("FeeCharge state for {string} holds the fee as the JSON string {string}")
    fun feeChargeHoldsTheFeeAsAString(transactionId: String, fee: String) {
        val stored = checkNotNull(Observations.chargeJson(transactionId)) { "FeeCharge holds no charge for $transactionId" }
        val node = JSON.readTree(stored).get("fee")
        assertTrue(node != null && node.isString) { "the fee is not a JSON string: $stored" }
        assertEquals(fee, node.asString(), stored)
    }

    /**
     * The stored row, read back through the app's repository under the IT JVM's UTC+14 zone,
     * renders exactly what was sent, so no time-zone shift happened.
     */
    @Then("the fee_record row for {string}, read back through the app, has created_at {string}")
    fun theRowReadBackHasCreatedAt(transactionId: String, createdAt: String) {
        val record = checkNotNull(records.find(transactionId)) { "no fee_record row for $transactionId" }
        assertEquals(createdAt, DateFormatter.format(record.createdAt))
    }

    /** Plants a charge for another payload, so the workflow's charge step refuses this one. */
    @Given("FeeCharge {string} already holds a charge for another payload")
    fun feeChargeAlreadyHoldsAnotherPayload(transactionId: String) {
        runBlocking {
            client.toService<FeeChargeSeeder>().request { seed(SeedCharge(transactionId, BigDecimal("1.50"), "USD", "f".repeat(64))) }.call()
        }
    }

    /**
     * An orphan charge: FeeCharge already holds a charge for this payload, with no workflow run and
     * no row, as a run that charged and then lost its record would leave it.
     */
    @Given("FeeCharge {string} already holds a charge of {bigdecimal} {string} for this payload")
    fun feeChargeAlreadyHoldsThisPayload(transactionId: String, fee: BigDecimal, asset: String) {
        val fingerprint = fingerprintOf(context.payloadJson())
        val charge = runBlocking {
            client.toService<FeeChargeSeeder>().request { seed(SeedCharge(transactionId, fee, asset, fingerprint)) }.call().response
        }
        context.seededCharge = charge
        context.chargeSnapshot = charge
    }

    @Then("that fee_record row's charge is the seeded charge")
    fun thatRowsChargeIsTheSeededCharge() {
        val seeded = checkNotNull(context.seededCharge) { "no charge was planted in this scenario" }
        val row = row()
        assertEquals(seeded.chargeId, row["charge_id"])
        assertEquals(seeded.chargedAt, Observations.timestamp(row.getValue("charged_at")))
        assertEquals(0, seeded.fee.compareTo(BigDecimal(row.getValue("fee")))) { "row fee ${row["fee"]}, charged ${seeded.fee}" }
    }

    @Then("the fee_record row for {string} is noted")
    fun theRowIsNoted(transactionId: String) {
        context.rowSnapshot = Observations.feeRecords(jdbc, transactionId).single()
    }

    /** Every column, as Postgres renders it, is what it was when noted. */
    @Then("the fee_record row for {string} is unchanged")
    fun theRowIsUnchanged(transactionId: String) {
        val noted = checkNotNull(context.rowSnapshot) { "the fee_record row was not noted first" }
        assertEquals(listOf(noted), Observations.feeRecords(jdbc, transactionId))
    }

    /** Exactly these commands, so a step missing from the list (a charge) is known not to have run. */
    @Then("the workflow journal for {string} shows only:")
    fun theWorkflowJournalShowsOnly(transactionId: String, expected: List<List<String>>) {
        assertEquals(expected.map { (kind, label) -> Observations.JournalCommand(kind, label) }, Observations.workflowJournal(transactionId))
    }

    /** Ends workflow retention now: the journal and workflow state go, FeeCharge's state stays. */
    @When("the workflow run for {string} is purged, as at the end of workflow retention")
    fun theWorkflowRunIsPurged(transactionId: String) {
        TestInfrastructure.admin.purgeWorkflow("FeeWorkflow", transactionId)
    }

    /** One workflow instance per `transaction_id`: every resend joined it. */
    @Then("Restate ran exactly one FeeWorkflow run for {string}")
    fun exactlyOneRun(transactionId: String) {
        val runs = Observations.invocations(transactionId).filter { it.startsWith("FeeWorkflow/run ") }
        assertEquals(1, runs.size) { "FeeWorkflow runs for $transactionId: $runs" }
    }

    @Then("Restate has no invocation for {string}")
    fun restateHasNoInvocation(transactionId: String) {
        assertEquals(emptyList<String>(), Observations.invocations(transactionId))
    }

    @When("FeeCharge {string} is charged again with the same fingerprint and a fee of {bigdecimal} {string}")
    fun chargedAgainWithTheSameFingerprint(transactionId: String, fee: BigDecimal, asset: String) {
        chargeAgain(SeedCharge(transactionId, fee, asset, submittedFingerprint()))
    }

    @When("FeeCharge {string} is charged again with another fingerprint")
    fun chargedAgainWithAnotherFingerprint(transactionId: String) {
        val snapshot = checkNotNull(context.chargeSnapshot) { "FeeCharge state was not observed first" }
        chargeAgain(SeedCharge(transactionId, snapshot.fee, snapshot.asset, "f".repeat(64)))
    }

    @Then("the repeat charge returns the charge_id of the fee_record row")
    fun theRepeatChargeReturnsTheRecordedChargeId() {
        val repeat = checkNotNull(context.repeatCharge) { "the repeat charge failed: ${context.repeatFailureBody}" }
        assertEquals(row()["charge_id"], repeat.chargeId)
        assertEquals(context.chargeSnapshot, repeat, "the stored charge comes back unchanged, whatever fee was asked")
    }

    @Then("the repeat charge fails with status {int} and tag {string}")
    fun theRepeatChargeFails(status: Int, tag: String) {
        assertEquals(status, context.repeatFailureStatus, "repeat charge answered ${context.repeatCharge}")
        val body = JSON.readTree(context.repeatFailureBody)
        assertEquals(tag, body.path("metadata").path("reason").asString(), context.repeatFailureBody)
    }

    @Then("FeeCharge state for {string} is unchanged")
    fun feeChargeIsUnchanged(transactionId: String) {
        assertEquals(context.chargeSnapshot, Observations.charge(transactionId))
    }

    @When("FeeCharge {string} is called directly through the Restate ingress")
    fun calledDirectlyThroughTheIngress(transactionId: String) {
        context.ingressResponse = Http.postJson(
            "${TestInfrastructure.ingressUrl}/FeeCharge/$transactionId/charge",
            """{"fee":"0.01","asset":"USD","fingerprint":"${"e".repeat(64)}"}""",
        )
    }

    @Then("the ingress refuses the call")
    fun theIngressRefuses() {
        val response = checkNotNull(context.ingressResponse)
        assertFalse(response.statusCode() in 200..299) { "FeeCharge answered through the ingress: ${response.statusCode()} ${response.body()}" }
    }

    @Then("FeeCharge is not public in the Restate admin API")
    fun feeChargeIsNotPublic() {
        assertFalse(TestInfrastructure.admin.service("FeeCharge").get("public").asBoolean())
    }

    private fun chargeAgain(request: SeedCharge) {
        try {
            context.repeatCharge = runBlocking { client.toService<FeeChargeSeeder>().request { seed(request) }.call().response }
        } catch (failure: IngressException) {
            context.repeatFailureStatus = failure.statusCode
            context.repeatFailureBody = failure.responseBody?.let { String(it, Charsets.UTF_8) }
        }
    }

    private fun row(): Map<String, String> {
        val transactionId = JSON.readTree(context.submittedBody()).get("transaction_id").asString()
        return Observations.feeRecords(jdbc, transactionId).single()
    }

    /** The fingerprint of what was submitted, from the app's PayloadFingerprintService bean. */
    private fun submittedFingerprint(): String = fingerprintOf(context.submittedBody())

    /** The fingerprint of a valid request body, built and fingerprinted by the app's own beans. */
    private fun fingerprintOf(json: String): String {
        val body = JSON.readTree(json)
        val input = TransactionInput(
            transactionId = body.get("transaction_id").asString(),
            amount = body.get("amount").decimalValue(),
            asset = body.get("asset").asString(),
            assetType = body.get("asset_type").asString(),
            type = body.get("type").asString(),
            state = body.get("state").asString(),
            createdAt = body.get("created_at").asString(),
        )
        return when (val creation = transactions.create(input)) {
            is TransactionCreation.Created -> fingerprints.of(creation.transaction)
            is TransactionCreation.Invalid -> error("not a valid payload: ${creation.violations}")
        }
    }

    private companion object {
        val MONEY_COLUMNS = setOf("amount", "fee", "rate")
        val JSON: JsonMapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
    }
}
