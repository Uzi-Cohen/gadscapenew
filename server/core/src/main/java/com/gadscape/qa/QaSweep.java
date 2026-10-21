package com.gadscape.qa;

import com.zenyte.game.GameInterface;
import com.zenyte.game.item.Item;
import com.zenyte.game.model.shop.Shop;
import com.zenyte.game.model.ui.InterfacePosition;
import com.zenyte.game.task.WorldTask;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.action.combat.AmmunitionDefinitions;
import com.zenyte.game.world.entity.player.action.combat.PlayerCombat;
import com.zenyte.game.world.entity.player.action.combat.SpecialAttack;
import com.gadscape.game.world.entity.player.action.combat.ISpecialAttack;
import mgi.types.config.items.ItemDefinitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * TEST BUILD ONLY. Steps through every GameInterface, every shop and every special-attack weapon, one per few ticks,
 * logging "QASWEEP ..." lines that an external watcher pairs with screenshots and client errors.
 * Usage: ::qasweep iface|shop|spec|skill|tele [from] | stop
 */
public final class QaSweep {
    private static final Logger log = LoggerFactory.getLogger(QaSweep.class);
    private static final int DUMMY_NPC = 2098; // hill giant: attackable everywhere, respawned every step
    private static volatile int generation;

    private QaSweep() {
    }

    public static void start(final Player p, final String[] args) {
        final int gen = ++generation;
        if (args.length == 0 || args[0].equals("stop")) {
            p.sendMessage("QA sweep stopped. Usage: ::qasweep iface|shop|spec [from]");
            return;
        }
        final int from = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        switch (args[0]) {
            case "iface" -> interfaces(p, gen, from);
            case "shop" -> shops(p, gen, from);
            case "spec" -> specials(p, gen, from);
            case "skill" -> skills(p, gen, from);
            case "tele" -> teleports(p, gen, from);
            default -> p.sendMessage("Unknown sweep: " + args[0]);
        }
    }

    private static void closeAll(final Player p, final InterfacePosition position) {
        try {
            p.getInterfaceHandler().closeInterfaces();
            p.getInterfaceHandler().closeInput();
            p.getInterfaceHandler().closeInterface(InterfacePosition.DIALOGUE);
            if (position != null && position != InterfacePosition.CENTRAL) {
                p.getInterfaceHandler().closeInterface(position);
            }
        } catch (Throwable t) {
            log.error("QASWEEP-ERR close {}", position, t);
        }
    }

    private static void interfaces(final Player p, final int gen, final int from) {
        final GameInterface[] all = GameInterface.values;
        log.info("QASWEEP-BEGIN iface {}", all.length);
        WorldTasksManager.schedule(new WorldTask() {
            int index = from;
            int phase;

            @Override
            public void run() {
                if (gen != generation || p.isFinished()) {
                    stop();
                    return;
                }
                if (phase == 0) {
                    if (index >= all.length) {
                        log.info("QASWEEP-END iface");
                        p.getInterfaceHandler().sendGameFrame();
                        stop();
                        return;
                    }
                    final GameInterface gi = all[index];
                    log.info("QASWEEP iface {}/{} {} id={} pos={}", index, all.length, gi.name(), gi.getId(), gi.getPosition());
                    try {
                        gi.open(p);
                    } catch (Throwable t) {
                        log.error("QASWEEP-ERR iface {} {} open", index, gi.name(), t);
                    }
                } else if (phase == 3) {
                    closeAll(p, all[index].getPosition());
                    if (p.isLocked()) log.info("QASWEEP-NOTE iface {} {} left the player locked", index, all[index].name());
                    index++;
                }
                phase = (phase + 1) % 4;
            }
        }, 0, 0);
    }

