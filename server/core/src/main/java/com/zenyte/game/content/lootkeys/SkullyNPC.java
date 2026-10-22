package com.zenyte.game.content.lootkeys;

import com.zenyte.game.item.ItemId;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.npc.actions.NPCPlugin;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.game.world.entity.player.dialogue.Expression;

import static com.zenyte.game.content.lootkeys.LootkeyConstants.UNLOCK_COST;

/**
 * Skully (Ferox Enclave / Edgeville): sells the loot key enchantment and changes its settings.
 * Ops (NPC 10382): Talk-to, Value, Settings. OSRS: oldschool.runescape.wiki/w/Skully, Transcript:Skully.
 */
public class SkullyNPC extends NPCPlugin {

    private static final String COST_TEXT = Utils.formatNumberWithCommas(UNLOCK_COST);
    private static final String COST_WORDS = UNLOCK_COST % 1_000_000 == 0 ? (UNLOCK_COST / 1_000_000) + " million" : COST_TEXT;

    @Override
    public void handle() {
        bind("Talk-to", (player, npc) -> player.getDialogueManager().start(new Dialogue(player, npc) {
            @Override
            public void buildDialogue() {
                npc("Eyup. They call me Skully. I run this Wilderness Loot Chest.");
            }

            @Override
            public void finish() {
                super.finish();
                player.getDialogueManager().start(menu(player, npc));
            }
        }));
        bind("Value", (player, npc) -> player.getDialogueManager().start(claimedLoot(player, npc, false)));
        bind("Settings", (player, npc) -> player.getDialogueManager().start(LootKeys.isUnlocked(player)
                ? settingsMenu(player, npc)
                : accessToChest(player, npc)));
    }

    @Override
    public int[] getNPCs() {
        return new int[] { LootkeyConstants.SKULLY_NPC_ID };
    }

    private Dialogue menu(final Player player, final NPC npc) {
        return new Dialogue(player, npc) {
            @Override
            public void buildDialogue() {
                final boolean unlocked = LootKeys.isUnlocked(player);
                options("Select an Option",
                        "How does the chest work?",
                        unlocked ? "Can I change how these loot keys work?" : "Can I have access to the chest?",
                        "How much loot have I claimed?",
                        "Who are you, exactly?",
                        "Goodbye.")
                        .onOptionOne(() -> player.getDialogueManager().start(chestWorks(player, npc)))
                        .onOptionTwo(() -> player.getDialogueManager().start(LootKeys.isUnlocked(player)
                                ? settingsMenu(player, npc)
                                : accessToChest(player, npc)))
                        .onOptionThree(() -> player.getDialogueManager().start(claimedLoot(player, npc, true)))
                        .onOptionFour(() -> player.getDialogueManager().start(whoAreYou(player, npc)))
                        .onOptionFive(() -> player.getDialogueManager().start(new Dialogue(player, npc) {
                            @Override
                            public void buildDialogue() {
                                player("Goodbye.");
                                npc("I'll be here if you need anything.");
                            }
                        }));
            }
        };
    }

    /** Returns to the main menu once the given dialogue has finished. */
    private abstract class Back extends Dialogue {
        private final NPC skully;

        Back(final Player player, final NPC npc) {
            super(player, npc);
            this.skully = npc;
        }

        @Override
        public void finish() {
            super.finish();
            player.getDialogueManager().start(menu(player, skully));
        }
    }

    /** Returns to the settings menu once the given dialogue has finished. */
    private abstract class BackToSettings extends Dialogue {
        private final NPC skully;

        BackToSettings(final Player player, final NPC npc) {
            super(player, npc);
            this.skully = npc;
        }

        @Override
        public void finish() {
            super.finish();
            player.getDialogueManager().start(settingsMenu(player, skully));
        }
    }

