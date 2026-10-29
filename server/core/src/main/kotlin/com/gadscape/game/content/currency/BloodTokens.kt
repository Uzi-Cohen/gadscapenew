package com.gadscape.game.content.currency

import com.gadscape.game.item.CustomItemId
import com.gadscape.game.model.item.getItemValue
import com.zenyte.game.item.Item
import com.zenyte.game.item.ItemId
import com.zenyte.game.world.entity.player.container.ContainerResult
import com.zenyte.game.world.entity.player.container.ContainerState
import com.zenyte.game.world.entity.player.container.RequestResult
import java.text.NumberFormat

/**
 * Blood tokens (item 13307, the old Blood money, 1:1) are GadScape's only currency; coins are gone.
 *
 * - 1 token = 1,000 coins. Every coin amount the content still names (fees, wages, shop prices, item values) is that
 *   many coins' worth of tokens: a cost of `c` coins is `c / 1000` tokens, at least 1 ([costInTokens]).
 * - Containers ([com.zenyte.game.world.entity.player.container.Container], the bank) answer for coins in tokens:
 *   "how many coins" is tokens x 1000, taking `c` coins takes [costInTokens] tokens, and coins put in become
 *   [payoutInTokens] tokens (rounded down), or nothing when [coinPayoutsAsTokens] is off. So no coin item is ever
 *   held again, and content written for coins charges tokens.
 * - Coins held when this went live are turned into tokens once at login ([CoinConversion]).
 * - A Blood card ([CARD]) is 1,000 tokens, made and cashed at a bank ([BloodCards]).
 */
object BloodTokens {

    const val TOKEN = ItemId.BLOOD_MONEY
    const val CARD = CustomItemId.BLOOD_CARD
    const val COINS = ItemId.COINS_995
    const val PLATINUM_TOKEN = ItemId.PLATINUM_TOKEN

    const val COINS_PER_TOKEN = 1000
    const val TOKENS_PER_CARD = 1000

    /**
     * THE switch for coin drops from NPCs (NPC.dropItem): off, so monsters drop no coins and nothing replaces them.
     * PvM and skilling token rewards are planned separately.
     */
    @JvmField
    var npcCoinDrops = false

    /**
     * Coins that content still hands out (a refund, a Grand Exchange sale, a casket) become that many coins' worth of
     * tokens, rounded down (true), or nothing (false). Monster drops never get here while [npcCoinDrops] is off.
     */
    @JvmField
    var coinPayoutsAsTokens = true

    /** Tokens, cards, and the coins / platinum tokens they replaced. */
    @JvmStatic
    fun isCurrency(id: Int): Boolean = id == TOKEN || id == CARD || id == COINS || id == PLATINUM_TOKEN

    /** Tokens for a cost of [coins] coins: divided by 1,000, rounded up, at least 1 (0 for nothing). */
    @JvmStatic
    fun costInTokens(coins: Long): Int {
        if (coins <= 0) return 0
        val tokens = (coins + COINS_PER_TOKEN - 1) / COINS_PER_TOKEN
        return if (tokens > Int.MAX_VALUE) Int.MAX_VALUE else tokens.toInt()
    }

    /** Tokens for [coins] coins paid to a player: divided by 1,000, rounded down. */
    @JvmStatic
    fun payoutInTokens(coins: Long): Int {
        if (coins <= 0) return 0
        val tokens = coins / COINS_PER_TOKEN
        return if (tokens > Int.MAX_VALUE) Int.MAX_VALUE else tokens.toInt()
    }

    /** What [tokens] tokens are worth in coins, for content that still counts in coins (capped at the int max). */
    @JvmStatic
    fun asCoins(tokens: Int): Int {
        val coins = tokens.toLong() * COINS_PER_TOKEN
        return if (coins > Int.MAX_VALUE) Int.MAX_VALUE else coins.toInt()
    }

    /**
     * A shop's buy price in tokens for a coin price: 0 (free) and negative (not sold) are kept, anything else is
     * divided by 1,000, at least 1.
     */
    @JvmStatic
    fun shopBuyPrice(coins: Int): Int = if (coins <= 0) coins else maxOf(1, coins / COINS_PER_TOKEN)

    /**
     * A shop's sell price (what it pays) in tokens for a coin price, rounded down. Negative (won't buy) and 0 are kept;
     * an item worth less than one token can't be sold (-1), so junk can't be turned into tokens.
     */
    @JvmStatic
    fun shopSellPrice(coins: Int): Int = if (coins <= 0) coins else (coins / COINS_PER_TOKEN).takeIf { it > 0 } ?: -1

    /** One [itemId]'s value in tokens, for risk and price checks: its coin value divided by 1,000. */
    @JvmStatic
    fun tokenValue(itemId: Int): Long = when (itemId) {
        TOKEN, PLATINUM_TOKEN -> 1L
        CARD -> TOKENS_PER_CARD.toLong()
        COINS -> 0L
        else -> getItemValue(itemId).toLong() / COINS_PER_TOKEN
    }

    /** [item]'s stack value in tokens. */
    @JvmStatic
    fun tokenValue(item: Item?): Long = if (item == null) 0L else tokenValue(item.id) * item.amount

    /** The tokens to take for [item] when it is coins (see [costInTokens]); any other item as it is. */
    @JvmStatic
    fun asCost(item: Item): Item =
        if (item.id == COINS) Item(TOKEN, costInTokens(item.amount.toLong())) else item

    /**
     * The tokens to give for [item] when it is coins (see [payoutInTokens]), or null when nothing is given; any
     * other item as it is.
     */
    @JvmStatic
    fun asPayout(item: Item): Item? {
        if (item.id != COINS) return item
        if (!coinPayoutsAsTokens) return null
        val tokens = payoutInTokens(item.amount.toLong())
        return if (tokens <= 0) null else Item(TOKEN, tokens)
    }

    /** The result of putting coins somewhere when nothing is given for them ([asPayout] null): done, nothing added. */
    @JvmStatic
    fun nothingAdded(item: Item): ContainerResult = ContainerResult(item, ContainerState.ADD).also {
        it.succeededAmount = item.amount
        it.result = RequestResult.SUCCESS
    }

    @JvmStatic
    fun format(tokens: Long): String = NumberFormat.getIntegerInstance().format(tokens) + if (tokens == 1L) " blood token" else " blood tokens"
}