    private static void shops(final Player p, final int gen, final int from) {
        final List<Map.Entry<String, Shop>> all = new ArrayList<>();
        all.addAll(new TreeMap<>(Shop.shops).entrySet());
        final int normal = all.size();
        all.addAll(new TreeMap<>(Shop.ironmanShops).entrySet());
        log.info("QASWEEP-BEGIN shop {} ({} normal, {} ironman)", all.size(), normal, all.size() - normal);
        WorldTasksManager.schedule(new WorldTask() {
            int index = from;
            int phase;

            @Override
            public void run() {
                if (gen != generation || p.isFinished()) {
                    stop();
                    return;
                }
                if (phase == 0) {
                    if (index >= all.size()) {
                        log.info("QASWEEP-END shop");
                        stop();
                        return;
                    }
                    final Map.Entry<String, Shop> e = all.get(index);
                    log.info("QASWEEP shop {}/{} {}{}", index, all.size(), index >= normal ? "ironman:" : "", e.getKey());
                    try {
                        e.getValue().open(p);
                    } catch (Throwable t) {
                        log.error("QASWEEP-ERR shop {} {} open", index, e.getKey(), t);
                    }
                } else if (phase == 3) {
                    closeAll(p, null);
                    index++;
                }
                phase = (phase + 1) % 4;
            }
        }, 0, 0);
    }

    private static int slotOf(final Player p, final int id) {
        for (int slot = 0; slot < 28; slot++) {
            final Item item = p.getInventory().getItem(slot);
            if (item != null && item.getId() == id) return slot;
        }
        return -1;
    }

    private static int ammoFor(final int weapon) {
        for (final AmmunitionDefinitions ammo : AmmunitionDefinitions.values()) {
            if (!ammo.isWeapon() && ammo.isCompatible(weapon) && ammo.getItemIds().length > 0) return ammo.getItemIds()[0];
        }
        return -1;
    }

    private static boolean thrown(final int weapon) {
        for (final AmmunitionDefinitions ammo : AmmunitionDefinitions.values()) {
            if (ammo.isWeapon()) for (final int id : ammo.getItemIds()) if (id == weapon) return true;
        }
        return false;
    }

    private static void specials(final Player p, final int gen, final int from) {
        final List<Integer> weapons = new ArrayList<>(new TreeMap<>(SpecialAttack.SPECIAL_ATTACKS).keySet());
        final Location base = new Location(p.getLocation());
        log.info("QASWEEP-BEGIN spec {} weapons at {}", weapons.size(), base);
        WorldTasksManager.schedule(new WorldTask() {
            int index = from;
            int phase;
            NPC dummy;
            int dummyHp;

            @Override
            public void run() {
                if (gen != generation || p.isFinished()) {
                    if (dummy != null) dummy.finish();
                    stop();
                    return;
                }
                try {
                    if (phase == 0) {
                        if (index >= weapons.size()) {
                            log.info("QASWEEP-END spec");
                            stop();
                            return;
                        }
                        final int weapon = weapons.get(index);
                        final ISpecialAttack special = SpecialAttack.SPECIAL_ATTACKS.get(weapon);
                        closeAll(p, null);
                        p.setLocation(new Location(base));
                        p.setHitpoints(p.getMaxHitpoints());
                        p.getInventory().clear();
                        p.getEquipment().clear();
                        p.getEquipment().refreshAll();
                        dummy = World.spawnNPC(DUMMY_NPC, new Location(base.getX() + 1, base.getY(), base.getPlane()));
                        dummy.setSpawned(true);
                        dummyHp = dummy.getHitpoints();
                        final ItemDefinitions def = ItemDefinitions.get(weapon);
                        final boolean isThrown = thrown(weapon);
                        p.getInventory().addItem(weapon, isThrown ? 5000 : 1);
                        final int wslot = slotOf(p, weapon);
                        final boolean worn = wslot >= 0 && p.getEquipment().wear(wslot);
                        final int ammo = isThrown ? -1 : ammoFor(weapon);
                        if (ammo >= 0) {
                            p.getInventory().addItem(ammo, 5000);
                            final int aslot = slotOf(p, ammo);
                            if (aslot >= 0) p.getEquipment().wear(aslot);
                        }
                        p.getCombatDefinitions().setSpecialEnergy(100);
                        log.info("QASWEEP spec {}/{} weapon={} name={} special={} ammo={} worn={}", index, weapons.size(), weapon,
                                def == null ? "?" : def.getName(), special == null ? "?" : special.getSpecialAttackName(), ammo, worn);
                    } else if (phase == 1) {
                        p.getCombatDefinitions().setSpecial(true, false);
                        if (dummy != null && !dummy.isFinished()) PlayerCombat.attackEntity(p, dummy, null);
                    } else if (phase == 6) {
                        final int weapon = weapons.get(index);
                        final int energy = p.getCombatDefinitions().getSpecialEnergy();
                        final int dealt = dummy == null ? 0 : dummyHp - dummy.getHitpoints();
                        log.info("QASWEEP-RESULT spec {} weapon={} energyAfter={} used={} dummyDamage={} dummyDead={}", index, weapon, energy,
                                energy < 100, dealt, dummy == null || dummy.isFinished() || dummy.isDead());
                        p.getCombatDefinitions().setSpecial(false, true);
                        p.getActionManager().forceStop();
                        if (dummy != null) dummy.finish();
                        dummy = null;
                        index++;
                    }
                } catch (Throwable t) {
                    log.error("QASWEEP-ERR spec {} phase {}", index, phase, t);
                    if (phase == 0) phase = 5; // skip to cleanup
                }
                phase = (phase + 1) % 7;
            }
        }, 0, 0);
    }