    private Dialogue settingsMenu(final Player player, final NPC npc) {
        return new Dialogue(player, npc) {
            @Override
            public void buildDialogue() {
                final LootkeySettings settings = player.getLootkeySettings();
                if (settings == null || !settings.isUnlocked()) {
                    npc("You'll need the enchantment before there's anything to change, mate.");
                    return;
                }
                final boolean enabled = settings.isEnabled();
                final boolean dropFood = settings.isDropFood();
                final boolean dropValuables = settings.isDropValuables();
                options("Loot keys are currently " + (enabled ? "on" : "off") + ".",
                        enabled ? "Turn loot keys off." : "Turn loot keys on.",
                        dropFood ? "Send food and potions to loot keys." : "Drop food and potions to the floor.",
                        dropValuables ? "Send valuables to loot keys." : "Drop valuables to the floor.",
                        "Change the valuable item threshold (" + Utils.formatNumberWithCommas(settings.getThreshold()) + "gp).",
                        "Nothing, thanks.")
                        .onOptionOne(() -> {
                            settings.setEnabled(!enabled);
                            player.getDialogueManager().start(new BackToSettings(player, npc) {
                                @Override
                                public void buildDialogue() {
                                    if (enabled) {
                                        npc("Ok, whenever you kill someone now, their items will go to the floor like normal. "
                                                + "Just remember, they could have loot keys on them, and they'll still go to you!");
                                    } else {
                                        npc("Ok, you'll now get loot keys when you kill another person in the Wilderness.");
                                    }
                                }
                            });
                        })
                        .onOptionTwo(() -> {
                            settings.setDropFood(!dropFood);
                            player.getDialogueManager().start(new BackToSettings(player, npc) {
                                @Override
                                public void buildDialogue() {
                                    npc(dropFood
                                            ? "Ok, food and potions will now be sent to your loot keys."
                                            : "Ok, food and potions will now be dropped to the floor instead of stored in a loot key.");
                                }
                            });
                        })
                        .onOptionThree(() -> {
                            settings.setDropValuables(!dropValuables);
                            player.getDialogueManager().start(new BackToSettings(player, npc) {
                                @Override
                                public void buildDialogue() {
                                    npc(dropValuables
                                            ? "Ok, valuable items will now be sent to your loot keys."
                                            : "Ok, items worth at least " + Utils.formatNumberWithCommas(settings.getThreshold())
                                            + "gp will now drop to the floor instead of being stored in a loot key.");
                                }
                            });
                        })
                        .onOptionFour(() -> changeThreshold(player, npc))
                        .onOptionFive(() -> { });
            }
        };
    }

    private void changeThreshold(final Player player, final NPC npc) {
        final LootkeySettings settings = player.getLootkeySettings();
        if (settings == null) {
            return;
        }
        final int current = settings.getThreshold();
        player.getDialogueManager().start(new Dialogue(player, npc) {
            @Override
            public void buildDialogue() {
                npc("Currently, items worth " + Utils.formatNumberWithCommas(current) + "gp or more will drop to the floor "
                        + "when valuables are set to drop. What do you want to change that value to?");
            }

            @Override
            public void finish() {
                super.finish();
                player.sendInputInt("Enter the valuable item threshold:", amount -> {
                    final int value = Math.max(0, amount);
                    settings.setThreshold(value);
                    player.getDialogueManager().start(new BackToSettings(player, npc) {
                        @Override
                        public void buildDialogue() {
                            if (value == current) {
                                npc("That's the same number it already was, mate. Fine, I'll leave it be.");
                            } else {
                                npc("Ok, now items worth at least " + Utils.formatNumberWithCommas(value) + "gp will drop to the floor.");
                            }
                        }
                    });
                });
            }
        });
    }

