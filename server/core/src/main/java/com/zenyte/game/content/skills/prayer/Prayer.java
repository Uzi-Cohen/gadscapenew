package com.zenyte.game.content.skills.prayer;

import com.zenyte.game.content.boons.impl.HolyInterventionI;
import com.zenyte.game.content.boons.impl.HolyInterventionII;
import com.zenyte.game.content.boons.impl.HolyInterventionIII;
import com.zenyte.game.world.entity.SoundEffect;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.utils.TextUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

public enum Prayer {
	THICK_SKIN(4104, 1, -1, 1, 2690, 4107, 4113, 4128, 4129, 5464, 5465),
	BURST_OF_STRENGTH(4105, 4, -1, 1, 2688, 4108, 4114, 4122, 4123, 4124, 4125, 4126, 4127, 4128, 4129, 5464, 5465),
	CLARITY_OF_THOUGHT(4106, 7, -1, 1, 2664, 4109, 4115, 4122, 4123, 4124, 4125, 4126, 4127, 4128, 4129, 5464, 5465),
	ROCK_SKIN(4107, 10, -1, 6, 2684, 4104, 4113, 4128, 4129, 5464, 5465),
	SUPERHUMAN_STRENGTH(4108, 13, -1, 6, 2689, 4105, 4114, 4122, 4123, 4124, 4125, 4126, 4127, 4128, 4129, 5464, 5465),
	IMPROVED_REFLEXES(4109, 16, -1, 6, 2662, 4106, 4115, 4122, 4123, 4124, 4125, 4126, 4127, 4128, 4129, 5464, 5465),
	RAPID_RESTORE(4110, 19, -1, 1, 2679),
	RAPID_HEAL(4111, 22, -1, 2, 2678),
	PROTECT_ITEM(4112, 25, -1, 2, 1982),
	STEEL_SKIN(4113, 28, -1, 12, 2687, 4104, 4107, 4128, 4129, 5464, 5465),
	ULTIMATE_STRENGTH(4114, 31, -1, 12, 2691, 4108, 4105, 4122, 4123, 4124, 4125, 4126, 4127, 4128, 4129, 5464, 5465),
	INCREDIBLE_REFLEXES(4115, 34, -1, 12, 2667, 4109, 4106, 4122, 4123, 4124, 4125, 4126, 4127, 4128, 4129, 5464, 5465),
	PROTECT_FROM_MAGIC(4116, 37, 2, 12, 2675, 4117, 4118, 4119, 4120, 4121),
	PROTECT_FROM_MISSILES(4117, 40, 1, 12, 2677, 4116, 4118, 4119, 4121, 4120),
	PROTECT_FROM_MELEE(4118, 43, 0, 12, 2676, 4116, 4117, 4119, 4121, 4120),
	RETRIBUTION(4119, 46, 3, 3, 2682, 4116, 4117, 4118, 4121, 4120),
	REDEMPTION(4120, 49, 5, 6, 2680, 4119, 4121, 4116, 4117, 4118),
	SMITE(4121, 52, 4, 18, 2686, 4116, 4117, 4118, 4119, 4120),
	SHARP_EYE(4122, 8, -1, 1, 2685, 4125, 4127, 4123, 4124, 4126, 4105, 4106, 4108, 4109, 4114, 4115, 4128, 4129, 5464, 5465),
	MYSTIC_WILL(4123, 9, -1, 1, 2670, 4125, 4127, 4122, 4124, 4126, 4105, 4106, 4108, 4109, 4114, 4115, 4128, 4129, 5464, 5465),
	HAWK_EYE(4124, 26, -1, 6, 2666, 4125, 4127, 4122, 4123, 4126, 4105, 4106, 4108, 4109, 4114, 4115, 4128, 4129, 5464, 5465),
	MYSTIC_LORE(4125, 27, -1, 6, 2668, 4124, 4127, 4122, 4123, 4126, 4105, 4106, 4108, 4109, 4114, 4115, 4128, 4129, 5464, 5465),
	EAGLE_EYE(4126, 44, -1, 12, 2665, 4125, 4127, 4122, 4123, 4124, 4105, 4106, 4108, 4109, 4114, 4115, 4128, 4129, 5464, 5465),
	MYSTIC_MIGHT(4127, 45, -1, 12, 2669, 4125, 4124, 4122, 4123, 4126, 4105, 4106, 4108, 4109, 4114, 4115, 4128, 4129, 5464, 5465),
	RIGOUR(5464, 74, -1, 24, 2685, 4125, 4124, 4122, 4123, 4126, 4105, 4106, 4108, 4109, 4114, 4115, 4127, 4128, 4129, 4104, 4107, 4113, 5465),
	CHIVALRY(4128, 60, -1, 24, 3826, 4129, 4105, 4106, 4108, 4109, 4114, 4115, 4122, 4123, 4124, 4125, 4126, 4127, 4104, 4107, 4113, 5464, 5465),
	PIETY(4129, 70, -1, 24, 3825, 4128, 4105, 4106, 4108, 4109, 4114, 4115, 4122, 4123, 4124, 4125, 4126, 4127, 4104, 4107, 4113, 5464, 5465),
	AUGURY(5465, 77, -1, 24, 2670, 4125, 4124, 4122, 4123, 4126, 4105, 4106, 4108, 4109, 4114, 4115, 4127, 4128, 4129, 4104, 4107, 4113, 5464),
	PRESERVE(5466, 55, -1, 3, 2679),

