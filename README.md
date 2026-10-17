# gadscape

GadScape game server (`server/`, Near Reality / Zenyte based) and its link to
the website (`Uzi-Cohen/siteforrsps`).

`server/data/`, the cache binaries and `worlds.yml` are not in git: take them
from the latest release zip (`gadscape-full-*.zip`).

## Website link

`server/core/src/main/kotlin/com/gadscape/website/WebsiteService.kt` connects the
game to gadscape.com. Turn it on per world in `worlds.yml`:

```yaml
    website:
      enabled: true
      siteUrl: "https://gadscape.com"
      apiKey: "<same as GAME_API_KEY on the website>"
      webhookPort: 43600        # optional; 0 = poll only
      pollSeconds: 15
      allowLoginWhenSiteDown: true
```

If `webhookPort` is set, open that port and set the website's
`GAME_SERVER_WEBHOOK_URL` to `http://<server ip>:43600/gadscape/events`.

| In game | What happens |
|---|---|
| `::vote` | opens the vote page signed in as the character; toplists confirm to the website |
| `::donate` / `::store` | opens the donation store |
| `::claim` | collects everything the website queued: pins, mystery boxes, vote/donator points, custom orders, staff rewards (inventory first, then bank) |
| `::link CODE` | links the character to a forum account |
| `::dpoints` | shows donator points, pins claimed, vote points |
| `::websitesync` (admin) | sends item definitions and drop tables to the website |
| using a donator pin | adds its value to the website total; rank goes up automatically |
| logging in | website bans, IP bans and VPN/proxy block are enforced; IP and location are logged |

Donator ranks (by pins claimed): $10 Donator → Premium crown, $100 Super →
Expansion, $200 Elite → Extreme, $500 Extreme → Respected, $1000 Respected →
Legendary, $5000 Platinum → Uber.

With `website.enabled: false` everything falls back to the old in-game vote
and credit store interfaces.