    private Dialogue whoAreYou(final Player player, final NPC npc) {
        return new Back(player, npc) {
            @Override
            public void buildDialogue() {
                player("Who are you, exactly?");
                npc("Ah, used to be an adventurer like you, I guess. Used to roam the world, killing "
                        + "monsters for a living. 'Cept then, I discovered the Wilderness.");
                npc("Used to take on anyone and everyone who stood in me way, and made myself a small fortune doing it too.");
                npc("It's how I got the name, used to go anywhere and everywhere with a skull over my head!");
                player("So how'd you end up like... this?");
                npc("Ah, you live a life feeding on others, eventually something higher up the food chain finds you.");
                npc("I used to have a hidden outpost near the Bone Yard, somewhere to keep my stuff in an emergency. "
                        + "One day all I come back to is a smouldering pile of rubble.");
                npc("Next thing I know, someone's snuck up behind me and has put a spear through my back.");
                player("And you survived?!", Expression.HIGH_REV_SHOCKED);
                npc("Always carry a ring of life, friend! Never know when you'll need it.", Expression.HIGH_REV_HAPPY);
                npc("Still, the damage was done. Everything I ever owned up in smoke, and even though the wound's fully "
                        + "healed, I can still feel that spear in my back to this day!");
                npc("Thankfully, Ferox was kind enough to take me in, and I managed to repurpose some of "
                        + "my old storage magic for adventurers like you.");
                player("But why come back to the Wilderness?");
                npc("What, go live a normal life behind a shop counter so I can peddle swamp paste for a living instead? I'll pass!");
                npc("Anyhow, enough about me. What can I do for you?");
            }
        };
    }

    private Dialogue chestWorks(final Player player, final NPC npc) {
        return new Back(player, npc) {
            @Override
            public void buildDialogue() {
                npc("Well, you know how when you kill someone all of their stuff just ends up in a pile on the "
                        + "floor and it's really hard to pick it all up quickly?");
                npc("I created an enchantment that teleports the loot to another dimension instead!");
                player("How does that help?");
                npc("Well, instead of getting a pile of rubbish, you get a single key. "
                        + "You take the key to this chest and BAM! There's your loot!");
                player("So the loot is safe in this other dimension?");
                npc("Eh, seems like it. As long as they've had the key, I've never had anyone unable "
                        + "to get the stuff back out. Though if you lose the key, you can't get the stuff out.");
                npc("Oh, and be careful running about with too many keys on you. Other people will know you've got them, "
                        + "and you can't carry more than five at once.");
                player("So if I kill someone and take their keys, I can use them myself?");
                npc("...Eh, if you wanna think about it that way, sure.");
                npc("I got nothing better to do, so I'll keep track of the value of the stuff you unlock.");
                npc("And anything you don't want, you can have the chest destroy it.");
                npc("Now, if you want your own keys instead of relying on the misfortune of others, "
                        + "I can put the enchantment on you as well, but it's gonna cost ya.");
            }
        };
    }

    private Dialogue claimedLoot(final Player player, final NPC npc, final boolean backToMenu) {
        final Dialogue dialogue = new Dialogue(player, npc) {
            @Override
            public void buildDialogue() {
                final LootkeySettings settings = player.getLootkeySettings();
                final int keys = settings == null ? 0 : settings.getKeysClaimed();
                if (keys <= 0) {
                    npc("Well, seeing as you haven't claimed a key yet, I reckon you've claimed a total of 0gp worth of loot.");
                    return;
                }
                final long claimed = settings.getTotalValueClaimed();
                final long destroyed = settings.getDestroyedValue();
                final String keyText = keys == 1 ? "1 key" : keys + " keys";
                if (destroyed <= 0) {
                    npc("You've claimed " + keyText + ", and I reckon you've claimed a total of about "
                            + Utils.formatNumberWithCommas(claimed) + "gp worth of loot. You haven't destroyed anything of value.");
                } else if (claimed <= 0) {
                    npc("You've claimed " + keyText + " worth about " + Utils.formatNumberWithCommas(destroyed)
                            + "gp, and you destroyed everything of value. What a waste!");
                } else {
                    npc("You've claimed " + keyText + ", and I reckon you've claimed a total of about "
                            + Utils.formatNumberWithCommas(claimed) + "gp worth of loot. You've also destroyed about "
                            + Utils.formatNumberWithCommas(destroyed) + "gp worth.");
                }
            }

            @Override
            public void finish() {
                super.finish();
                if (backToMenu) {
                    player.getDialogueManager().start(menu(player, npc));
                }
            }
        };
        return dialogue;
    }