	/*
	 * Alternate prayers (build 241 / cache 2746, dbtable 270 alternate_prayers). Once unlocked each one takes the slot
	 * of its base prayer in the prayer book (same button 541:x, same quick-prayer bit), but its active state lives in
	 * varp 5994 prayer_allactive_alternate_0 (same bit number as the base in varp 83). Their collisions are those of the
	 * base prayer plus the base itself; they are filled in by the static block below.
	 * Drain: Deadeye/Mystic Vigour 1pt per 3s (20/min, like Eagle Eye), Zeal 1pt per 1.5s (40/min, like Piety).
	 */
	DEADEYE(16090, 62, -1, 12, 10194),
	MYSTIC_VIGOUR(16091, 63, -1, 12, 10100),
	ZEAL(16577, 84, -1, 24, 12645),

	/*
	 * Ruinous Powers prayer book (Deadman/Leagues book of 24 prayers, cache 2746: varbit 14826 prayerbook = 1, enum 4959,
	 * active bits in varp 3780 prayer_allactive_ruinous, quick prayers in varp 3781). (book slot = param 630 = varp 3780
	 * bit = enum 4959 index; button 541:(9 + slot); varbit; level; overhead icon; drain; sound). Drain = the OSRS wiki's
	 * "drain effect" (added to the drain counter every tick, like Piety's 24). Effects: PrayerManager (boosts, Dampen/Vindication/Wrath/Protect item/Rejuvenation
	 * work as their standard counterparts via Prayer#getRuinousEquivalent) and RuinousPowers (Purge, the Vows, Rebuke,
	 * Ruinous Grace, Metabolise).
	 */
	RUINOUS_ANCIENT_STRENGTH(Book.RUINOUS, 0, 14829, 61, -1, 18, 2688),
	RUINOUS_ANCIENT_SIGHT(Book.RUINOUS, 1, 14830, 62, -1, 18, 2666),
	RUINOUS_ANCIENT_WILL(Book.RUINOUS, 2, 14831, 63, -1, 18, 2670),
	RUINOUS_TRINITAS(Book.RUINOUS, 3, 14832, 72, -1, 22, 2662),
	RUINOUS_DECIMATE(Book.RUINOUS, 4, 14833, 82, -1, 28, 3826),
	RUINOUS_ANNIHILATE(Book.RUINOUS, 5, 14834, 84, -1, 28, 2670),
	RUINOUS_VAPORISE(Book.RUINOUS, 6, 14835, 86, -1, 28, 2668),
	RUINOUS_DAMPEN_MELEE(Book.RUINOUS, 7, 14962, 71, 12, 14, 2676),
	RUINOUS_DAMPEN_RANGED(Book.RUINOUS, 8, 14963, 69, 13, 14, 2677),
	RUINOUS_DAMPEN_MAGIC(Book.RUINOUS, 9, 14964, 67, 14, 14, 2675),
	RUINOUS_PURGE(Book.RUINOUS, 10, 14839, 75, 20, 18, 2686),
	RUINOUS_REJUVENATION(Book.RUINOUS, 11, 14840, 60, -1, 4, 2678),
	RUINOUS_GRACE(Book.RUINOUS, 12, 14841, 66, -1, 1, 1982),
	RUINOUS_WRATH(Book.RUINOUS, 13, 14842, 91, 10, 3, 2682),
	RUINOUS_METABOLISE(Book.RUINOUS, 14, 14843, 77, -1, 12, 2687),
	RUINOUS_BERSERKER(Book.RUINOUS, 15, 14844, 74, -1, 2, 2687),
	RUINOUS_FUMUS_VOW(Book.RUINOUS, 16, 14845, 87, -1, 14, 2680),
	RUINOUS_CRUORS_VOW(Book.RUINOUS, 17, 14846, 89, -1, 14, 2686),
	RUINOUS_UMBRAS_VOW(Book.RUINOUS, 18, 14847, 88, -1, 14, 2685),
	RUINOUS_GLACIES_VOW(Book.RUINOUS, 19, 14848, 90, -1, 14, 2670),
	RUINOUS_INTENSIFY(Book.RUINOUS, 20, 14965, 92, -1, 28, 2666),
	RUINOUS_REBUKE(Book.RUINOUS, 21, 14850, 78, 19, 12, 2668),
	RUINOUS_VINDICATION(Book.RUINOUS, 22, 14851, 80, 21, 9, 2665),
	RUINOUS_PROTECT_ITEM(Book.RUINOUS, 23, 14966, 65, -1, 18, 2665);

