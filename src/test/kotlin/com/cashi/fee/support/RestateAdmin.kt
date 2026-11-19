package com.cashi.fee.support

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The Restate admin API (port 9070), as the tests use it: register the app's endpoint, read a
 * service's settings, query the introspection tables (`sys_invocation`, `sys_journal`, `state`)
 * over SQL, resume a paused invocation, and purge a completed workflow run as the end of workflow
 * retention would.
 */
class RestateAdmin(private val baseUrl: String) {

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val json: JsonMapper = JsonMapper.builder().build()

    /** Registers (or re-registers) the deployment at [uri]: the app's Restate endpoint. */
    fun registerDeployment(uri: String) {
        val body = JsonNodeFactory.instance.objectNode().put("uri", uri).put("force", true)
        val response = send(post("/deployments", body.toString()))
        check(response.statusCode() in 200..299) { "deployment registration failed (${response.statusCode()}): ${response.body()}" }
    }

    /** A registered service, as `GET /services/{name}` describes it (`public`, retry policy, …). */
    fun service(name: String): JsonNode {
        val response = send(HttpRequest.newBuilder(uri("/services/${encode(name)}")).GET().build())
        check(response.statusCode() == 200) { "service $name not readable (${response.statusCode()}): ${response.body()}" }
        return json.readTree(response.body())
    }

    /** The rows of a SQL query over Restate's introspection tables. */
    fun query(sql: String): List<JsonNode> {
        val body = JsonNodeFactory.instance.objectNode().put("query", sql)
        val response = send(post("/query", body.toString()))
        check(response.statusCode() == 200) { "query failed (${response.statusCode()}): ${response.body()}\n$sql" }
        return json.readTree(response.body()).get("rows").toList()
    }

    /**
     * The id of the invocation of [service]'s [handler] for [key], such as a workflow's one `run`;
     * null when Restate has none (never started, or purged).
     */
    fun invocationId(service: String, key: String, handler: String): String? =
        query(
            """
            SELECT id FROM sys_invocation
            WHERE target_service_name = ${literal(service)} AND target_service_key = ${literal(key)}
              AND target_handler_name = ${literal(handler)}
            """.trimIndent(),
        ).singleOrNull()?.get("id")?.asString()

    /**
     * The status of the invocation of [service]'s [handler] for [key], as `sys_invocation` lists it
     * (`running`, `backing-off`, `paused`, `completed`, …); null when Restate has none.
     */
    fun invocationStatus(service: String, key: String, handler: String): String? =
        query(
            """
            SELECT status FROM sys_invocation
            WHERE target_service_name = ${literal(service)} AND target_service_key = ${literal(key)}
              AND target_handler_name = ${literal(handler)}
            """.trimIndent(),
        ).singleOrNull()?.get("status")?.asString()

    /**
     * Resumes a paused invocation: `PATCH /invocations/{id}/resume` (Restate 1.8.1's admin API).
     * Restate starts a new attempt at once, replaying the journal. It answers 409 while the
     * invocation is still running; this is the call the stuck-charge runbook uses.
     */
    fun resumeInvocation(invocationId: String) {
        val request = HttpRequest.newBuilder(uri("/invocations/${encode(invocationId)}/resume"))
            .method("PATCH", HttpRequest.BodyPublishers.noBody())
            .build()
        val response = send(request)
        check(response.statusCode() in 200..299) { "resume of $invocationId failed (${response.statusCode()}): ${response.body()}" }
    }

    /**
     * Purges a completed invocation: `PATCH /invocations/{id}/purge` (Restate 1.8.1's admin API;
     * the older `DELETE /invocations/{id}?mode=purge` is deprecated). For a workflow's `run` it
     * also deletes the workflow's state, so the key is free for a new run.
     */
    fun purgeInvocation(invocationId: String) {
        val request = HttpRequest.newBuilder(uri("/invocations/${encode(invocationId)}/purge"))
            .method("PATCH", HttpRequest.BodyPublishers.noBody())
            .build()
        val response = send(request)
        check(response.statusCode() in 200..299) { "purge of $invocationId failed (${response.statusCode()}): ${response.body()}" }
    }

    /**
     * Ends workflow retention for [key] now: purges the workflow's completed `run` invocation, with
     * its journal and workflow state, and waits until Restate no longer lists it. Virtual Object
     * state (FeeCharge) is untouched, as it is when retention ends.
     */
    fun purgeWorkflow(service: String, key: String) {
        val id = checkNotNull(invocationId(service, key, "run")) { "no $service run for $key to purge" }
        purgeInvocation(id)
        val deadline = System.nanoTime() + PURGE_WAIT.toNanos()
        while (invocationId(service, key, "run") != null) {
            check(System.nanoTime() < deadline) { "$service run $id for $key is still listed after its purge" }
            Thread.sleep(50)
        }
    }

    private fun post(path: String, body: String): HttpRequest =
        HttpRequest.newBuilder(uri(path))
            .header("content-type", "application/json")
            .header("accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

    private fun send(request: HttpRequest): HttpResponse<String> = http.send(request, HttpResponse.BodyHandlers.ofString())

    private fun uri(path: String): URI = URI.create("$baseUrl$path")

    companion object {
        private val PURGE_WAIT: Duration = Duration.ofSeconds(10)

        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

        /** A SQL string literal, quotes escaped. Test ids only; never user input. */
        fun literal(value: String): String = "'${value.replace("'", "''")}'"
    }
}
