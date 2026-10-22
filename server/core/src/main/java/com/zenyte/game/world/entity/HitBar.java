package com.zenyte.game.world.entity;

/**
 * One head bar update, sent with the entity's HIT update flag (rsprot head bar: type, startFill, endFill,
 * startTime, endTime). Fills are segment counts, 0 to the head bar config's "segments" (30 by default).
 * See {@link InterpolatingHitBar} for bars the client animates (E14) and {@link RemoveHitBar} to hide a bar.
 */
public abstract class HitBar {

	/** The head bar config id. */
	public abstract int getType();

	/** The fill shown first (segments). */
	public abstract int getPercentage();

	/** Client cycles (30 per tick) before the bar shows {@link #getPercentage()}. */
	public int getDelay() {
		return 0;
	}

	/** Client cycles over which the client slides to {@link #interpolatePercentage()}; 0 = static, 32767 = remove the bar. */
	public int interpolateTime() {
		return 0;
	}

	/** The fill the bar slides to when {@link #interpolateTime()} is above 0. */
	public int interpolatePercentage() {
		return 0;
	}

	public int getDisplayPercentage() {
		if(forcePercentage != -1) {
			return forcePercentage;
		}
		return 0;
	}

	public boolean hasForcedPercentage() {
		return forcePercentage != -1;
	}

	private int forcePercentage = -1;
	public void setForcePercentage(AbstractEntity entity, int num, int denom){
		forcePercentage = (int) Math.floor(((double) num / denom) * 100);
	}
}