	/** The prayer book a prayer belongs to (varbit 14826 prayerbook). */
	public enum Book {
		STANDARD, RUINOUS
	}

	private static final Prayer[] VALUES = values();
	private static final Int2ObjectOpenHashMap<Prayer> PRAYERS = new Int2ObjectOpenHashMap<Prayer>(VALUES.length);
	/** The prayers that sit on a slot of the standard book (varp 83 bits 0..28); the alternates come after them. */
	public static final int STANDARD_BOOK_SIZE = 29;

	static {
		// Ruinous Powers: offensive prayers exclude each other, as do the overheads and the vows.
		final Prayer[][] ruinousGroups = {
				{RUINOUS_ANCIENT_STRENGTH, RUINOUS_ANCIENT_SIGHT, RUINOUS_ANCIENT_WILL, RUINOUS_TRINITAS, RUINOUS_DECIMATE, RUINOUS_ANNIHILATE, RUINOUS_VAPORISE, RUINOUS_INTENSIFY},
				{RUINOUS_DAMPEN_MELEE, RUINOUS_DAMPEN_RANGED, RUINOUS_DAMPEN_MAGIC, RUINOUS_PURGE, RUINOUS_WRATH, RUINOUS_REBUKE, RUINOUS_VINDICATION},
				{RUINOUS_FUMUS_VOW, RUINOUS_CRUORS_VOW, RUINOUS_UMBRAS_VOW, RUINOUS_GLACIES_VOW},
		};
		for (final Prayer[] group : ruinousGroups) {
			for (final Prayer p : group) {
				for (final Prayer other : group) {
					if (other != p) {
						p.collisions = append(p.collisions, other.varbit);
					}
				}
			}
		}
		// Ruinous prayers that work exactly like a standard prayer (protection 100% vs NPCs / 40% in PvP like the
		// protection prayers' multipliers, Redemption, Retribution, Protect Item, Rapid Heal).
		PROTECT_FROM_MELEE.ruinousEquivalent = RUINOUS_DAMPEN_MELEE;
		PROTECT_FROM_MISSILES.ruinousEquivalent = RUINOUS_DAMPEN_RANGED;
		PROTECT_FROM_MAGIC.ruinousEquivalent = RUINOUS_DAMPEN_MAGIC;
		REDEMPTION.ruinousEquivalent = RUINOUS_VINDICATION;
		RETRIBUTION.ruinousEquivalent = RUINOUS_WRATH;
		PROTECT_ITEM.ruinousEquivalent = RUINOUS_PROTECT_ITEM;
		RAPID_HEAL.ruinousEquivalent = RUINOUS_REJUVENATION;
		DEADEYE.base = EAGLE_EYE;
		MYSTIC_VIGOUR.base = MYSTIC_MIGHT;
		ZEAL.base = PIETY;
		final Prayer[] alternates = {DEADEYE, MYSTIC_VIGOUR, ZEAL};
		for (final Prayer alternate : alternates) {
			alternate.collisions = append(alternate.base.collisions, alternate.base.varbit);
			alternate.base.alternate = alternate;
		}
		for (final Prayer p : VALUES) {
			for (final Prayer alternate : alternates) {
				if (p == alternate) {
					continue;
				}
				if (p == alternate.base || contains(p.collisions, alternate.base.varbit)) {
					p.collisions = append(p.collisions, alternate.varbit);
				}
			}
		}
		for (Prayer p : VALUES) {
			PRAYERS.put(p.varbit, p);
		}
		for (Prayer p : VALUES) {
			p.setQuickPrayerCollisions(p.getQPCollisions());
		}
	}

