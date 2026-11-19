package com.cashi.fee.bdd

import com.cashi.fee.workflow.model.Charge
import io.cucumber.spring.ScenarioScope
import org.springframework.stereotype.Component
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

/**
 * What one scenario has done so far, shared by its step classes. A fresh instance per scenario,
 * so nothing leaks from one scenario to the next.
 */
@Component
@ScenarioScope
class ScenarioContext {

    /** The request being built: each top-level field as raw JSON text, in order. */
    var payload: LinkedHashMap<String, String> = linkedMapOf()

    /** The body last sent to `POST /transaction/fee`. */
    var submittedBody: String? = null

    /** The last response, of a POST or a GET: what the `the response …` steps check. */
    var response: HttpResponse<String>? = null

    /** The last `POST /transaction/fee` response, kept when a GET follows, to compare against. */
    var postResponse: HttpResponse<String>? = null

    /** The scenario's first `POST /transaction/fee` response and body, which resends are held to (AC17, AC19). */
    var firstPostResponse: HttpResponse<String>? = null
    var firstSubmittedBody: String? = null

    /** A `POST /transaction/fee` still in flight, sent while the scenario goes on (AC16). */
    var backgroundResponse: CompletableFuture<HttpResponse<String>>? = null

    /** The responses of identical requests sent at once (AC17). */
    var concurrentResponses: List<HttpResponse<String>> = emptyList()

    /** The fee_record row as first observed, every column as text, to compare against later. */
    var rowSnapshot: Map<String, String>? = null

    /** FeeCharge's state as first observed (or as planted), to compare against later. */
    var chargeSnapshot: Charge? = null

    /** The charge planted in FeeCharge before any request: an orphan charge (AC18). */
    var seededCharge: Charge? = null

    /** The answer of a repeated charge: the charge, or the failure's status and raw body. */
    var repeatCharge: Charge? = null
    var repeatFailureStatus: Int? = null
    var repeatFailureBody: String? = null

    /** The answer of a direct call to the Restate ingress. */
    var ingressResponse: HttpResponse<String>? = null

    /** The request being built, as the JSON object it is sent as. */
    fun payloadJson(): String = payload.entries.joinToString(",", "{", "}") { (key, raw) -> "\"$key\":$raw" }

    fun response(): HttpResponse<String> = checkNotNull(response) { "no request was submitted in this scenario" }

    fun submittedBody(): String = checkNotNull(submittedBody) { "no request was submitted in this scenario" }

    fun postResponse(): HttpResponse<String> = checkNotNull(postResponse) { "no request was submitted in this scenario" }

    fun firstPostResponse(): HttpResponse<String> = checkNotNull(firstPostResponse) { "no request was submitted in this scenario" }
}
