package com.gadscape.website;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Thin blocking client for https://<site>/api/game/*. Call it off the game thread. */
public final class WebsiteApi {
	private final WebsiteConfig config;
	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

	public WebsiteApi(WebsiteConfig config) {
		this.config = config;
	}

	public JsonObject post(String path, JsonObject body) throws IOException {
		return send(HttpRequest.newBuilder(uri(path))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)));
	}

	public JsonObject get(String path) throws IOException {
		return send(HttpRequest.newBuilder(uri(path)).GET());
	}

	private URI uri(String path) {
		return URI.create(config.siteUrl.replaceAll("/+$", "") + path);
	}

	private JsonObject send(HttpRequest.Builder builder) throws IOException {
		HttpRequest request = builder
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "Bearer " + config.apiKey)
				.header("Content-Type", "application/json")
				.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		}
		JsonElement json;
		try {
			json = JsonParser.parseString(response.body());
		} catch (RuntimeException e) {
			throw new IOException("Bad response " + response.statusCode() + " from " + request.uri());
		}
		if (response.statusCode() >= 500 || response.statusCode() == 401) {
			throw new IOException("Site returned " + response.statusCode() + ": " + response.body());
		}
		return json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
	}

	public static JsonObject obj(Object... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i < kv.length; i += 2) {
			Object v = kv[i + 1];
			if (v == null) continue;
			if (v instanceof Number) o.addProperty((String) kv[i], (Number) v);
			else if (v instanceof Boolean) o.addProperty((String) kv[i], (Boolean) v);
			else if (v instanceof JsonElement) o.add((String) kv[i], (JsonElement) v);
			else o.addProperty((String) kv[i], v.toString());
		}
		return o;
	}
}
