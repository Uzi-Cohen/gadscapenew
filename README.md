# gadscape

| Folder | What |
|---|---|
| `server/` | Game server (Kotlin/Java, revision 211) |
| `client/` | Game client (RuneLite/OpenOSRS-based fork) |
| `editor/` | Uzi's Map Editor: maps, objects, NPCs, shops, items, models, animations |

`server/data/`, the packed cache, `worlds.yml` and the client installers are
not in git: take them from the latest release zip (`gadscape-full-*.zip`).

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

Donator ranks (by pins claimed) show in game under the website's names: $10
Donator, $100 Super, $200 Elite, $500 Extreme, $1000 Respected, $5000 Platinum.
They reuse the server's existing crowns and perks (Premium, Expansion, Extreme,
Respected, Legendary and Uber in the code). Every donator and every staff member
can use `::bank`.

With `website.enabled: false`, `::vote` and `::donate` open the plain website
pages and pins are added to a total kept on the character.

## Editing the Universal Shop

The shop with all the tabs (Melee, Ranged, Magic, ... Vote) is packed into the
cache. Its items and prices are in
`server/core-model/src/main/kotlin/com/gadscape/game/content/universalshop/UnivShopTable.kt`
and the tabs are in `UnivShopCategory.kt` in the same folder. After editing, rebuild the cache
(`./gradlew :app:runProductionUpdate` packs and starts; `generateCacheProd`
only packs). Players get the new cache automatically. In game, `::usm` lets
staff switch items on or off, change prices or close the shop without a rebuild.

Item names and other cache content are in `server/cache/assets/`.
