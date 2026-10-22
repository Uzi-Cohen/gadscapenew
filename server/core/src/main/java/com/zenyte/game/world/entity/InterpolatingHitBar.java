package com.zenyte.game.world.entity;

import com.zenyte.game.world.entity.masks.UpdateFlag;

/**
 * A head bar the client animates on its own from one fill to another (E14). One update is enough for the whole
 * animation: the client shows {@link #getPercentage()} after {@link #getDelay()} client cycles and slides to
 * {@link #interpolatePercentage()} over the next {@link #interpolateTime()} cycles (rsprot's head bar
 * "startTime" / "endTime"; 30 cycles = 1 tick). A fill is a segment count, 0 to {@link #getSegments()} (the head
 * bar's "segments" in the cache; 30 when the cache gives none).
 * <p>
 * Usage: {@code new InterpolatingHitBar.Charge70().interpolate(0, 1, 12).show(npc)} fills headbar 85 from empty
 * to full over 12 ticks (a charge), {@code new InterpolatingHitBar.Shield60().fill(0.5).show(npc)} shows the
 * cyan shield bar at half. {@link #remove(Entity)} hides a bar at once. A bar instance is read when the entity's
 * update is written at the end of the tick, so create a new one (or do not change it) for the rest of that tick.
 * <p>
 * The five 2746 bars and their cache values [CACHE 2746 headbar configs, osrs-dumps 6e5033af dump.headbar]:
 * <table>
 * <tr><th>id</th><th>sprites</th><th>segments</th><th>sticktime</th><th>meant for (spec §5.7)</th></tr>
 * <tr><td>85</td><td>standard_charge_70 (yellow)</td><td>70</td><td>1</td><td>Ezekiel sword empower, Moia and Dagon'hai charges, Ocellus void adds</td></tr>
 * <tr><td>86</td><td>standard_shield_60 (cyan)</td><td>60</td><td>300</td><td>Dagon'hai shared pool, Guardian flying shield</td></tr>
 * <tr><td>87</td><td>headbar_blood_45</td><td>60</td><td>0</td><td>jellies (optional)</td></tr>
 * <tr><td>88</td><td>headbar_heat_60 (orange)</td><td>60</td><td>0</td><td>Ezekiel sword launch timer</td></tr>
 * <tr><td>89</td><td>standard_shield_30 (cyan)</td><td>30</td><td>0</td><td>unassigned; Dagon'hai pool on size-2 bodies [INF]</td></tr>
 * </table>
 * A bar with sticktime 300 fades 300 cycles (10 ticks) after its last update, so a persistent value (a shared
 * pool) has to be re-sent at least every 9 ticks or on every change.
 */
public class InterpolatingHitBar extends HitBar {

	/** Client cycles per server tick. */
	public static final int CYCLES_PER_TICK = 30;

	/** The largest start delay / interpolation time the head bar block can carry (32767 means "remove"). */
	public static final int MAX_CYCLES = 32766;

	private final int type;
	private final int segments;
	private int startFill;
	private int endFill;
	private int startDelay;
	private int duration;

	public InterpolatingHitBar(final int type, final int segments) {
		if (segments <= 0 || segments > 255) {
			throw new IllegalArgumentException("head bar " + type + " segments " + segments);
		}
		this.type = type;
		this.segments = segments;
		this.startFill = segments;
		this.endFill = segments;
	}

	/** A static bar at [fraction] (0..1) of its width. */
	public InterpolatingHitBar fill(final double fraction) {
		return fillSegments(toSegments(fraction));
	}

	/** A static bar with [fill] segments (0..segments). */
	public InterpolatingHitBar fillSegments(final int fill) {
		startFill = clampFill(fill);
		endFill = startFill;
		startDelay = 0;
		duration = 0;
		return this;
	}

	/** Animates from [fromFraction] to [toFraction] (0..1) over [ticks] ticks, starting now. */
	public InterpolatingHitBar interpolate(final double fromFraction, final double toFraction, final int ticks) {
		return interpolateCycles(toSegments(fromFraction), toSegments(toFraction), 0, ticks * CYCLES_PER_TICK);
	}