    // ------------------------------------------------------------------ skills

    /** One skill check: go to {@code at}, get {@code items}, run {@code action}, wait, and compare the skill's XP. */
    private record SkillTask(String name, int skill, Location at, int[] items, Action action, int waitTicks, boolean pressSpace) {
    }

    @FunctionalInterface
    private interface Action {
        String run(Player p) throws Exception;
    }

    private static boolean hasOption(final String[] options, final String option) {
        if (options == null) return false;
        for (final String o : options) if (o != null && o.toLowerCase().contains(option.toLowerCase())) return true;
        return false;
    }

    private static int optionIndex(final String[] options, final String option) {
        for (int i = 0; i < options.length; i++) if (options[i] != null && options[i].equalsIgnoreCase(option)) return i + 1;
        for (int i = 0; i < options.length; i++) if (options[i] != null && options[i].toLowerCase().contains(option.toLowerCase())) return i + 1;
        return -1;
    }

    /** Nearest object to {@code c} (same plane, within {@code radius}) with the given option and, optionally, a name containing {@code name}. */
    private static Player viewer;

    private static com.zenyte.game.world.object.WorldObject objectNear(final Location c, final int radius, final String option, final String name) {
        com.zenyte.game.world.object.WorldObject best = null;
        int bestDist = Integer.MAX_VALUE;
        final java.util.Set<Integer> regions = new java.util.TreeSet<>();
        for (int x = c.getX() - radius; x <= c.getX() + radius + 63; x += 64)
            for (int y = c.getY() - radius; y <= c.getY() + radius + 63; y += 64)
                regions.add(((Math.min(x, c.getX() + radius) >> 6) << 8) | (Math.min(y, c.getY() + radius) >> 6));
        for (final int regionId : regions) {
            final com.zenyte.game.world.region.Region region = World.getRegion(regionId, true);
            if (region == null || region.getObjects() == null) continue;
            for (final com.zenyte.game.world.object.WorldObject o : region.getObjects().values()) {
                if (o == null || o.getPlane() != c.getPlane()) continue;
                final int d = Math.max(Math.abs(o.getX() - c.getX()), Math.abs(o.getY() - c.getY()));
                if (d > radius || d >= bestDist) continue;
                final mgi.types.config.ObjectDefinitions defs = viewer == null || o.getDefinitions() == null ? o.getDefinitions()
                        : mgi.types.config.ObjectDefinitions.get(viewer.getTransmogrifiedId(o.getDefinitions(), o.getId()));
                if (defs == null || !hasOption(defs.getOptions(), option)) continue;
                if (name != null && (defs.getName() == null || !defs.getName().toLowerCase().contains(name.toLowerCase()))) continue;
                best = o;
                bestDist = d;
            }
        }
        return best;
    }

    private static NPC npcNear(final Location c, final int radius, final String option) {
        NPC best = null;
        int bestDist = Integer.MAX_VALUE;
        for (final NPC n : com.zenyte.game.world.region.CharacterLoop.find(c, radius, NPC.class, n -> n.getDefinitions() != null && hasOption(n.getDefinitions().getOptions(), option))) {
            final int d = n.getLocation().getTileDistance(c);
            if (d < bestDist) {
                best = n;
                bestDist = d;
            }
        }
        return best;
    }

