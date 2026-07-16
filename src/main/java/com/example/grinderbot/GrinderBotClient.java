package com.example.grinderbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;

public class GrinderBotClient implements ClientModInitializer {

    // ---- Tunables ----
    private static final int ATTACK_INTERVAL_TICKS = 2;   // 20 ticks/sec ÷ 10 cps
    private static final double ATTACK_RANGE = 3.0;        // blocks, 1.8-style reach
    private static final double SEARCH_RADIUS = 24.0;      // blocks, how far to look for mites
    private static final String GRIND_TOKEN_NAME = "Grind Token";
    private static final String GIFT_TARGET = "shivanshu7";
    private static final int TOKEN_THRESHOLD = 5;
    private static final int GIFT_MENU_TOKEN_SLOT = 11;

    private static final int WAIT_GOTO_START_TIMEOUT = 40;
    private static final int WAIT_MENU_TIMEOUT = 100;

    // ---- State machine ----
    private enum State {
        IDLE,
        WAIT_FOR_GOTO_START, WAIT_FOR_ARRIVAL,
        ACTIVE,
        SEND_GIFT, WAIT_GIFT_MENU, CLICK_TOKEN, WAIT_TOKEN_APPEAR, CLOSE_GIFT_MENU,
        WAIT_CONFIRM_MENU, CLICK_CONFIRM
    }
    private State state = State.IDLE;

    private boolean running = false; // master on/off switch

    private int actionTickCounter = 0;
    private int attackTickCounter = 0;

    private volatile boolean giftScreenOpened = false;
    private volatile boolean confirmScreenOpened = false;

    @Override
    public void onInitializeClient() {

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!message.toLowerCase().startsWith("%grind")) {
                return true; // not ours, let it through untouched
            }

            String command = message.substring(1).trim(); // "grind ..."
            String[] parts = command.split("\\s+");

            if (parts.length == 4) {
                // %grind x y z -> initial move to grinder area, then auto-start
                String coords = parts[1] + " " + parts[2] + " " + parts[3];
                notify("§bHeading to grinder at " + coords);
                getBaritone().getPathingBehavior().cancelEverything();
                getBaritone().getCommandManager().execute("stop");
                getBaritone().getCommandManager().execute("goto " + coords);
                running = true;
                actionTickCounter = 0;
                state = State.WAIT_FOR_GOTO_START;

            } else if (parts.length == 2 && parts[1].equalsIgnoreCase("stop")) {
                running = false;
                getBaritone().getPathingBehavior().cancelEverything();
                getBaritone().getCommandManager().execute("stop");
                notify("§cGrinding stopped");

            } else if (parts.length == 2 && parts[1].equalsIgnoreCase("start")) {
                running = true;
                if (state == State.IDLE) {
                    state = State.ACTIVE; // resume from current position, auto-seeks target
                }
                notify("§aGrinding resumed");

            } else {
                notify("§cUsage: %grind x y z | %grind start | %grind stop");
            }

            return false; // cancel actual chat send in all cases
        });

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
        if (client.player == null || client.level == null) return;

        switch (state) {
            case IDLE:
                break; // waiting for a %grind command

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
                    notify("§aArrived — starting auto-grind");
                    attackTickCounter = 0;
                    state = State.ACTIVE;
                }
                break;

            case ACTIVE:
                doActiveTick(client);
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
                    notify("§cGift menu never opened — resuming grind");
                    state = State.ACTIVE;
                }
                break;

            case CLICK_TOKEN:
                clickGiftTokenSlot(client);
                actionTickCounter = 0;
                state = State.WAIT_TOKEN_APPEAR;
                break;
            case WAIT_TOKEN_APPEAR:
                actionTickCounter++;
                if (giftSlotFilled(client)) {
                    actionTickCounter = 0;
                    state = State.CLOSE_GIFT_MENU;
                } else if (actionTickCounter > 40) { // ~2 seconds, safety timeout
                    notify("§cToken never appeared in gift slot — closing anyway");
                    actionTickCounter = 0;
                    state = State.CLOSE_GIFT_MENU;
                }
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
                    state = State.ACTIVE;
                }
                break;

            case CLICK_CONFIRM:
                clickSlotZero(client);
                if (client.screen != null) {
                    client.player.closeContainer();
                }
                notify("§aGifted token — resuming grind");
                attackTickCounter = 0;
                state = State.ACTIVE;
                break;
        }
    }

    private boolean giftSlotFilled(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return false;
        AbstractContainerMenu menu = screen.getMenu();
        LocalPlayer player = client.player;

        for (var slot : menu.slots) {
            // Only check the container's own slots, not the player's inventory row
            if (slot.container != player.getInventory() && !slot.getItem().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void doActiveTick(Minecraft client) {
        LocalPlayer player = client.player;

        // Master switch check: if stopped, make sure we're not still moving, then idle
        if (!running) {
            if (isBaritoneActive()) {
                getBaritone().getPathingBehavior().cancelEverything();
                getBaritone().getCommandManager().execute("stop");
            }
            return;
        }

        // 1. Grind Tokens take priority once we've stacked up enough of them
        int tokenCount = countGrindTokens(player);
        if (tokenCount >= TOKEN_THRESHOLD) {
            notify("§e" + tokenCount + " Grind Tokens collected — pausing to gift");
            if (isBaritoneActive()) {
                getBaritone().getPathingBehavior().cancelEverything();
            }
            state = State.SEND_GIFT;
            return;
        }

        // 2. Find nearest endermite within search radius
        AABB searchBox = player.getBoundingBox().inflate(SEARCH_RADIUS);
        var candidates = client.level.getEntitiesOfClass(Endermite.class, searchBox);

        Endermite nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Endermite mite : candidates) {
            double distSq = mite.distanceToSqr(player);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = mite;
            }
        }

        if (nearest == null) {
            // nothing to fight right now
            return;
        }

        double distance = Math.sqrt(nearestDistSq);
        faceEntity(player, nearest);

        if (distance > ATTACK_RANGE) {
            // out of range -> path to it, but don't spam goto every tick
            if (!isBaritoneActive()) {
                BlockPos pos = nearest.blockPosition();
                getBaritone().getCommandManager().execute(
                        "goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            }
            return;
        }

        // in range -> stop any movement, then attack at 10 cps
        if (isBaritoneActive()) {
            getBaritone().getPathingBehavior().cancelEverything();
        }

        attackTickCounter++;
        if (attackTickCounter >= ATTACK_INTERVAL_TICKS) {
            attackTickCounter = 0;
            client.gameMode.attack(player, nearest);
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private void faceEntity(LocalPlayer player, Endermite target) {
        double dx = target.getX() - player.getX();
        double dy = (target.getY() + target.getEyeHeight() * 0.5)
            - (player.getY() + player.getEyeHeight());
        double dz = target.getZ() - player.getZ();

        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));

        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    private int countGrindTokens(LocalPlayer player) {
        var inventory = player.getInventory();
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && isGrindToken(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean isGrindToken(ItemStack stack) {
        Component name = stack.getHoverName();
        return name != null && name.getString().contains(GRIND_TOKEN_NAME);
    }

    private void clickGiftTokenSlot(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return;
        AbstractContainerMenu menu = screen.getMenu();
        client.gameMode.handleInventoryMouseClick(
                menu.containerId, GIFT_MENU_TOKEN_SLOT, 0, ClickType.PICKUP, client.player);
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
