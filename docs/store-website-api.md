# In-game store: what the website must add

The game's `::donate` now opens an in-game store screen (interface 5265, editor `utils.StorePatch`; server
`com.gadscape.game.content.store`). Nothing is sold in game: the screen builds a cart and opens the website's store
page through the signed link the website already makes. It also shows the player's claim list and a "paid this week"
total. Everything below is called with the usual game API key (`Authorization: Bearer <GAME_API_KEY>`), like the
other `/api/game/*` routes. The game works without any of it (it falls back as described); with it, the screen is exact.

## 1. Cart on the store link

`POST /api/game/donate-link` (exists) now gets an optional `cart` field:

    { "player": "Some Name", "cart": "pin1:2,bond10:1,bond50:3" }

and the game appends the same `cart=` parameter to the `url` it gets back (before any `#fragment`, unless the url
already has `cart=`):

    https://gadscape.com/store?token=...&cart=pin1:2,bond10:1,bond50:3

Format: comma separated `<key>:<quantity>`, quantity 1-99, each key at most once. The keys never change:

| key | item (build 241 id) | price |
|---|---|---|
| `pin1` | Donator pin (50007) | $1 |
| `bond10` | $10 bond (50008) | $10 |
| `bond20` | $20 bond (50009) | $20 |
| `bond50` | $50 bond (50010) | $50 |
| `bond100` | $100 bond (50011) | $100 |
| `bond250` | $250 bond (50012) | $250 |
| `bond1000` | $1000 bond (50013) | $1000 |

What the website should do: when the store page opens with `cart`, put those products in the cart (prices are the
website's own; the game only shows them). Unknown keys are ignored. Either read the parameter from the page url, or
put the body's `cart` into the signed link yourself (then the game doesn't add it again, as the url has `cart=`).
If the parameter is ignored, the player still lands on the store; "Open store website" opens the link without a cart.

## 2. Paid this week

    GET /api/game/store/week?player=<name>&from=<epoch ms>&to=<epoch ms>
    -> { "cents": 5200 }

`from` / `to` are the game's week (Monday 00:00 up to next Monday 00:00, the game server's time zone); please count
payments made in `[from, to)`. `cents` = money the player actually paid in that window (card / PayPal, completed and
not refunded), in US cents. Bonds or pins redeemed don't count; only money paid. `player` is the character name
(same normalisation as the other routes).

The game asks at login and whenever the store screen opens (and after a claim). Its uses: the Promotion checkpoints
bar ($25 / $50 / $100 / $250) and the weekly offer (by default 1 Cosmetic mystery box per $20). The game gives those
rewards itself, once per week per checkpoint, on its own claim list; the website doesn't queue them.

Until this exists (any non-2xx, e.g. 404), the game counts the purchases it has seen claimed this week (see 4).

## 3. Claim list without claiming

    GET /api/game/claims/pending?player=<name>
    -> { "rewards": [ { "id": "...", "label": "$10 bond", "amount": 2, "itemId": 50008, "code": "PIN" }, ... ] }

The rewards `POST /api/game/claims` would hand out right now, without reserving anything (read only). `label` and
`amount` are shown; `itemId` (build 241) gives the row's item icon (may be missing). Claiming still goes through the
existing `POST /api/game/claims` + `POST /api/game/claims/complete` (batch, journal, confirmation: nothing is given twice).
A Claim button on a website row claims the whole batch, like `::claim`.

Until this exists, the screen shows one "Website purchases" row whose Claim does a `::claim`.

## 4. Paid amount on claimed rewards (for the fallback)

When section 2 isn't there, the game adds up, per week, the rewards it hands out from `POST /api/game/claims`:

1. `paidCents` on the reward, when present: money paid for this reward (please add it; 0 for votes, promotions, staff grants);
2. else `donatedCents` (or `amountCents`) on a non-PIN reward;
3. else for a `PIN` reward whose `source` is `DONATION`, `PURCHASE`, `STORE`, `ORDER` or `CUSTOM_ORDER`: `valueCents` x `amount`.

Each reward counts once (by its `id`). With section 2 in place this is only a backup.

## Not needed from the website

- The weekly offer text is set in game by admins (`::storeoffer <text>`, `::storeoffer default`,
  `::storeofferreward <item id> <every $> | none`).
- Checkpoint / offer rewards, lottery wins and offline gifts are kept by the game (data/pending-rewards.json) and
  claimed in the same panel.