    private static String objectOp(final Player p, final Location c, final int radius, final String option, final String name) {
        viewer = p;
        final com.zenyte.game.world.object.WorldObject o = objectNear(c, radius, option, name);
        if (o == null) return "no object with option '" + option + "'" + (name == null ? "" : " named '" + name + "'") + " within " + radius + " of " + c;
        final mgi.types.config.ObjectDefinitions seen = mgi.types.config.ObjectDefinitions.get(p.getTransmogrifiedId(o.getDefinitions(), o.getId()));
        final mgi.types.config.ObjectDefinitions defs = seen != null ? seen : o.getDefinitions();
        new com.zenyte.game.packet.in.event.OpLocEvent(o.getId(), o.getX(), o.getY(), optionIndex(defs.getOptions(), option), false).handle(p);
        return "object " + o.getId() + " '" + defs.getName() + "' option " + optionIndex(defs.getOptions(), option) + " at " + o.getX() + "," + o.getY();
    }

    private static String npcOp(final Player p, final Location c, final int radius, final String option) {
        final NPC n = npcNear(c, radius, option);
        if (n == null) return "no npc with option '" + option + "' within " + radius + " of " + c;
        new com.zenyte.game.packet.in.event.OpNpcEvent(n.getIndex(), optionIndex(n.getDefinitions().getOptions(), option), false).handle(p);
        return "npc " + n.getId() + " '" + n.getDefinitions().getName() + "' at " + n.getLocation();
    }

    private static String itemOnItem(final Player p, final int a, final int b) {
        final int sa = slotOf(p, a), sb = slotOf(p, b);
        if (sa < 0 || sb < 0) return "missing items " + a + "/" + b;
        new com.zenyte.game.packet.in.event.IfButtonTEvent(149, 0, 149, 0, sa, sb, a, b).handle(p);
        return "used " + a + " on " + b;
    }

    private static String itemOnObject(final Player p, final int item, final Location c, final int radius, final String name) {
        com.zenyte.game.world.object.WorldObject o = null;
        int bestDist = Integer.MAX_VALUE;
        final java.util.Set<Integer> regions = new java.util.TreeSet<>();
        for (int x = c.getX() - radius; x <= c.getX() + radius + 63; x += 64)
            for (int y = c.getY() - radius; y <= c.getY() + radius + 63; y += 64)
                regions.add(((Math.min(x, c.getX() + radius) >> 6) << 8) | (Math.min(y, c.getY() + radius) >> 6));
        for (final int regionId : regions) {
            final com.zenyte.game.world.region.Region region = World.getRegion(regionId, true);
            if (region == null || region.getObjects() == null) continue;
            for (final com.zenyte.game.world.object.WorldObject w : region.getObjects().values()) {
                if (w == null || w.getPlane() != c.getPlane() || w.getDefinitions() == null || w.getDefinitions().getName() == null) continue;
                if (!w.getDefinitions().getName().toLowerCase().contains(name.toLowerCase())) continue;
                final int d = Math.max(Math.abs(w.getX() - c.getX()), Math.abs(w.getY() - c.getY()));
                if (d <= radius && d < bestDist) {
                    o = w;
                    bestDist = d;
                }
            }
        }
        if (o == null) return "no object named '" + name + "' within " + radius + " of " + c;
        final int slot = slotOf(p, item);
        if (slot < 0) return "missing item " + item;
        new com.zenyte.game.packet.in.event.OpLocTEvent(149, 0, slot, o.getId(), o.getX(), o.getY(), false).handle(p);
        return "used " + item + " on object " + o.getId() + " '" + o.getDefinitions().getName() + "' at " + o.getX() + "," + o.getY();
    }

    private static String itemOption(final Player p, final int item, final String option) {
        final int slot = slotOf(p, item);
        final ItemDefinitions def = ItemDefinitions.get(item);
        if (slot < 0 || def == null) return "missing item " + item;
        final int op = optionIndex(def.getInventoryOptions(), option);
        if (op < 0) return "item " + item + " has no option '" + option + "'";
        com.zenyte.game.model.item.ItemActionHandler.handle(p, item, slot, op);
        return "item " + item + " option " + option + " (" + op + ")";
    }