    private Dialogue accessToChest(final Player player, final NPC npc) {
        return new Dialogue(player, npc) {
            @Override
            public void buildDialogue() {
                if (LootKeys.isUnlocked(player)) {
                    npc("You've already got the enchantment, mate. Use my Settings if you want to change how your keys work.");
                    return;
                }
                player("Can I have access to the chest?");
                npc("So, if you have keys from someone else, you're more than welcome to put them in the chest and see what comes out.");
                npc("If you want the enchantment that makes the keys, I'm afraid it's gonna cost you " + COST_WORDS + " coins.");
                options("Select an Option",
                        "Sure, I'll pay for that.",
                        "Not right now, thanks.",
                        "How much?!")
                        .onOptionOne(() -> player.getDialogueManager().start(confirmPurchase(player, npc)))
                        .onOptionTwo(() -> player.getDialogueManager().start(new Back(player, npc) {
                            @Override
                            public void buildDialogue() {
                                player("Not right now, thanks.");
                                npc("Fair enough mate, want to talk about something else?");
                            }
                        }))
                        .onOptionThree(() -> player.getDialogueManager().start(new Dialogue(player, npc) {
                            @Override
                            public void buildDialogue() {
                                player("How much?!");
                                npc(COST_TEXT + " coins. Fair price for never having to scrabble about on the floor again.");
                            }

                            @Override
                            public void finish() {
                                super.finish();
                                player.getDialogueManager().start(accessToChest(player, npc));
                            }
                        }));
            }
        };
    }

    private Dialogue confirmPurchase(final Player player, final NPC npc) {
        return new Dialogue(player, npc) {
            @Override
            public void buildDialogue() {
                player("Sure, I'll pay for that.");
                npc("Are you sure? " + COST_WORDS + " is a lot of coins, and you won't get them back!");
                options("Pay " + COST_TEXT + " coins to unlock loot keys?",
                        "Yes, pay " + COST_WORDS + " coins!",
                        "No, I've changed my mind!")
                        .onOptionOne(() -> purchase(player, npc))
                        .onOptionTwo(() -> player.getDialogueManager().start(new Dialogue(player, npc) {
                            @Override
                            public void buildDialogue() {
                                npc("Fair enough mate, talk to me again if you change your mind.");
                            }
                        }));
            }
        };
    }

    private void purchase(final Player player, final NPC npc) {
        if (LootKeys.isUnlocked(player)) {
            return;
        }
        if (player.getInventory().getAmountOf(ItemId.COINS_995) < UNLOCK_COST) {
            player.getDialogueManager().start(new Dialogue(player, npc) {
                @Override
                public void buildDialogue() {
                    npc("Hmm... I don't think you have enough money on you there, mate.");
                }
            });
            return;
        }
        if (player.getInventory().deleteItem(ItemId.COINS_995, UNLOCK_COST).getSucceededAmount() != UNLOCK_COST) {
            return;
        }
        final LootkeySettings existing = player.getLootkeySettings();
        if (existing != null) {
            // Statistics from keys opened before buying the enchantment are kept.
            existing.setUnlocked(true);
            existing.setEnabled(true);
        } else {
            player.setLootkeySettings(new LootkeySettings(true, false, false, LootkeyConstants.DEFAULT_THRESHOLD, 0, 0));
        }
        player.getDialogueManager().start(new Dialogue(player, npc) {
            @Override
            public void buildDialogue() {
                npc("Ok, let me just... and a bit of... and we're done! "
                        + "You'll now get loot keys whenever you kill someone in the Wilderness. "
                        + "Talk to me again if you have any questions!");
            }
        });
    }
}
