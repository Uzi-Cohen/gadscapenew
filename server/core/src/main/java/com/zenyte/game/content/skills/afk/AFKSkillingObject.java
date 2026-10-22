package com.zenyte.game.content.skills.afk;

import com.gadscape.game.rev241.Gad211;
import com.zenyte.game.content.skills.afk.impl.*;

public enum AFKSkillingObject {
    FARMING(Gad211.loc(50084), AfkFarmingAction.class),
    FIRE_MAKING(Gad211.loc(50085), AfkFiremakingAction.class),
    CRAFTING(Gad211.loc(50093), AfkCraftingAction.class),
    MINING(Gad211.loc(50087), AfkMiningAction.class),
    SMITHING(Gad211.loc(50094), AfkSmithingAction.class),
    WOODCUTTING(Gad211.loc(50090), AfkWoodCuttingAction.class),
    AGILITY(50061, AfkAgilityAction.class),
    THIEVING(Gad211.loc(50089), AfkThievingAction.class),
    RUNECRAFTING(Gad211.loc(50095), AfkRunecraftingAction.class),
    FISHING(Gad211.loc(50096), AfkFishingAction.class),
    ;

    private int objectId;
    private Class<? extends BasicAfkAction> clazz;

    AFKSkillingObject(int objectId, Class<? extends BasicAfkAction> clazz) {
        this.objectId = objectId;
        this.clazz = clazz;
    }

    public int getObjectId() {
        return objectId;
    }

    public Class<? extends BasicAfkAction> getClazz() {
        return clazz;
    }

    public static AFKSkillingObject forId(int id) {
        for (AFKSkillingObject value : VALUES) {
            if(value.getObjectId() == id)
                return value;
        }
        return null;
    }

    public static final AFKSkillingObject[] VALUES = values();
}
