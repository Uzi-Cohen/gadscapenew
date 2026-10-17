package com.gadscape.website

import com.gadscape.game.world.info.WebsiteProfile
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Blocking client for the website game API (https://<site>/api/game/...). Never call it on the game thread.
 */
internal class WebsiteClient(private val profile: WebsiteProfile) {

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun post(path: String, body: JsonObject, timeoutSeconds: Long = 10): JsonObject =
        send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body.toString())), timeoutSeconds)

    fun get(path: String, timeoutSeconds: Long = 10): JsonObject =
        send(HttpRequest.newBuilder(uri(path)).GET(), timeoutSeconds)

    private fun uri(path: String) = URI.create(profile.siteUrl.trimEnd('/') + path)

    private fun send(builder: HttpRequest.Builder, timeoutSeconds: Long): JsonObject {
        val request = builder
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Authorization", "Bearer ${profile.apiKey}")
            .header("Content-Type", "application/json")
            .header("User-Agent", "GadScape Game Server")
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        }
        val json = try {
            JsonParser.parseString(response.body())
        } catch (e: RuntimeException) {
            throw IOException("Bad response ${response.statusCode()} from ${request.uri()}")
        }
        if (response.statusCode() >= 500 || response.statusCode() == 401)
            throw IOException("Website returned ${response.statusCode()} for ${request.uri().path}: ${response.body().take(200)}")
        return if (json.isJsonObject) json.asJsonObject else JsonObject()
    }
}

internal fun json(vararg pairs: Pair<String, Any?>) = JsonObject().apply {
    for ((k, v) in pairs) when (v) {
        null -> {}
        is Number -> addProperty(k, v)
        is Boolean -> addProperty(k, v)
        is JsonElement -> add(k, v)
        else -> addProperty(k, v.toString())
    }
}

internal fun JsonObject.str(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
internal fun JsonObject.int(key: String): Int? = get(key)?.takeUnless { it.isJsonNull }?.asInt
internal fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
