package com.cashi.fee.bdd

import com.cashi.fee.support.Http
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.springframework.beans.factory.annotation.Value
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Builds a request, sends it over HTTP to the POST or GET endpoint, and checks the response. */
class TransactionSteps(
    private val context: ScenarioContext,
    @param:Value("\${local.server.port}") private val port: Int,
) {

    @Given("the brief's sample transaction with transaction_id {string}")
    fun theBriefsSample(transactionId: String) {
        context.payload = linkedMapOf(
            "transaction_id" to "\"$transactionId\"",
            "amount" to "1000",
            "asset" to "\"USD\"",
            "asset_type" to "\"FIAT\"",
            "type" to "\"Mobile Top Up\"",
            "state" to "\"SETTLED - PENDING FEE\"",
            "created_at" to "\"2023-08-30 15:42:17.610059\"",
        )
    }

    /** Amount as raw JSON text (so `1000.00` and `1E+30` go out as written), asset and asset type. */
    @Given("it is {word} {string} of asset type {string}")
    fun itIsAnAmountOf(rawAmount: String, asset: String, assetType: String) {
        context.payload["amount"] = rawAmount
        context.payload["asset"] = "\"$asset\""
        context.payload["asset_type"] = "\"$assetType\""
    }

    @Given("its type is {string}")
    fun itsTypeIs(type: String) {
        context.payload["type"] = "\"$type\""
    }

    @Given("its {string} is the JSON value {word}")
    fun itsFieldIsTheJsonValue(field: String, rawJson: String) {
        context.payload[field] = rawJson
    }

    /** A string value, JSON-escaped, so it may hold spaces (`SETTLED - FEE CHARGED`, a date-time). */
    @Given("its {string} is the JSON string {string}")
    fun itsFieldIsTheJsonString(field: String, value: String) {
        context.payload[field] = JSON.writeValueAsString(value)
    }

    @Given("its {string} is missing")
    fun itsFieldIsMissing(field: String) {
        checkNotNull(context.payload.remove(field)) { "$field is not in the payload" }
    }

    /** The JVM runs in a zone with an offset (Failsafe sets UTC+14), so a time-zone shift would show. */
    @Given("the JVM's default time zone is not UTC")
    fun theJvmZoneIsNotUtc() {
        val offset = ZoneId.systemDefault().rules.getOffset(Instant.now())
        assertNotEquals(ZoneOffset.UTC, offset, "the IT JVM must not run at UTC (${ZoneId.systemDefault()})")
    }

    @When("it is submitted")
    fun itIsSubmitted() {
        submit(context.payloadJson())
    }

    /** The body last submitted, sent again as it was. */
    @When("it is submitted again")
    fun itIsSubmittedAgain() {
        submit(context.submittedBody())
    }

    /**
     * A resend under the same `transaction_id` with one field changed, as raw JSON text, so `2000`,
     * `1000.00` and `"Bank Transfer"` go out as written.
     */
    @When("^the same transaction_id is submitted with its \"([^\"]+)\" as the JSON value (.+)$")
    fun theSameIdIsSubmittedWithAChangedField(field: String, rawJson: String) {
        check(field in context.payload) { "$field is not in the payload" }
        context.payload[field] = rawJson
        submit(context.payloadJson())
    }

    /** The scenario's first payload, exactly as it was first sent. */
    @When("the first payload is submitted again")
    fun theFirstPayloadIsSubmittedAgain() {
        submit(checkNotNull(context.firstSubmittedBody) { "no request was submitted in this scenario" })
    }

    /** Sent on another thread: the scenario goes on while this POST waits for its answer. */
    @When("it is submitted in the background")
    fun itIsSubmittedInTheBackground() {
        val body = context.payloadJson()
        context.submittedBody = body
        context.backgroundResponse = CompletableFuture.supplyAsync { Http.postJson(feeEndpoint(), body) }
    }

    /** [times] identical POSTs, released together from their own threads. */
    @When("it is submitted {int} times at once")
    fun itIsSubmittedAtOnce(times: Int) {
        val body = context.payloadJson()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(times)
        try {
            val sent = (1..times).map {
                pool.submit<HttpResponse<String>> {
                    start.await()
                    Http.postJson(feeEndpoint(), body)
                }
            }
            start.countDown()
            context.concurrentResponses = sent.map { it.get(CONCURRENT_LIMIT.toSeconds(), TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        record(body, context.concurrentResponses.first())
    }

    @When("this transaction is submitted:")
    fun thisTransactionIsSubmitted(body: String) {
        submit(body)
    }

    /** `GET /transaction/{id}/fee`. The POST's response is kept to compare against. */
    @When("the fee of {string} is requested")
    fun theFeeIsRequested(transactionId: String) {
        context.response = Http.get(feeUrl(transactionId))
    }

    /** Polls the GET, as a caller following the 202's `Retry-After` would, until it answers otherwise. */
    @When("the fee of {string} is requested until the answer is no longer {int}")
    fun theFeeIsRequestedUntil(transactionId: String, status: Int) {
        val deadline = System.nanoTime() + POLL_LIMIT.toNanos()
        var response = Http.get(feeUrl(transactionId))
        while (response.statusCode() == status && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL.toMillis())
            response = Http.get(feeUrl(transactionId))
        }
        context.response = response
    }

    @Then("the response status is {int}")
    fun theResponseStatusIs(status: Int) {
        assertEquals(status, context.response().statusCode(), context.response().body())
    }

    @Then("the response content type is {string}")
    fun theResponseContentTypeIs(contentType: String) {
        assertEquals(contentType, context.response().headers().firstValue("content-type").orElse(null))
    }

    @Then("the response body equals:")
    fun theResponseBodyEquals(expected: String) {
        assertEquals(JSON.readTree(expected), responseJson(), "field by field")
    }

    @Then("the response body has exactly the keys {string}")
    fun theResponseBodyHasExactlyTheKeys(keys: String) {
        assertEquals(keys.split(",").map(String::trim).toSet(), responseJson().propertyNames().toSet())
    }

    @Then("no value in the response body is null")
    fun noValueIsNull() {
        responseJson().properties().forEach { (key, value) -> assertFalse(value.isNull) { "$key is null" } }
    }

    @Then("the response body has no key {string}")
    fun theResponseBodyHasNoKey(key: String) {
        assertFalse(responseJson().has(key)) { "unexpected key $key in ${context.response().body()}" }
    }

    @Then("the raw response body renders {string} as {word}")
    fun theRawBodyRenders(key: String, rendered: String) {
        val body = context.response().body()
        assertTrue(Regex("\"$key\":${Regex.escape(rendered)}(?=[,}])").containsMatchIn(body)) {
            "expected \"$key\":$rendered in the raw body: $body"
        }
    }

    /** The 400 problem's `errors[]` holds this one field and message, and nothing else. */
    @Then("the problem lists exactly the invalid field {string} with the message {string}")
    fun theProblemListsExactly(field: String, message: String) {
        val errors = responseJson().get("errors")
        assertTrue(errors != null && errors.isArray) { "no top-level errors array: ${context.response().body()}" }
        assertEquals(
            listOf(field to message),
            errors.values().map { it.path("field").asString(null) to it.path("message").asString(null) },
            context.response().body(),
        )
    }

    /**
     * The problem lists exactly these fields and messages, each field once. The order isn't
     * compared: keeping field errors before rule errors is the validator's job.
     */
    @Then("the problem lists exactly these invalid fields:")
    fun theProblemListsExactlyTheseFields(expected: Map<String, String>) {
        theResponseContentTypeIs("application/problem+json")
        val listed = errors().values().map { it.path("field").asString(null) to it.path("message").asString(null) }
        assertEquals(listed.map { it.first }.distinct(), listed.map { it.first }) { "a field is listed twice: ${context.response().body()}" }
        assertEquals(expected.toList().toSet(), listed.toSet(), context.response().body())
    }

    @Then("the problem lists no invalid field")
    fun theProblemListsNoField() {
        assertEquals(0, errors().size(), context.response().body())
    }

    @Then("the response header {string} is {string}")
    fun theResponseHeaderIs(header: String, value: String) {
        assertEquals(value, context.response().headers().firstValue(header).orElse(null)) { "$header in ${context.response().headers().map()}" }
    }

    /** Every identical request sent at once got a 200 with the same bytes. */
    @Then("every one of those responses is 200 with the same body")
    fun everyResponseIs200WithTheSameBody() {
        val responses = context.concurrentResponses
        check(responses.isNotEmpty()) { "no requests were sent at once in this scenario" }
        responses.forEach { assertEquals(200, it.statusCode(), it.body()) }
        assertEquals(listOf(responses.first().body()), responses.map { it.body() }.distinct())
    }

    /** A resend, or a GET, answers byte for byte what the scenario's first POST answered. */
    @Then("the response body is exactly the first POST's 200 body")
    fun theResponseBodyIsTheFirstPosts() {
        assertEquals(200, context.firstPostResponse().statusCode(), context.firstPostResponse().body())
        assertEquals(200, context.response().statusCode(), context.response().body())
        assertEquals(context.firstPostResponse().body(), context.response().body())
    }

    /**
     * The background POST was not refused: it got the same 200 body, or the status link if its
     * wait ran out first.
     */
    @Then("the background submission got the same 200 body, or the status link if its wait ran out")
    fun theBackgroundSubmissionWasNotRefused() {
        val background = checkNotNull(context.backgroundResponse) { "nothing was submitted in the background" }
            .get(CONCURRENT_LIMIT.toSeconds(), TimeUnit.SECONDS)
        val transactionId = JSON.readTree(context.submittedBody()).get("transaction_id").asString()
        when (background.statusCode()) {
            200 -> assertEquals(context.response().body(), background.body())
            202 -> assertEquals("/transaction/$transactionId/fee", background.headers().firstValue("location").orElse(null))
            else -> fail("the background submission was refused: ${background.statusCode()} ${background.body()}")
        }
    }

    /** GET answers byte for byte what the POST answered with its 200. */
    @Then("the response body is exactly the POST's 200 body")
    fun theResponseBodyIsThePosts() {
        assertEquals(200, context.postResponse().statusCode(), context.postResponse().body())
        assertEquals(context.postResponse().body(), context.response().body())
    }

    /** The same problem, field by field; only `instance`, the request's own path, differs. */
    @Then("the response is the same problem the POST got")
    fun theResponseIsThePostsProblem() {
        val post = context.postResponse()
        assertEquals(post.statusCode(), context.response().statusCode(), context.response().body())
        assertEquals(post.headers().firstValue("content-type"), context.response().headers().firstValue("content-type"))
        val withoutInstance = { body: String -> (JSON.readTree(body) as ObjectNode).apply { remove("instance") } }
        assertEquals(withoutInstance(post.body()), withoutInstance(context.response().body()))
    }

    @Then("the response field {string} is {string}")
    fun theResponseFieldIs(key: String, value: String) {
        assertEquals(value, responseJson().get(key)?.asString())
    }

    private fun submit(body: String) {
        record(body, Http.postJson(feeEndpoint(), body))
    }

    /** Makes [response] the one the `the response …` steps check; the first POST is kept. */
    private fun record(body: String, response: HttpResponse<String>) {
        context.submittedBody = body
        context.response = response
        context.postResponse = response
        if (context.firstPostResponse == null) {
            context.firstPostResponse = response
            context.firstSubmittedBody = body
        }
    }

    private fun feeEndpoint(): String = "http://localhost:$port/transaction/fee"

    private fun feeUrl(transactionId: String): String = "http://localhost:$port/transaction/$transactionId/fee"

    private fun responseJson(): JsonNode = JSON.readTree(context.response().body())

    private fun errors(): JsonNode {
        val errors = responseJson().get("errors")
        assertTrue(errors != null && errors.isArray) { "no top-level errors array: ${context.response().body()}" }
        return errors!!
    }

    private companion object {
        val POLL_INTERVAL: Duration = Duration.ofMillis(250)
        val POLL_LIMIT: Duration = Duration.ofSeconds(20)

        /** Bounds the wait for a concurrent or background request; Http's timeout bounds each too. */
        val CONCURRENT_LIMIT: Duration = Duration.ofSeconds(60)

        /** Floats as BigDecimal, so a comparison never goes through double. */
        val JSON: JsonMapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
    }
}
