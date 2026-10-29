package com.zenyte.game.model.shop

enum class ShopCurrency(
    override val id: Int = -1,
    override val maximumAmount: Int = Int.MAX_VALUE,
    override val isStackable: Boolean = true,
    override val isPhysical: Boolean = true
) : CurrencyPalette {
    BLOOD_MONEY(13307) {
        override fun toString(): String = "blood tokens"
    },
    /**
     * Coins are gone: a coin shop is paid in blood tokens (13307), its prices divided by 1,000 (at least 1), see
     * Shop.getBuyPrice / getSellPrice and com.gadscape.game.content.currency.BloodTokens.
     */
    COINS(13307) {
        override fun toString(): String = "blood tokens"
    },
    TOKKUL(6529),
    GOLD_NUGGETS(12012) {
        override fun toString(): String = "golden nuggets"
    },
    CASTLE_WARS_TICKETS(4067) {
        override fun toString(): String = "castle wars tickets"
    },
    MARK_OF_GRACE(11849) {
        override fun toString(): String = "marks of grace"
    },
    VOTE_POINTS(isStackable = false, isPhysical = false) {
        override fun toString(): String = "vote points"
    },
    BH_POINTS(isStackable = false, isPhysical = false) {
        override fun toString(): String = "bounty hunter points"
    },
    AFK_POINTS(isStackable = false, isPhysical = false) {
        override fun toString(): String = "Afk points"
    },
    LOYALTY_POINTS(isStackable = false, isPhysical = false) {
        override fun toString(): String = "loyalty points"
    },
    EXCHANGE_POINTS(isStackable = false, isPhysical = false) {
        override fun toString(): String = "exchange points"
    },
    MOLCH_PEARL(22820) {
        override fun toString(): String = "molch pearls"
    },
    STARDUST(25527) {
        override fun toString(): String = "stardust"
    },
    TOURNAMENT_POINTS(isStackable = false, isPhysical = false) {
        override fun toString(): String = "tournament points"
    },
    SLAYER_POINTS(isStackable = false, isPhysical = false) {
        override fun toString(): String = "slayer points"
    },
    BROKEN_EGG_SHELLS(32356) {
        override fun toString(): String = "broken egg shells"
    },
    PVM_ARENA_POINTS(isStackable = false, isPhysical = false) {
        override fun toString(): String = "pvm arena points"
    };

    private val formattedString by lazy { name.lowercase() }

    override fun toString(): String = formattedString
}
