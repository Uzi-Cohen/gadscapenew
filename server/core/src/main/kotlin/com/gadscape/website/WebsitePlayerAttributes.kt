package com.gadscape.website

import com.zenyte.game.world.entity.persistentAttribute
import com.zenyte.game.world.entity.player.Player

/** Donator points granted by the website (promotions, per-dollar points). Spend them however the staff decide. */
var Player.donatorPoints: Int by persistentAttribute("website_donator_points", 0)

/** Total value of donator pins this player has redeemed, in cents, as last reported by the website. */
var Player.claimedPinCents: Int by persistentAttribute("website_claimed_pin_cents", 0)
