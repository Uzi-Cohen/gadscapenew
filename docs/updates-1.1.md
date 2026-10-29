# GadScape 1.1 - live server patch guide

## What's in 1.1

- **ToA**: invocation bits fixed (TOAPartySettings).
- **Party room**: the Party Drop Chest uses build 241's chest screen; the lever drops the chest's items at once
  (no knight dance). `::party` teleports to the party room; `::dropparty` (developers) opens a staff drop party chest.
- **YouTuber rank**: every Divine Gad Donator perk (the saved donator rank isn't changed) and the YouTuber crown.
  `::giveaway` opens the giveaway screen (with a confirm screen); `::giveawayitems` (administrators) sets the
  approved prizes. Prizes for offline winners wait until they log in.
- **Premade Kits tab** replaces the music tab (music still plays by area). Kit items vanish on death.
- **Blood tokens** (item 13307, the old Blood money) replace coins: coins are converted at login, shop prices are
  divided by 1,000, blood cards (1,000 tokens) are made and cashed at banks, PK kills pay 100-500 tokens by the
  victim's risk plus 10% per kill of streak (max +100%).
- **Donator ranks** are the website's six Gad ranks: Gad Donator $9.95, Super $29.95, Extreme $89.95, Legendary
  $189.95, Royal $499.95, Divine $999.95. A rank is bought (DONATOR_RANK on `::claim`), redeemed from a rank ticket
  or reached by the lifetime total donated, and never goes down. Old saves load as the rank the same money buys now
  (Donator -> Gad Donator, Super -> Extreme, Elite -> Legendary, Extreme -> Royal, Respected/Mythical/Platinum ->
  Divine). Perks per rank: drop rate 15-45%, presets, Slayer points and task costs, Bounty Hunter skips, CoX purple
  chance, `::bank` (Super+), free instances (Extreme+), `::spec` / `::hp` / `::unskull` (Legendary+), no AFK time
  limit (Royal+), `::settele` / `::tele 1-5` (Divine). `::donator` / `::benefits` shows the table.
- **Donator items**: six rank tickets (50001-50006, Redeem), the stackable $1 Donator pin (50007, Redeem / Combine)
  and Donator bonds $10/$20/$50/$100/$250/$1000 (50008-50013, Redeem). Pins and bonds go through the existing pin
  redemption (WebsiteService.redeemPin, Bond enum), 1 donator point per dollar.
- **Voting**: `::vote` opens the vote screen (six sites, per-site Vote / Claim), the vote ticket replaces the vote
  shard, the vote mystery box every 3rd vote, a weekly vote lottery, vote perks (blood tokens, skilling streak boost,
  +20% PvM drop rate for 20 minutes per vote, AFK hours by rank), vote-only pets, the Voter's crown, sceptre and cape,
  and the vote shop.
- **Store**: `::donate` / `::store` opens the in-game store (cart, checkout, open website, claim list, weekly offer,
  promotion checkpoints). Items for players waiting to claim them are kept in PendingRewards.

## Deploy

1. Stop nothing yet. Build the server: `./gradlew :app:shadowJar` in `server/`.
2. Rebuild `data/plugins.dat`: `./gradlew :app:runPluginScanner` (or run `com.zenyte.plugins.PluginScanner`). New
   plugin classes (vote mystery box, donator items, kits, store, giveaway, donator perks) are only found this way.
3. Patch the live build 241 cache (close the server, client and editor that use it first):
   - with the editor: Deploy to server (any deploy that sends the cache runs `CacheEdits.fixBeforeShipping`, which
     now runs the six patches on a 241 export cache, each logged and skipped on its own if it fails), or
   - by hand, each on the cache folder, in this order:
     `java -cp <editor fat jar> utils.PartyGiveawayPatch <cache241>`, then `utils.KitsPatch`,
     `utils.CurrencyDonatorPatch`, `utils.VoteItemsPatch`, `utils.StorePatch`, `utils.VotePatch`.
   All patches are idempotent: running them again changes nothing.
4. Copy the new shadow jar, `plugins.dat` and the patched cache to the server, then restart it.
5. Clients must download the new cache (the screens, tab, items and pets are cache content). Without the patched
   cache the server falls back: `::donate` opens the website, `::vote` opens the vote page, the music tab stays.

## Data files created at runtime

| File | What |
| --- | --- |
| `data/giveaways.json` | approved giveaway prizes, prizes waiting for their winner, recent giveaways |
| `data/pending-rewards.json` | items waiting to be claimed (lottery wins, promotion checkpoints, weekly offer) |
| `data/store-week.json` | money paid this week per player, when the website can't tell it |
| `data/vote-lottery.json` | the weekly vote lottery's entries and prize |
| `data/vote-sites.json` | optional, read at start-up: each vote site's page and on/off |
| `data/logs/giveaways.log`, `data/logs/vote-lottery.log` | giveaway and lottery history |

Back them up with the character saves.

## New commands

- Players: `::vote`, `::votelottery`, `::donate` / `::store`, `::party`, `::donator` / `::benefits`, `::dzone`,
  `::bank` (Super+), `::spec`, `::hp`, `::unskull` (Legendary+), `::settele 1-5`, `::tele 1-5` (Divine).
- YouTubers: `::giveaway`.
- Administrators: `::giveawayitems`, `::votelottery prize <itemId> <amount>`, `::storeoffer <text>|default`,
  `::storeofferreward <item id> <every $>|none`.
- Developers: `::dropparty`, `::setdonated <dollars> [player]`.

## Still needs the website

See `docs/store-website-api.md`. Until the website has them:

- the store's cart (`cart=` on the store link), `GET /api/game/store/week` and `GET /api/game/claims/pending`: the
  store screen shows what this server saw claimed and one "Website purchases" row that claims like `::claim`;
- the vote link's `site` parameter (`POST /api/game/vote-link`) and a `site` on VOTE_RECEIVED events and vote
  rewards: without them votes still arrive, but Claim can't tell the sites apart;
- DONATOR_RANK rewards (rank 1-6) and `donatedCents` on purchase rewards: the rank comes from the lifetime total
  (pins plus claimed purchases), not from the website's tier key, so purchases only count once the website sends
  their amount.
