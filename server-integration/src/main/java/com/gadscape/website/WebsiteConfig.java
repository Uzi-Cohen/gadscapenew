package com.gadscape.website;

/** Settings for the website link. Load from your server config / env. */
public final class WebsiteConfig {
	/** e.g. https://gadscape.com (no trailing slash). */
	public String siteUrl = System.getenv().getOrDefault("GADSCAPE_SITE_URL", "https://gadscape.com");
	/** Same value as GAME_API_KEY on the site. */
	public String apiKey = System.getenv().getOrDefault("GADSCAPE_GAME_API_KEY", "");
	/** Port for the site's push webhook (GAME_SERVER_WEBHOOK_URL = http://<server>:port/gadscape/events). 0 = off, poll only. */
	public int webhookPort = Integer.parseInt(System.getenv().getOrDefault("GADSCAPE_WEBHOOK_PORT", "0"));
	/** How often to poll /api/game/events even without a push. */
	public int pollSeconds = 15;
	/** How often to reload the full ban list. */
	public int banRefreshSeconds = 300;
	/** Where the last processed event number is kept between restarts. */
	public String stateFile = "data/website-events.seq";
	/**
	 * If the site can't be reached during login: true lets the player in (cached bans still apply),
	 * false refuses the login. The site blocks VPNs, so false is the strict choice.
	 */
	public boolean allowLoginWhenSiteDown = true;
}