	/**
	 * Animates from [fromFill] to [toFill] segments: the bar appears at [fromFill] after [delayCycles] client
	 * cycles and reaches [toFill] [durationCycles] cycles later. A duration of 0 is a static bar at [fromFill].
	 */
	public InterpolatingHitBar interpolateCycles(final int fromFill, final int toFill, final int delayCycles, final int durationCycles) {
		startFill = clampFill(fromFill);
		endFill = clampFill(toFill);
		startDelay = Math.max(0, Math.min(MAX_CYCLES, delayCycles));
		duration = Math.max(0, Math.min(MAX_CYCLES, durationCycles));
		if (duration == 0) {
			endFill = startFill;
		}
		return this;
	}

	/** Queues this bar on [entity] for this tick's update (flags HIT, as every head bar update rides on it). */
	public InterpolatingHitBar show(final Entity entity) {
		entity.getHitBars().add(this);
		entity.getUpdateFlags().flag(UpdateFlag.HIT);
		return this;
	}

	/** Removes this bar type from [entity] at once. */
	public void remove(final Entity entity) {
		entity.getHitBars().add(new RemoveHitBar(type));
		entity.getUpdateFlags().flag(UpdateFlag.HIT);
	}

	/** [fraction] (clamped to 0..1) as a segment count, rounded to the nearest segment. */
	public int toSegments(final double fraction) {
		final double clamped = Double.isNaN(fraction) ? 0 : Math.max(0, Math.min(1, fraction));
		return (int) Math.round(clamped * segments);
	}

	private int clampFill(final int fill) {
		return Math.max(0, Math.min(segments, fill));
	}

	public int getSegments() {
		return segments;
	}

	@Override
	public int getType() {
		return type;
	}

	/** The fill the bar starts at. */
	@Override
	public int getPercentage() {
		return startFill;
	}

	/** Client cycles before the bar appears at {@link #getPercentage()}. */
	@Override
	public int getDelay() {
		return startDelay;
	}

	/** Client cycles the client takes to slide to {@link #interpolatePercentage()}; 0 = no animation. */
	@Override
	public int interpolateTime() {
		return duration;
	}

	/** The fill the bar ends at. */
	@Override
	public int interpolatePercentage() {
		return endFill;
	}

	@Override
	public String toString() {
		return "InterpolatingHitBar[type=" + type + ", " + startFill + "->" + endFill + "/" + segments + ", delay=" + startDelay + ", duration=" + duration + "]";
	}

	/** Headbar 85 {@code standard_charge_70}: yellow, 70 segments, sticktime 1 (hides right after the last update ends). */
	public static final class Charge70 extends InterpolatingHitBar {
		public static final int TYPE = 85;
		public static final int SEGMENTS = 70;

		public Charge70() {
			super(TYPE, SEGMENTS);
		}
	}

	/** Headbar 86 {@code standard_shield_60}: cyan, 60 segments, sticktime 300. */
	public static final class Shield60 extends InterpolatingHitBar {
		public static final int TYPE = 86;
		public static final int SEGMENTS = 60;

		public Shield60() {
			super(TYPE, SEGMENTS);
		}
	}

	/** Headbar 87 {@code headbar_blood_45}: blood red, 60 segments, sticktime 0. */
	public static final class Blood45 extends InterpolatingHitBar {
		public static final int TYPE = 87;
		public static final int SEGMENTS = 60;

		public Blood45() {
			super(TYPE, SEGMENTS);
		}
	}

	/** Headbar 88 {@code headbar_heat_60}: orange, 60 segments, sticktime 0. */
	public static final class Heat60 extends InterpolatingHitBar {
		public static final int TYPE = 88;
		public static final int SEGMENTS = 60;

		public Heat60() {
			super(TYPE, SEGMENTS);
		}
	}

	/** Headbar 89 {@code standard_shield_30}: cyan, 30 segments (no segments line in the cache: the default 30), sticktime 0. */
	public static final class Shield30 extends InterpolatingHitBar {
		public static final int TYPE = 89;
		public static final int SEGMENTS = 30;

		public Shield30() {
			super(TYPE, SEGMENTS);
		}
	}
}
