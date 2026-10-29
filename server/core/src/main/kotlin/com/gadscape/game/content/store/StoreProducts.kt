package com.gadscape.game.content.store

import com.gadscape.game.item.CustomItemId
import com.zenyte.game.item.Item

/**
 * What the store screen sells, in tile order. [key] is what the cart parameter of the store link uses
 * (`cart=pin1:2,bond10:1`, see docs/store-website-api.md): never change a key, the website reads them.
 */
enum class StoreProduct(val key: String, val itemId: Int, val cents: Int, val fallbackName: String) {
    PIN_1("pin1", CustomItemId.DONATOR_PIN_1, 100, "Donator pin"),
    BOND_10("bond10", CustomItemId.DONATOR_BOND_10, 1_000, "\$10 bond"),
    BOND_20("bond20", CustomItemId.DONATOR_BOND_20, 2_000, "\$20 bond"),
    BOND_50("bond50", CustomItemId.DONATOR_BOND_50, 5_000, "\$50 bond"),
    BOND_100("bond100", CustomItemId.DONATOR_BOND_100, 10_000, "\$100 bond"),
    BOND_250("bond250", CustomItemId.DONATOR_BOND_250, 25_000, "\$250 bond"),
    BOND_1000("bond1000", CustomItemId.DONATOR_BOND_1000, 100_000, "\$1000 bond"),
    ;

    val itemName: String get() = runCatching { Item(itemId).name }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" } ?: fallbackName

    companion object {
        @JvmField val all = entries.toList()
    }
}

/** A promotion checkpoint: once a week's payments reach [cents], [rewards] go to the claim list (once a week). */
class Checkpoint(val cents: Int, val rewards: List<Item>)

object StoreCheckpoints {
    /** In mark order ($25, $50, $100, $250); the patch lays out one reward icon for the first two marks, two for the others. */
    @JvmField
    val all = listOf(
        Checkpoint(2_500, listOf(Item(CustomItemId.COSMETIC_MYSTERY_BOX, 1))),
        Checkpoint(5_000, listOf(Item(CustomItemId.COSMETIC_MYSTERY_BOX, 2))),
        Checkpoint(10_000, listOf(Item(CustomItemId.DONATOR_PIN_1, 5), Item(CustomItemId.COSMETIC_MYSTERY_BOX, 3))),
        Checkpoint(25_000, listOf(Item(CustomItemId.DONATOR_PIN_1, 15), Item(CustomItemId.COSMETIC_MYSTERY_BOX, 5))),
    )
}