    private static String spellOnItem(final Player p, final String spell, final int item) {
        int component = -1;
        for (int c = 0; c < 300 && component < 0; c++) {
            final String n = com.zenyte.game.content.skills.magic.SpellDefinitions.getSpellName(c);
            if (n != null && n.toLowerCase().replace(" ", "").contains(spell)) component = c;
        }
        final int slot = slotOf(p, item);
        if (component < 0 || slot < 0) return "spell '" + spell + "' component " + component + ", item slot " + slot;
        new com.zenyte.game.packet.in.event.IfButtonTEvent(218, component, 149, 0, -1, slot, -1, item).handle(p);
        return "cast " + spell + " (218:" + component + ") on " + item;
    }

    private static NPC spawnDummy(final Player p, final Location at) {
        final NPC n = World.spawnNPC(DUMMY_NPC, new Location(at.getX() + 1, at.getY(), at.getPlane()));
        n.setSpawned(true);
        return n;
    }

    private static List<SkillTask> skillTasks() {
        final Location home = new Location(3217, 3427, 0);
        final List<SkillTask> t = new ArrayList<>();
        t.add(new SkillTask("Melee (whip, lash: Attack/Strength/Defence/Hitpoints)", 2, home, new int[]{4151, 1}, p -> {
            p.getEquipment().wear(slotOf(p, 4151));
            p.getCombatDefinitions().setStyle(1);
            PlayerCombat.attackEntity(p, spawnDummy(p, home), null);
            return "attacking";
        }, 10, false));
        t.add(new SkillTask("Ranged (magic shortbow)", 4, home, new int[]{861, 1, 892, 500}, p -> {
            p.getEquipment().wear(slotOf(p, 861));
            p.getEquipment().wear(slotOf(p, 892));
            PlayerCombat.attackEntity(p, spawnDummy(p, home), null);
            return "attacking";
        }, 10, false));
        t.add(new SkillTask("Magic (High Alchemy)", 6, home, new int[]{1203, 1, 554, 100, 561, 10}, p -> spellOnItem(p, "highlevelalchemy", 1203), 6, false));
        t.add(new SkillTask("Prayer (bury big bones)", 5, home, new int[]{532, 1}, p -> itemOption(p, 532, "Bury"), 5, false));
        t.add(new SkillTask("Woodcutting (Lumbridge trees)", 8, new Location(3192, 3227, 0), new int[]{6739, 1}, p -> objectOp(p, new Location(3192, 3227, 0), 25, "Chop down", "tree"), 20, false));
        t.add(new SkillTask("Mining (Lumbridge swamp)", 14, new Location(3228, 3148, 0), new int[]{11920, 1}, p -> objectOp(p, new Location(3228, 3148, 0), 15, "Mine", null), 20, false));
        t.add(new SkillTask("Fishing (Draynor net)", 10, new Location(3086, 3230, 0), new int[]{303, 1}, p -> npcOp(p, new Location(3086, 3230, 0), 25, "Net"), 25, false));
        t.add(new SkillTask("Firemaking (logs)", 11, new Location(3233, 3232, 0), new int[]{590, 1, 1511, 1}, p -> itemOnItem(p, 590, 1511), 12, false));
        t.add(new SkillTask("Cooking (Lumbridge range)", 7, new Location(3209, 3214, 0), new int[]{317, 5}, p -> itemOnObject(p, 317, new Location(3209, 3214, 0), 10, "range"), 15, true));
        t.add(new SkillTask("Fletching (knife on logs)", 9, home, new int[]{946, 1, 1511, 1}, p -> itemOnItem(p, 946, 1511), 12, true));
        t.add(new SkillTask("Crafting (chisel sapphire)", 12, home, new int[]{1755, 1, 1623, 3}, p -> itemOnItem(p, 1755, 1623), 10, true));
        t.add(new SkillTask("Smithing (Lumbridge furnace)", 13, new Location(3226, 3254, 0), new int[]{436, 3, 438, 3}, p -> itemOnObject(p, 436, new Location(3226, 3254, 0), 10, "furnace"), 15, true));
        t.add(new SkillTask("Herblore (clean grimy guam)", 15, home, new int[]{199, 1}, p -> itemOption(p, 199, "Clean"), 5, false));
        t.add(new SkillTask("Agility (Draynor rooftop rough wall)", 16, new Location(3103, 3277, 0), new int[]{}, p -> objectOp(p, new Location(3103, 3277, 0), 10, "Climb", "wall"), 12, false));
        t.add(new SkillTask("Thieving (stall at home)", 17, home, new int[]{}, p -> objectOp(p, home, 25, "Steal-from", null), 8, false));
        t.add(new SkillTask("Slayer (hill giant task, kill one)", 18, home, new int[]{4151, 1}, p -> {
            final com.zenyte.game.content.skills.slayer.SlayerTask task = com.zenyte.game.content.skills.slayer.RegularTask.HILL_GIANTS;
            p.getSlayer().setAssignment(new com.zenyte.game.content.skills.slayer.Assignment(p, p.getSlayer(), task, task.getEnumName(), 5, 5, com.zenyte.game.content.skills.slayer.SlayerMaster.MAZCHNA));
            p.getSlayer().setMaster(com.zenyte.game.content.skills.slayer.SlayerMaster.MAZCHNA);
            p.getEquipment().wear(slotOf(p, 4151));
            PlayerCombat.attackEntity(p, spawnDummy(p, home), null);
            return "task hill giants x5, attacking";
        }, 30, false));
        t.add(new SkillTask("Farming (rake Falador allotment)", 19, new Location(3054, 3306, 0), new int[]{5341, 1}, p -> objectOp(p, new Location(3054, 3306, 0), 15, "Rake", null), 15, false));
        t.add(new SkillTask("Runecraft (earth altar)", 20, new Location(2656, 4839, 0), new int[]{7936, 10}, p -> objectOp(p, new Location(2656, 4839, 0), 10, "Craft-rune", null), 8, false));
        t.add(new SkillTask("Hunter (lay box trap)", 21, new Location(2550, 2912, 0), new int[]{10008, 1}, p -> itemOption(p, 10008, "Lay"), 8, false));
        t.add(new SkillTask("Construction (enter house, build mode)", 22, home, new int[]{}, p -> {
            final boolean ok = p.getConstruction().enterHouse(true, null);
            return "enterHouse(build) returned " + ok;
        }, 12, false));
        return t;
    }