	private static boolean contains(final int[] array, final int value) {
		for (final int v : array) {
			if (v == value) {
				return true;
			}
		}
		return false;
	}

	private static int[] append(final int[] array, final int value) {
		if (contains(array, value)) {
			return array;
		}
		final int[] result = java.util.Arrays.copyOf(array, array.length + 1);
		result[array.length] = value;
		return result;
	}

	private final int varbit;
	private final int level;
	private final int headIcon;
	private final SoundEffect soundEffect;
	private final float drainRate;
	private int[] collisions;
	private final String name;
	int[] quickPrayerCollisions;
	/** For an alternate prayer: the prayer whose slot it takes. */
	private Prayer base;
	/** For a prayer that has an alternate: that alternate. */
	private Prayer alternate;
	private final Book book;
	/** Ruinous Powers: the slot in that book (param 630); -1 for the standard book (slot from the ordinal). */
	private final int bookSlot;
	/** The Ruinous Powers prayer that counts as this standard prayer while active (Dampen Magic for Protect from Magic...). */
	private Prayer ruinousEquivalent;

	Prayer(final Book book, final int slot, final int varbit, final int level, final int headIcon, final float drainRate, final int soundEffect) {
		this.book = book;
		this.bookSlot = slot;
		this.varbit = varbit;
		this.level = level;
		this.headIcon = headIcon;
		this.drainRate = drainRate;
		this.soundEffect = new SoundEffect(soundEffect);
		this.collisions = new int[0];
		this.name = switch (name()) {
			case "RUINOUS_GRACE" -> "Ruinous Grace";
			case "RUINOUS_FUMUS_VOW" -> "Fumus' Vow";
			case "RUINOUS_CRUORS_VOW" -> "Cruor's Vow";
			case "RUINOUS_UMBRAS_VOW" -> "Umbra's Vow";
			case "RUINOUS_GLACIES_VOW" -> "Glacies' Vow";
			default -> TextUtils.capitalize(name().substring("RUINOUS_".length()).toLowerCase().replace("_", " "));
		};
	}

