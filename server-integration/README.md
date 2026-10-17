# GadScape website integration (game server side)

Drop-in Java package that connects the game server to the website:
`::vote`, `::donate`, `::claim`, `::link`, donator pins → donator ranks,
login checks (bans, VPN/proxy, IP logging), instant notifications from the
site, and pushing player count / hiscores / items / drop tables.

The website side lives in `Uzi-Cohen/siteforrsps` - its
`docs/game-integration.md` lists every endpoint.

Needs Java 11+ and Gson 2.8.6+ (already on the classpath of most RSPS bases).

## Setup

1. Copy `src/main/java/com/gadscape/website` into the server source.
2. Set the config (env vars or your own config file):

   | Env var | Value |
   |---|---|
   | `GADSCAPE_SITE_URL` | `https://gadscape.com` |
   | `GADSCAPE_GAME_API_KEY` | same as `GAME_API_KEY` on the site |
   | `GADSCAPE_WEBHOOK_PORT` | e.g. `43600` - then set the site's `GAME_SERVER_WEBHOOK_URL` to `http://<server-ip>:43600/gadscape/events`. `0` = poll only (every 15s). |

3. Implement `GameHooks` against your server's player and item code, create
   the integration once at startup and call `start()`.

```java
WebsiteIntegration website = new WebsiteIntegration(new WebsiteConfig(), new GameHooks() {
    public void runOnGameThread(Runnable task) { WorldTasksManager.schedule(task::run); }
    public boolean isOnline(String name) { return World.getPlayer(name).isPresent(); }
    public void sendMessage(String name, String msg) { World.getPlayer(name).ifPresent(p -> p.sendMessage(msg)); }
    public void openUrl(String name, String url) { World.getPlayer(name).ifPresent(p -> p.getPacketDispatcher().sendURL(url)); }
    public boolean giveItem(String name, int id, int amount) {
        Player p = World.getPlayer(name).orElse(null);
        if (p == null) return false;
        if (p.getInventory().hasFreeSlots()) { p.getInventory().addItem(id, amount); return true; }
        return p.getBank().add(new Item(id, amount));
    }
    public void addPoints(String name, String type, int amount) { /* vote / donator points */ }
    public boolean giveReward(String name, Reward r) {
        // Rewards without an item id: map PIN (by r.valueCents), MYSTERY_BOX, CUSTOM_ITEM ...
        return false; // false = stays queued for the next ::claim
    }
    public void setDonatorTier(String name, String tier) { /* apply the rank */ }
    public void kick(String name, String reason) { World.getPlayer(name).ifPresent(Player::logout); }
});
website.start();
```

(The method names inside the hooks are examples - use whatever your base calls them.)

## Wiring

| Where | Call |
|---|---|
| `::vote` | `website.commandVote(player.getUsername())` |
| `::donate` | `website.commandDonate(player.getUsername())` |
| `::claim` | `website.commandClaim(player.getUsername())` |
| `::link CODE` | `website.commandLink(player.getUsername(), code)` |
| Login handshake | `if (website.isBannedCached(name, ip)) reject;` |
| After login | `website.onLogin(name, ip, reason -> { if (reason != null) kick(reason); })` |
| Player uses a donator pin | remove the pin, then `website.onPinRedeemed(name, valueCents)` |
| Every minute | `website.pushPlayerCount(World.getPlayers().size())` |
| Hiscores save / on a timer | `website.pushHiscores(players)` |
| Startup / after content changes | `website.pushItems(items)`, `website.pushDrops(npcs)` |
| Shutdown | `website.stop()` |

### Donator pins

The site splits every donation into pins ($10, $25, $50, $100, $250, $500,
$1000 by default) and sends them as `PIN` rewards. Set each pin's game item id
in the site's Admin → Rewards & Promos and `::claim` gives the item directly;
otherwise `giveReward` gets the `PIN` with its `valueCents`.

When a player uses a pin, call `onPinRedeemed` - the site adds it to their
total and returns their rank:

| Pins claimed | Rank (`tierKey`) |
|---|---|
| $10+ | Donator (`REGULAR`) |
| $100+ | Super (`SUPER`) |
| $200+ | Elite (`ELITE`) |
| $500+ | Extreme (`EXTREME`) |
| $1000+ | Respected (`RESPECTED`) |
| $5000+ | Platinum (`PLATINUM`) |

### Bans

Refunded or charged-back donations ban the character, the forum account and
every IP they used. The ban list is loaded at startup, refreshed every 5
minutes and updated instantly by `PLAYER_BANNED` events (online players are
kicked).