    private static void skills(final Player p, final int gen, final int from) {
        final List<SkillTask> all = skillTasks();
        log.info("QASWEEP-BEGIN skill {}", all.size());
        WorldTasksManager.schedule(new WorldTask() {
            int index = from;
            int tick;
            double before;
            final List<NPC> spawned = new ArrayList<>();

            private void cleanup() {
                for (final NPC n : spawned) if (!n.isFinished()) n.finish();
                spawned.clear();
                for (final NPC n : com.zenyte.game.world.region.CharacterLoop.find(p.getLocation(), 3, NPC.class, n -> n.getId() == DUMMY_NPC && n.isSpawned())) n.finish();
                p.getActionManager().forceStop();
                closeAll(p, null);
                p.getInventory().clear();
                p.getEquipment().clear();
                p.getEquipment().refreshAll();
            }

            @Override
            public void run() {
                if (gen != generation || p.isFinished()) {
                    stop();
                    return;
                }
                try {
                    if (index >= all.size()) {
                        log.info("QASWEEP-END skill");
                        p.setLocation(new Location(3217, 3427, 0));
                        stop();
                        return;
                    }
                    final SkillTask task = all.get(index);
                    if (tick == 0) {
                        cleanup();
                        p.setLocation(new Location(task.at()));
                        p.setHitpoints(p.getMaxHitpoints());
                        for (int s = 0; s < 23; s++) if (p.getSkills().getExperience(s) > 13_034_431) p.getSkills().setSkill(s, 99, 13_034_431);
                        for (int k = 0; k + 1 < task.items().length; k += 2) p.getInventory().addItem(task.items()[k], task.items()[k + 1]);
                        before = p.getSkills().getExperience(task.skill());
                        log.info("QASWEEP skill {}/{} {}", index, all.size(), task.name());
                    } else if (tick == 3) {
                        String what;
                        try {
                            what = task.action().run(p);
                        } catch (Throwable t) {
                            what = "action threw " + t;
                            log.error("QASWEEP-ERR skill {} {}", index, task.name(), t);
                        }
                        log.info("QASWEEP-NOTE skill {} {}", index, what);
                    } else if (task.pressSpace() && tick == 6) {
                        log.info("QASWEEP-KEY space");
                    } else if (tick >= 3 + task.waitTicks()) {
                        final double after = p.getSkills().getExperience(task.skill());
                        log.info("QASWEEP-RESULT skill {} {} skill={} xpBefore={} xpAfter={} gained={} at={}", index, task.name(), task.skill(),
                                (long) before, (long) after, (long) (after - before), p.getLocation());
                        cleanup();
                        index++;
                        tick = 0;
                        return;
                    }
                } catch (Throwable t) {
                    log.error("QASWEEP-ERR skill {} tick {}", index, tick, t);
                }
                tick++;
            }
        }, 0, 0);
    }

