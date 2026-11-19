package com.cashi.fee.support

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Raw HTTP for the tests: the exact bytes go out and come back, so number rendering is checked as text. */
object Http {

    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    /** Bounds every call, so a stuck request fails the test instead of hanging the build. */
    private val TIMEOUT: Duration = Duration.ofSeconds(30)

    fun postJson(url: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("content-type", "application/json")
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    fun get(url: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString())
}
