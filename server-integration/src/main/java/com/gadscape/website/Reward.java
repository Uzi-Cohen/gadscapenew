package com.gadscape.website;

/** One entry from ::claim. itemId is null when the game should pick the item from {@code code}. */
public final class Reward {
	public final String id;
	public final String code;
	public final Integer itemId;
	public final int amount;
	public final Integer valueCents;
	public final String label;
	public final String source;

	public Reward(String id, String code, Integer itemId, int amount, Integer valueCents, String label, String source) {
		this.id = id;
		this.code = code;
		this.itemId = itemId;
		this.amount = amount;
		this.valueCents = valueCents;
		this.label = label;
		this.source = source;
	}
}
