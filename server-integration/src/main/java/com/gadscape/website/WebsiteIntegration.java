package com.gadscape.website;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connects the game to the website. Create one at startup, call {@link #start()},
 * and route the commands / login / pin use through the public methods below.
 * All network calls run on a background pool; results come back on the game
 * thread through {@link GameHooks#runOnGameThread(Runnable)}.
 */
public final class WebsiteIntegration {
	private static final Logger LOG = Logger.getLogger("website");

	private final WebsiteConfig config;
	private final GameHooks game;
	private final WebsiteApi api;
	private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(4, r -> {
		Thread t = new Thread(r, "website-api");
		t.setDaemon(true);
		return t;
	});
	private final Set<String> bannedPlayers = ConcurrentHashMap.newKeySet();
	private final Set<String> bannedIps = ConcurrentHashMap.newKeySet();
	private final Set<String> claiming = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean polling = new AtomicBoolean();
	private volatile int lastSeq;
	private HttpServer webhook;

	public WebsiteIntegration(WebsiteConfig config, GameHooks game) {
		this.config = config;
		this.game = game;
		this.api = new WebsiteApi(config);
	}

	/** Same normalisation as the site: lowercase, spaces/underscores/hyphens folded to "_". */
	public static String key(String name) {
		return name.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "_");
	}

	// ---- lifecycle ----------------------------------------------------------------

	public void start() {
		lastSeq = readSeq();
		pool.execute(this::refreshBans);
		pool.scheduleWithFixedDelay(this::refreshBans, config.banRefreshSeconds, config.banRefreshSeconds, TimeUnit.SECONDS);
		pool.scheduleWithFixedDelay(this::pollEvents, 5, config.pollSeconds, TimeUnit.SECONDS);
		if (config.webhookPort > 0) startWebhook();
	}

	public void stop() {
		if (webhook != null) webhook.stop(0);
		pool.shutdownNow();
	}

	// ---- login ----------------------------------------------------------------------

	/** Fast local check you can do inside the login handshake (cached ban list). */
	public boolean isBannedCached(String player, String ip) {
		return bannedPlayers.contains(key(player)) || (ip != null && bannedIps.contains(ip));
	}

	/**
	 * Full check: ban, VPN/proxy and IP logging on the site. Call when the player
	 * logs in; {@code result} receives null to allow, or the reason to kick with.
	 */
	public void onLogin(String player, String ip, Consumer<String> result) {
		if (isBannedCached(player, ip)) {
			result.accept("This account is banned.");
			return;
		}
		pool.execute(() -> {
			String reason;
			JsonObject res = null;
			try {
				res = api.post("/api/game/login", WebsiteApi.obj("player", player, "ip", ip));
				reason = res.has("allow") && res.get("allow").getAsBoolean() ? null : str(res, "reason", "Login refused.");
			} catch (IOException e) {
				LOG.log(Level.WARNING, "Website login check failed for " + player, e);
				reason = config.allowLoginWhenSiteDown ? null : "Login server unavailable, try again shortly.";
			}
			final String finalReason = reason;
			final JsonObject finalRes = res;
			game.runOnGameThread(() -> {
				result.accept(finalReason);
				if (finalReason == null && finalRes != null) {
					JsonElement tier = finalRes.get("tier");
					game.setDonatorTier(player, tier != null && tier.isJsonObject() ? str(tier.getAsJsonObject(), "key", null) : null);
					int pending = finalRes.has("pendingRewards") ? finalRes.get("pendingRewards").getAsInt() : 0;
					if (pending > 0) game.sendMessage(player, "You have " + pending + " reward(s) waiting. Type ::claim to collect them.");
				}
			});
		});
	}

	// ---- commands -------------------------------------------------------------------

	/** ::vote */
	public void commandVote(String player) {
		openLink(player, "/api/game/vote-link", "Opening the vote page...");
	}

	/** ::donate */
	public void commandDonate(String player) {
		openLink(player, "/api/game/donate-link", "Opening the donation store...");
	}

	private void openLink(String player, String path, String message) {
		pool.execute(() -> {
			try {
				JsonObject res = api.post(path, WebsiteApi.obj("player", player));
				String url = str(res, "url", null);
				game.runOnGameThread(() -> {
					if (url == null) {
						game.sendMessage(player, "That isn't available right now.");
						return;
					}
					game.sendMessage(player, message);
					game.openUrl(player, url);
				});
			} catch (IOException e) {
				LOG.log(Level.WARNING, "Website link failed", e);
				game.runOnGameThread(() -> game.sendMessage(player, "Couldn't reach the website. Try again in a moment."));
			}
		});
	}

	/** ::claim - collects everything the site has queued (votes, donations, promos, custom orders). */
	public void commandClaim(String player) {
		String k = key(player);
		if (!claiming.add(k)) return;
		pool.execute(() -> {
			try {
				JsonObject res = api.post("/api/game/claims", WebsiteApi.obj("player", player));
				if (res.has("error")) {
					claiming.remove(k);
					game.runOnGameThread(() -> game.sendMessage(player, "You can't claim rewards: " + str(res, "reason", "account blocked") + "."));
					return;
				}
				String batchId = str(res, "batchId", null);
				List<Reward> rewards = new ArrayList<>();
				for (JsonElement el : res.getAsJsonArray("rewards")) rewards.add(toReward(el.getAsJsonObject()));
				if (batchId == null || rewards.isEmpty()) {
					claiming.remove(k);
					game.runOnGameThread(() -> game.sendMessage(player, "You have nothing to claim."));
					return;
				}
				game.runOnGameThread(() -> deliver(player, batchId, rewards));
			} catch (IOException e) {
				claiming.remove(k);
				LOG.log(Level.WARNING, "Claim failed for " + player, e);
				game.runOnGameThread(() -> game.sendMessage(player, "Couldn't reach the website. Your rewards are safe - try again shortly."));
			}
		});
	}

	private void deliver(String player, String batchId, List<Reward> rewards) {
		JsonArray delivered = new JsonArray();
		int left = 0;
		if (game.isOnline(player)) {
			for (Reward r : rewards) {
				boolean ok;
				if (r.itemId != null) ok = game.giveItem(player, r.itemId, r.amount);
				else if ("VOTE_POINTS".equals(r.code) || "DONATOR_POINTS".equals(r.code)) {
					game.addPoints(player, r.code, r.amount);
					ok = true;
				} else ok = game.giveReward(player, r);
				if (ok) {
					delivered.add(r.id);
					game.sendMessage(player, "Claimed: " + r.label + (r.amount > 1 ? " x" + r.amount : ""));
				} else left++;
			}
			if (left > 0) game.sendMessage(player, left + " reward(s) didn't fit. Make room and type ::claim again.");
		}
		final JsonObject body = WebsiteApi.obj("batchId", batchId, "delivered", delivered);
		pool.execute(() -> {
			for (int attempt = 0; attempt < 5; attempt++) {
				try {
					api.post("/api/game/claims/complete", body);
					break;
				} catch (IOException e) {
					LOG.log(Level.WARNING, "claims/complete failed (attempt " + (attempt + 1) + ") batch " + batchId, e);
					sleep(2000L << attempt);
				}
			}
			claiming.remove(key(player));
		});
	}

	/** ::link CODE - ties the character to a forum account. */
	public void commandLink(String player, String code) {
		pool.execute(() -> {
			String msg;
			try {
				msg = str(api.post("/api/game/link", WebsiteApi.obj("player", player, "code", code)), "message", "Done.");
			} catch (IOException e) {
				msg = "Couldn't reach the website. Try again shortly.";
			}
			final String m = msg;
			game.runOnGameThread(() -> game.sendMessage(player, m));
		});
	}

	/**
	 * Call after removing a donator pin from the player's inventory. Counts toward
	 * the player's donator tier; the new tier is applied through {@link GameHooks#setDonatorTier}.
	 */
	public void onPinRedeemed(String player, int valueCents) {
		String redeemId = UUID.randomUUID().toString();
		JsonObject body = WebsiteApi.obj("player", player, "valueCents", valueCents, "redeemId", redeemId);
		pool.execute(() -> {
			for (int attempt = 0; attempt < 6; attempt++) {
				try {
					JsonObject res = api.post("/api/game/pins/redeem", body);
					JsonElement tier = res.get("tier");
					String tierKey = tier != null && tier.isJsonObject() ? str(tier.getAsJsonObject(), "key", null) : null;
					String tierName = tier != null && tier.isJsonObject() ? str(tier.getAsJsonObject(), "name", null) : null;
					int total = res.get("claimedCents").getAsInt();
					game.runOnGameThread(() -> {
						game.setDonatorTier(player, tierKey);
						game.sendMessage(player, String.format("Pin claimed. Total: $%.2f%s", total / 100.0, tierName != null ? " - " + tierName : ""));
					});
					return;
				} catch (IOException e) {
					// Same redeemId on every retry, so the site never counts it twice.
					LOG.log(Level.WARNING, "Pin redeem failed (attempt " + (attempt + 1) + ") " + redeemId, e);
					sleep(2000L << attempt);
				}
			}
			LOG.severe("Pin redeem gave up: " + body);
		});
	}

	// ---- data pushes ----------------------------------------------------------------

	/** Call every minute. */
	public void pushPlayerCount(int online) {
		pool.execute(() -> {
			try {
				api.post("/api/game/status", WebsiteApi.obj("playersOnline", online));
			} catch (IOException e) {
				LOG.log(Level.FINE, "status push failed", e);
			}
		});
	}

	/** players: [{name, skills:{ATTACK:{level,xp},...}}], up to 500 per call. */
	public void pushHiscores(JsonArray players) {
		pushAsync("/api/game/hiscores", WebsiteApi.obj("players", players));
	}

	/** items: [{id, name, examine?, value?, category?, bonuses?}], up to 1000 per call. */
	public void pushItems(JsonArray items) {
		pushAsync("/api/game/items", WebsiteApi.obj("items", items));
	}

	/** npcs: [{name, combatLevel?, isBoss?, drops:[{itemId, itemName?, rate:"1/512"}]}], up to 200 per call. */
	public void pushDrops(JsonArray npcs) {
		pushAsync("/api/game/drops", WebsiteApi.obj("npcs", npcs));
	}

	private void pushAsync(String path, JsonObject body) {
		pool.execute(() -> {
			try {
				JsonObject res = api.post(path, body);
				LOG.info(path + " -> " + res);
			} catch (IOException e) {
				LOG.log(Level.WARNING, path + " failed", e);
			}
		});
	}

	// ---- bans + events --------------------------------------------------------------

	private void refreshBans() {
		try {
			JsonObject res = api.get("/api/game/bans");
			Set<String> players = ConcurrentHashMap.newKeySet();
			Set<String> ips = ConcurrentHashMap.newKeySet();
			for (JsonElement p : res.getAsJsonArray("players")) players.add(str(p.getAsJsonObject(), "player", ""));
			for (JsonElement i : res.getAsJsonArray("ips")) ips.add(str(i.getAsJsonObject(), "ip", ""));
			bannedPlayers.retainAll(players);
			bannedPlayers.addAll(players);
			bannedIps.retainAll(ips);
			bannedIps.addAll(ips);
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.WARNING, "Ban list refresh failed", e);
		}
	}

	private void pollEvents() {
		if (!polling.compareAndSet(false, true)) return;
		try {
			while (true) {
				JsonObject res = api.get("/api/game/events?after=" + lastSeq + "&limit=200");
				JsonArray events = res.getAsJsonArray("events");
				if (events.size() == 0) break;
				for (JsonElement el : events) {
					JsonObject ev = el.getAsJsonObject();
					handleEvent(ev);
					lastSeq = ev.get("seq").getAsInt();
				}
				writeSeq(lastSeq);
				api.post("/api/game/events", WebsiteApi.obj("upTo", lastSeq));
				if (events.size() < 200) break;
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.FINE, "Event poll failed", e);
		} finally {
			polling.set(false);
		}
	}

	private void handleEvent(JsonObject ev) {
		String type = str(ev, "type", "");
		String player = str(ev, "player", null);
		JsonObject data = ev.has("data") && ev.get("data").isJsonObject() ? ev.getAsJsonObject("data") : new JsonObject();
		switch (type) {
			case "PLAYER_BANNED": {
				if (player != null) bannedPlayers.add(player);
				if (data.has("ips")) for (JsonElement ip : data.getAsJsonArray("ips")) bannedIps.add(ip.getAsString());
				String reason = str(data, "reason", "Banned");
				if (player != null) game.runOnGameThread(() -> {
					if (game.isOnline(player)) game.kick(player, reason);
				});
				break;
			}
			case "PLAYER_UNBANNED":
				if (player != null) bannedPlayers.remove(player);
				pool.execute(this::refreshBans);
				break;
			case "TIER_CHANGED": {
				String tier = str(data, "tier", null);
				if (player != null) game.runOnGameThread(() -> {
					if (game.isOnline(player)) game.setDonatorTier(player, tier);
				});
				break;
			}
			case "VOTE_RECEIVED":
			case "REWARDS_PENDING":
			case "DONATION_PAID": {
				String msg = "DONATION_PAID".equals(type)
						? "Thank you for your donation! Type ::claim to collect your rewards."
						: "VOTE_RECEIVED".equals(type)
						? "Your vote on " + str(data, "site", "the toplist") + " was received! Type ::claim."
						: "You have new rewards waiting. Type ::claim to collect them.";
				if (player != null) game.runOnGameThread(() -> {
					if (game.isOnline(player)) game.sendMessage(player, msg);
				});
				break;
			}
			case "ACCOUNT_LINKED":
				if (player != null) game.runOnGameThread(() -> {
					if (game.isOnline(player)) game.sendMessage(player, "Linked to forum account " + str(data, "forumAccount", "") + ".");
				});
				break;
			default:
				break;
		}
	}

	// Push endpoint: verifies the HMAC, then just triggers a poll so every event
	// is handled once, in order, through the same path.
	private void startWebhook() {
		try {
			webhook = HttpServer.create(new InetSocketAddress(config.webhookPort), 0);
			webhook.createContext("/gadscape/events", exchange -> {
				byte[] body;
				try (InputStream in = exchange.getRequestBody()) {
					body = in.readAllBytes();
				}
				String sig = exchange.getRequestHeaders().getFirst("X-GadScape-Signature");
				boolean ok = sig != null && MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8), sign(body).getBytes(StandardCharsets.UTF_8));
				exchange.sendResponseHeaders(ok ? 200 : 401, -1);
				exchange.close();
				if (ok) pool.execute(this::pollEvents);
			});
			webhook.setExecutor(pool);
			webhook.start();
			LOG.info("Website webhook listening on :" + config.webhookPort + "/gadscape/events");
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Couldn't start website webhook listener", e);
		}
	}

	private String sign(byte[] body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(config.apiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			StringBuilder hex = new StringBuilder("sha256=");
			for (byte b : mac.doFinal(body)) hex.append(String.format("%02x", b));
			return hex.toString();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	// ---- helpers ------------------------------------------------------------------

	private static Reward toReward(JsonObject o) {
		return new Reward(
				str(o, "id", ""),
				str(o, "code", "ITEM"),
				o.has("itemId") && !o.get("itemId").isJsonNull() ? o.get("itemId").getAsInt() : null,
				o.has("amount") ? o.get("amount").getAsInt() : 1,
				o.has("valueCents") && !o.get("valueCents").isJsonNull() ? o.get("valueCents").getAsInt() : null,
				str(o, "label", "Reward"),
				str(o, "source", ""));
	}

	private static String str(JsonObject o, String k, String def) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
	}

	private int readSeq() {
		try {
			return Integer.parseInt(Files.readString(Paths.get(config.stateFile)).trim());
		} catch (Exception e) {
			return 0;
		}
	}

	private void writeSeq(int seq) {
		try {
			Path p = Paths.get(config.stateFile);
			if (p.getParent() != null) Files.createDirectories(p.getParent());
			Files.writeString(p, Integer.toString(seq));
		} catch (IOException e) {
			LOG.log(Level.WARNING, "Couldn't save event position", e);
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