    // ------------------------------------------------------------------ teleports

    private static void teleports(final Player p, final int gen, final int from) {
        final List<com.zenyte.game.content.skills.magic.spells.teleports.Teleport> all = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (final com.zenyte.game.world.entity.player.teleportsystem.PortalTeleport t : com.zenyte.game.world.entity.player.teleportsystem.PortalTeleport.values()) {
            all.add(t);
            labels.add("portal:" + t.getCategory() + ":" + t.name());
        }
        for (final com.zenyte.game.content.skills.magic.spells.teleports.SpellbookTeleport t : com.zenyte.game.content.skills.magic.spells.teleports.SpellbookTeleport.values()) {
            all.add(t);
            labels.add("spell:" + t.name());
        }
        for (final com.zenyte.game.content.skills.magic.spells.teleports.ItemTeleport t : com.zenyte.game.content.skills.magic.spells.teleports.ItemTeleport.values()) {
            all.add(t);
            labels.add("item:" + t.name());
        }
        for (final com.zenyte.game.content.skills.magic.spells.teleports.TeleportCollection t : com.zenyte.game.content.skills.magic.spells.teleports.TeleportCollection.values()) {
            all.add(t);
            labels.add("collection:" + t.name());
        }
        final int[] runes = {556, 555, 557, 554, 563, 566, 565, 560, 9075, 561, 559, 558, 564, 562, 21880};
        log.info("QASWEEP-BEGIN tele {}", all.size());
        p.addAttribute("NO_WILDERNESS_TELEPORT_WARNING", 1);
        WorldTasksManager.schedule(new WorldTask() {
            int index = from;
            int tick;
            Location start;

            @Override
            public void run() {
                if (gen != generation || p.isFinished()) {
                    stop();
                    return;
                }
                try {
                    if (index >= all.size()) {
                        log.info("QASWEEP-END tele");
                        stop();
                        return;
                    }
                    final com.zenyte.game.content.skills.magic.spells.teleports.Teleport t = all.get(index);
                    if (tick == 0) {
                        closeAll(p, null);
                        p.getActionManager().forceStop();
                        p.setHitpoints(p.getMaxHitpoints());
                        p.getInventory().clear();
                        for (final int r : runes) p.getInventory().addItem(r, 1000);
                        if (t instanceof com.zenyte.game.content.skills.magic.spells.teleports.SpellbookTeleport st && st.getSpellbook() != null) {
                            p.getCombatDefinitions().setSpellbook(st.getSpellbook(), true);
                        }
                        start = new Location(p.getLocation());
                        log.info("QASWEEP tele {}/{} {} dest={}", index, all.size(), labels.get(index), t.getDestination());
                        try {
                            t.teleport(p);
                        } catch (Throwable e) {
                            log.error("QASWEEP-ERR tele {} {}", index, labels.get(index), e);
                        }
                    } else if (tick == 9) {
                        final Location dest = t.getDestination();
                        final Location at = p.getLocation();
                        final boolean arrived = dest != null ? at.getTileDistance(dest) <= 20 && at.getPlane() == dest.getPlane() : (at.getX() != start.getX() || at.getY() != start.getY() || at.getPlane() != start.getPlane());
                        log.info("QASWEEP-RESULT tele {} {} arrived={} at={} dest={}", index, labels.get(index), arrived, at, dest);
                        closeAll(p, null);
                        p.setLocation(new Location(3217, 3427, 0));
                        index++;
                        tick = 0;
                        return;
                    }
                } catch (Throwable e) {
                    log.error("QASWEEP-ERR tele {} tick {}", index, tick, e);
                }
                tick++;
            }
        }, 0, 0);
    }
}