	Prayer(final int varbit, final int level, final int headIcon, final float drainRate, final int soundEffect, final int... collisions) {
		this.book = Book.STANDARD;
		this.bookSlot = -1;
		this.varbit = varbit;
		this.level = level;
		this.headIcon = headIcon;
		this.drainRate = drainRate;
		this.soundEffect = new SoundEffect(soundEffect);
		this.collisions = collisions;
		this.name = TextUtils.capitalize(name().toLowerCase().replace("_", " ")).replace("From", "from").replace("Of", "of");
	}

	public static final Prayer getPrayer(final int varbitId) {
		return PRAYERS.get(varbitId);
	}

	public static Prayer get(int id) {
		return Prayer.VALUES[id];
	}

	/** The quick-prayer slots (bits of varp 84) this prayer's slot excludes: alternates map onto their base slot. */
	private int[] getQPCollisions() {
		final it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet slots = new it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet();
		for (final int collision : collisions) {
			final Prayer other = PRAYERS.get(collision);
			if (other != null && other.getSlot() != getSlot()) {
				slots.add(other.getSlot());
			}
		}
		return slots.toIntArray();
	}

	/**
	 * The prayer's slot in the standard prayer book: param 630 (prayer id) of its prayer object, which is the bit in
	 * varp 83 (or varp 5994 for an alternate), the index in enum 4956 and the quick-prayer bit in varp 84.
	 */
	public int getSlot() {
		if (bookSlot >= 0) {
			return bookSlot;
		}
		return base != null ? base.ordinal() : ordinal();
	}

	public Book getBook() {
		return book;
	}

	public boolean isRuinous() {
		return book == Book.RUINOUS;
	}

	/** The Ruinous Powers prayer that counts as this (standard) prayer, or null. */
	public Prayer getRuinousEquivalent() {
		return ruinousEquivalent;
	}

	private static final Prayer[] RUINOUS_BY_SLOT = new Prayer[24];

	static {
		for (final Prayer p : VALUES) {
			if (p.isRuinous()) {
				RUINOUS_BY_SLOT[p.bookSlot] = p;
			}
		}
	}

	/** The Ruinous Powers prayer of a book slot (0..23), or null. */
	public static Prayer ruinous(final int slot) {
		return slot < 0 || slot >= RUINOUS_BY_SLOT.length ? null : RUINOUS_BY_SLOT[slot];
	}

	/** The prayer this alternate replaces once unlocked (Eagle Eye for Deadeye...), or null for a standard prayer. */
	public Prayer getBase() {
		return base;
	}

	/** The alternate prayer that replaces this one once unlocked, or null. */
	public Prayer getAlternate() {
		return alternate;
	}

	public boolean isAlternate() {
		return base != null;
	}

	public int getVarbit() {
		return varbit;
	}

	public int getLevel() {
		return level;
	}

	public int getHeadIcon() {
		return headIcon;
	}

	public SoundEffect getSoundEffect() {
		return soundEffect;
	}

	public float getDrainRate(Player player) {
		double multiplier = 1.0D;

		if(player == null)
			return drainRate;

		if(player.hasBoon(HolyInterventionIII.class))
			multiplier = 0.5D;
		else if (player.hasBoon(HolyInterventionII.class))
			multiplier = 0.75D;
		else if (player.hasBoon(HolyInterventionI.class))
			multiplier = 0.90D;
		return (float) (drainRate * multiplier);
	}

	public int[] getCollisions() {
		return collisions;
	}

	public int[] getQuickPrayerCollisions() {
		return quickPrayerCollisions;
	}

	public void setQuickPrayerCollisions(int[] quickPrayerCollisions) {
		this.quickPrayerCollisions = quickPrayerCollisions;
	}

	public String getName() {
		return name;
	}
}
