package com.example.grinderbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;

public class GrinderBotClient implements ClientModInitializer {

    // ---- Tunables ----
    private static final int ATTACK_INTERVAL_TICKS = 2; // 20 ticks/sec ÷ 10 cps = every 2 ticks
    private static final String GRIND_TOKEN_NAME = "Grind Token";
    private static final String GIFT_TARGET = "shivanshu6";

    private static final int WAIT_GOTO_START_TIMEOUT = 40;
    private static final int WAIT_MENU_TIMEOUT = 100;

    // ---- State machine ----
    private enum State {
        IDLE,
        WAIT_FOR_GOTO_START, WAIT_FOR_ARRIVAL,
        KILLING,
        SEND_GIFT, WAIT_GIFT_MENU, CLICK_TOKEN, CLOSE_GIFT_MENU,
        WAIT_CONFIRM_MENU, CLICK_CONFIRM
    }
    private State state = State.IDLE;

    private int actionTickCounter = 0;
    private int attackTickCounter = 0;

    private volatile boolean giftScreenOpened = false;
    private volatile boolean confirmScreenOpened = false;

    private int tokenSlotIndex = -1; // slot found holding the Grind Token, set when detected

    @Override
    public void onInitializeClient() {

        // Intercept "$grind x y z" -> goto coords, then auto-start killing loop on arrival
        // Other "$..." messages still pass straight to Baritone, same as your mining mod
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (message.startsWith("$")) {
                String command = message.substring(1).trim();

                if (command.toLowerCase().startsWith("grind ")) {
                    String[] parts = command.split("\\s+");
                    if (parts.length == 4) {
                        String coords = parts[1] + " " + parts[2] + " " + parts[3];
                        notify("§bHeading to grinder at " + coords);
                        getBaritone().getPathingBehavior().cancelEverything();
                        getBaritone().getCommandManager().execute("stop");
                        getBaritone().getCommandManager().execute("goto " + coords);
                        actionTickCounter = 0;
                        state = State.WAIT_FOR_GOTO_START;
                    } else {
                        notify("§cUsage: $grind x y z");
                    }
                } else if (!command.isEmpty()) {
                    getBaritone().getCommandManager().execute(command);
                }
                return false; // cancel actual chat send either way
            }
            return true;
        });

        // Detect when the gift menu / confirmation menu opens
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen) {
                if (state == State.WAIT_GIFT_MENU) {
                    giftScreenOpened = true;
                } else if (state == State.WAIT_CONFIRM_MENU) {
                    confirmScreenOpened = true;
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft client) {
        if (client.player == null) return;

        switch (state) {
            case IDLE:
                // nothing happening until "$grind x y z" is sent
                break;

            case WAIT_FOR_GOTO_START:
                actionTickCounter++;
                if (isBaritoneActive()) {
                    state = State.WAIT_FOR_ARRIVAL;
                } else if (actionTickCounter > WAIT_GOTO_START_TIMEOUT) {
                    notify("§cGoto never started — check coordinates");
                    state = State.IDLE;
                }
                break;

            case WAIT_FOR_ARRIVAL:
                if (!isBaritoneActive()) {
                    notify("§aArrived — starting kill loop");
                    attackTickCounter = 0;
                    state = State.KILLING;
                }
                break;

            case KILLING:
                doKillingTick(client);
                break;

            case SEND_GIFT:
                sendCommand(client, "gift " + GIFT_TARGET);
                giftScreenOpened = false;
                actionTickCounter = 0;
                state = State.WAIT_GIFT_MENU;
                break;

            case WAIT_GIFT_MENU:
                actionTickCounter++;
                if (giftScreenOpened) {
                    state = State.CLICK_TOKEN;
                } else if (actionTickCounter > WAIT_MENU_TIMEOUT) {
                    notify("§cGift menu never opened — resuming kill loop");
                    state = State.KILLING;
                }
                break;

            case CLICK_TOKEN:
                clickTokenSlot(client);
                actionTickCounter = 0;
                state = State.CLOSE_GIFT_MENU;
                break;

            case CLOSE_GIFT_MENU:
                if (client.screen != null) {
                    client.player.closeContainer();
                }
                confirmScreenOpened = false;
                actionTickCounter = 0;
                state = State.WAIT_CONFIRM_MENU;
                break;

            case WAIT_CONFIRM_MENU:
                actionTickCounter++;
                if (confirmScreenOpened) {
                    state = State.CLICK_CONFIRM;
                } else if (actionTickCounter > 60) {
                    // no confirmation appeared, just resume killing
                    state = State.KILLING;
                }
                break;

            case CLICK_CONFIRM:
                clickSlotZero(client);
                if (client.screen != null) {
                    client.player.closeContainer();
                }
                notify("§aGifted token — resuming kill loop");
                attackTickCounter = 0;
                state = State.KILLING;
                break;
        }
    }

    private void doKillingTick(Minecraft client) {
        // 1. Check inventory for a Grind Token first — pause killing if found
        int slot = findGrindTokenSlot(client.player);
        if (slot != -1) {
            tokenSlotIndex = slot;
            notify("§eGrind Token found — pausing to gift");
            state = State.SEND_GIFT;
            return;
        }

        // 2. Attack loop at 10 cps
        attackTickCounter++;
        if (attackTickCounter < ATTACK_INTERVAL_TICKS) return;
        attackTickCounter = 0;

        HitResult hit = client.hitResult;
        if (hit instanceof EntityHitResult entityHit) {
            Entity target = entityHit.getEntity();
            if (target instanceof Endermite) {
                client.gameMode.attack(client.player, target);
                client.player.swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    private int findGrindTokenSlot(LocalPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && isGrindToken(stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isGrindToken(ItemStack stack) {
        Component name = stack.getHoverName();
        return name != null && name.getString().contains(GRIND_TOKEN_NAME);
    }

    private void clickTokenSlot(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return;
        AbstractContainerMenu menu = screen.getMenu();
        LocalPlayer player = client.player;

        // Find the slot in the OPEN MENU (not raw inventory index) that holds
        // the Grind Token, since menu slot indices differ from inventory indices
        // once wrapped in a container screen.
        for (var slot : menu.slots) {
            if (slot.container == player.getInventory() && !slot.getItem().isEmpty()
                    && isGrindToken(slot.getItem())) {
                client.gameMode.handleInventoryMouseClick(
                        menu.containerId, slot.index, 0, ClickType.PICKUP, player);
                return;
            }
        }
        notify("§cCouldn't find token slot in gift menu");
    }

    private void clickSlotZero(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return;
        AbstractContainerMenu menu = screen.getMenu();
        client.gameMode.handleInventoryMouseClick(
                menu.containerId, 0, 0, ClickType.PICKUP, client.player);
    }

    private void sendCommand(Minecraft client, String command) {
        client.player.connection.sendCommand(command);
    }

    private boolean isBaritoneActive() {
        IBaritone baritone = getBaritone();
        return baritone.getPathingBehavior().isPathing()
                || baritone.getPathingBehavior().hasPath();
    }

    private IBaritone getBaritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    private void notify(String msg) {
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal(msg));
    }
}
