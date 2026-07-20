package com.example.grinderbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.tags.ItemTags;
import java.util.Queue;
import java.util.ArrayDeque;
import java.util.function.Predicate;

import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;

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

    private static final int WAIT_GOTO_START_TIMEOUT = 40;
    private static final int WAIT_MENU_TIMEOUT = 100;

    private static final int GAPPLE_HOTBAR_SLOT = 8;      // reserved hotbar slot for swapping food in
    private static final int REGEN_REFRESH_THRESHOLD = 20; // ticks left before we consider it "ending" (1s)
    private int preGappleHotbarSlot = 0;
    private static final int SWAP_SETTLE_TICKS = 5;    // wait after swapping gapple into hotbar
    private static final int RESTORE_SETTLE_TICKS = 5;  // wait after switching back
    private int pendingGappleInventoryIndex = -1;
    private static final int GAPPLE_EAT_DURATION_TICKS = 32; // vanilla eat animation length

    private static final int ACTION_DELAY_TICKS = 6;           // delay between queued clicks

    private static final String KIT_VOUCHER_NAME = "Kit";      // matches any voucher containing "Kit"
    private static final int VOUCHER_HOTBAR_SLOT = 7;          // reserved slot to hold the voucher temporarily

    private boolean regearing = false;
    private String lastGrindCoords = null;       // set whenever %grind x y z is used
    private volatile boolean genericScreenOpened = false;

    private final Queue<Runnable> pendingActions = new ArrayDeque<>();
    private int actionDelayCounter = 0;
    private State queueFinishedState = State.IDLE;

    // ---- State machine ----
    private enum State {
        IDLE,
        WAIT_FOR_GOTO_START, WAIT_FOR_ARRIVAL,
        ACTIVE,
        GAPPLE_SWAP, WAIT_AFTER_SWAP, GAPPLE_EAT, WAIT_GAPPLE_FINISH, WAIT_AFTER_RESTORE,
        SEND_GIFT, WAIT_GIFT_MENU, PRE_CLICK_DELAY, CLICK_TOKEN, WAIT_TOKEN_APPEAR, CLOSE_GIFT_MENU,
        WAIT_CONFIRM_MENU, WAIT_CONFIRM_ICON, CLICK_CONFIRM,
        // ---- death / regear ----
        WAIT_RESPAWN,
        // ---- vault -> extract voucher -> use voucher -> gear lands directly ----
        WAIT_VAULT_MENU, VAULT_PRE_CLICK_DELAY, EXTRACT_VOUCHER, WAIT_VOUCHER_APPEAR, CLOSE_VAULT_MENU,
        SELECT_VOUCHER, WAIT_AFTER_SELECT, USE_VOUCHER, WAIT_KIT_SETTLE,
        OPEN_INV_SYNC, WAIT_INV_SYNC, CLOSE_INV_SYNC,
        EQUIP_GEAR,
        QUEUE_PROCESSING,
        RETURN_TO_GRIND_GOTO, WAIT_RETURN_GOTO_START, WAIT_RETURN_ARRIVAL
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
                // %grind x y z -> initial move to grinder area, then auto-start on arrival
                String coords = parts[1] + " " + parts[2] + " " + parts[3];
                lastGrindCoords = coords;
                notify("§bHeading to grinder at " + coords);
                getBaritone().getPathingBehavior().cancelEverything();
                getBaritone().getCommandManager().execute("stop");
                getBaritone().getCommandManager().execute("goto " + coords);
                actionTickCounter = 0;
                state = State.WAIT_FOR_GOTO_START;

            } else if (parts.length == 2 && parts[1].equalsIgnoreCase("stop")) {
                running = false;
                getBaritone().getPathingBehavior().cancelEverything();
                getBaritone().getCommandManager().execute("stop");
                notify("§cGrinding stopped");

            } else if (parts.length == 2 && parts[1].equalsIgnoreCase("start")) {
                resumeGrinding();

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
                } else if (state == State.WAIT_VAULT_MENU) {
                    genericScreenOpened = true;
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft client) {
        if (client.player == null || client.level == null) return;

        if (!regearing && client.player.isDeadOrDying()) {
            regearing = true;
            notify("§4Died — respawning and regearing");
            triggerRespawn(client);
            actionTickCounter = 0;
            state = State.WAIT_RESPAWN;
        }

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
                    notify("§aArrived at grind spot");
                    resumeGrinding(); // equivalent to running %grind start once we've arrived
                }
                break;

            case ACTIVE:
                doActiveTick(client);
                break;

            case GAPPLE_SWAP:
                swapGappleIntoHotbar(client, pendingGappleInventoryIndex);
                actionTickCounter = 0;
                state = State.WAIT_AFTER_SWAP;
                break;

            case WAIT_AFTER_SWAP:
                actionTickCounter++;
                if (actionTickCounter >= SWAP_SETTLE_TICKS) {
                    actionTickCounter = 0;
                    state = State.GAPPLE_EAT;
                }
                break;

            case GAPPLE_EAT:
                getBaritone().getPathingBehavior().cancelEverything();
                getBaritone().getInputOverrideHandler().clearAllKeys();
                selectAndEatGapple(client);
                actionTickCounter = 0;
                state = State.WAIT_GAPPLE_FINISH;
                break;

            case WAIT_GAPPLE_FINISH:
                getBaritone().getPathingBehavior().cancelEverything();
                getBaritone().getInputOverrideHandler().clearAllKeys();

                actionTickCounter++;
                if (actionTickCounter >= GAPPLE_EAT_DURATION_TICKS) {
                    client.options.keyUse.setDown(false);
                    restoreHeldSlot(client);
                    actionTickCounter = 0;
                    state = State.WAIT_AFTER_RESTORE;
                }
                break;

            case WAIT_AFTER_RESTORE:
                actionTickCounter++;
                if (actionTickCounter >= RESTORE_SETTLE_TICKS) {
                    notify("§aGapple eaten — resuming grind");
                    attackTickCounter = 0;
                    state = State.ACTIVE;
                }
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
                    actionTickCounter = 0;
                    state = State.PRE_CLICK_DELAY;
                } else if (actionTickCounter > WAIT_MENU_TIMEOUT) {
                    notify("§cGift menu never opened — resuming grind");
                    state = State.ACTIVE;
                }
                break;

            case PRE_CLICK_DELAY:
                actionTickCounter++;
                if (actionTickCounter > 5) {
                    state = State.CLICK_TOKEN;
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
                } else if (actionTickCounter > 40) {
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
                    actionTickCounter = 0;
                    state = State.WAIT_CONFIRM_ICON;
                } else if (actionTickCounter > 60) {
                    state = State.ACTIVE;
                }
                break;

            case WAIT_CONFIRM_ICON:
                actionTickCounter++;
                if (confirmSlotFilled(client, 11)) {
                    actionTickCounter = 0;
                    state = State.CLICK_CONFIRM;
                } else if (actionTickCounter > 40) {
                    notify("§cConfirm icon never appeared — closing anyway");
                    actionTickCounter = 0;
                    state = State.CLICK_CONFIRM;
                }
                break;

            case CLICK_CONFIRM:
                clickConfirmSlot(client);
                if (client.screen != null) {
                    client.player.closeContainer();
                }
                notify("§aGifted token — resuming grind");
                attackTickCounter = 0;
                state = State.ACTIVE;
                break;

            case WAIT_RESPAWN:
                actionTickCounter++;
                if (!client.player.isDeadOrDying()) {
                    actionTickCounter = 0;
                    sendCommand(client, "pv 1");
                    genericScreenOpened = false;
                    state = State.WAIT_VAULT_MENU; // pv 1 opens the vault GUI directly
                } else if (actionTickCounter % 20 == 0) {
                    triggerRespawn(client);
                }
                break;

                // ---- Stage 1: open vault, extract exactly 1 Kit voucher into inventory ----

            case WAIT_VAULT_MENU:
                actionTickCounter++;
                if (genericScreenOpened) {
                    actionTickCounter = 0;
                    state = State.VAULT_PRE_CLICK_DELAY;
                } else if (actionTickCounter > WAIT_MENU_TIMEOUT) {
                    notify("§cVault never opened — aborting regear");
                    regearing = false;
                    state = State.IDLE;
                }
                break;

            case VAULT_PRE_CLICK_DELAY:
                actionTickCounter++;
                if (actionTickCounter >= 5) {
                    actionTickCounter = 0;
                    state = State.EXTRACT_VOUCHER;
                }
                break;

            case EXTRACT_VOUCHER:
                buildSingleVoucherExtractQueue(client);
                actionDelayCounter = 0;
                queueFinishedState = State.WAIT_VOUCHER_APPEAR;
                state = State.QUEUE_PROCESSING;
                break;

            case WAIT_VOUCHER_APPEAR:
                actionTickCounter++;
                if (findVoucherInInventory(client.player) != -1) {
                    actionTickCounter = 0;
                    state = State.CLOSE_VAULT_MENU;
                } else if (actionTickCounter > 40) {
                    notify("§cVoucher never appeared in inventory — closing anyway");
                    actionTickCounter = 0;
                    state = State.CLOSE_VAULT_MENU;
                }
                break;

            case CLOSE_VAULT_MENU:
                if (client.screen != null) {
                    client.player.closeContainer();
                }
                genericScreenOpened = false;
                actionTickCounter = 0;
                state = State.SELECT_VOUCHER;
                break;

                // ---- Stage 2: select voucher, use it — gear lands directly in inventory ----

            case SELECT_VOUCHER:
                int voucherIndex = findVoucherInInventory(client.player);
                if (voucherIndex == -1) {
                    notify("§cVoucher not found — aborting regear");
                    regearing = false;
                    state = State.IDLE;
                    break;
                }
                swapItemIntoHotbar(client, voucherIndex, VOUCHER_HOTBAR_SLOT);
                actionTickCounter = 0;
                state = State.WAIT_AFTER_SELECT;
                break;

            case WAIT_AFTER_SELECT:
                actionTickCounter++;
                if (actionTickCounter >= SWAP_SETTLE_TICKS) {
                    actionTickCounter = 0;
                    state = State.USE_VOUCHER;
                }
                break;

            case USE_VOUCHER:
                client.player.getInventory().setSelectedSlot(VOUCHER_HOTBAR_SLOT);
                client.player.connection.send(new ServerboundSetCarriedItemPacket(VOUCHER_HOTBAR_SLOT));
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                actionTickCounter = 0;
                state = State.WAIT_KIT_SETTLE; // items land directly in inventory, no menu opens
                break;

            case WAIT_KIT_SETTLE:
                actionTickCounter++;
                if (actionTickCounter >= 20) { // ~1s for the server to grant items after use
                    actionTickCounter = 0;
                    state = State.OPEN_INV_SYNC;
                }
                break;

            case OPEN_INV_SYNC:
                client.setScreen(new InventoryScreen(client.player));
                actionTickCounter = 0;
                state = State.WAIT_INV_SYNC;
                break;

            case WAIT_INV_SYNC:
                actionTickCounter++;
                if (actionTickCounter >= 20) { // ~1 second, forces a full inventory sync
                    state = State.CLOSE_INV_SYNC;
                }
                break;

            case CLOSE_INV_SYNC:
                if (client.screen != null) client.player.closeContainer();
                actionTickCounter = 0;
                state = State.EQUIP_GEAR;
                break;

            case EQUIP_GEAR:
                buildEquipQueue(client);
                actionDelayCounter = 0;
                queueFinishedState = State.RETURN_TO_GRIND_GOTO;
                state = State.QUEUE_PROCESSING;
                break;

            case QUEUE_PROCESSING:
                actionDelayCounter++;
                if (actionDelayCounter >= ACTION_DELAY_TICKS) {
                    actionDelayCounter = 0;
                    Runnable next = pendingActions.poll();
                    if (next != null) {
                        next.run();
                    } else {
                        state = queueFinishedState;
                    }
                }
                break;

            case RETURN_TO_GRIND_GOTO:
                if (lastGrindCoords != null) {
                    notify("§bGeared up — returning to grind spot");
                    getBaritone().getCommandManager().execute("goto " + lastGrindCoords);
                    actionTickCounter = 0;
                    state = State.WAIT_RETURN_GOTO_START;
                } else {
                    notify("§cNo saved grind coords — use %grind x y z once");
                    regearing = false;
                    state = State.IDLE;
                }
                break;

            case WAIT_RETURN_GOTO_START:
                actionTickCounter++;
                if (isBaritoneActive()) {
                    state = State.WAIT_RETURN_ARRIVAL;
                } else if (actionTickCounter > WAIT_GOTO_START_TIMEOUT) {
                    // Likely already close enough that Baritone had nothing to path —
                    // treat as arrived rather than as a failure
                    notify("§aAlready near grind spot — resuming");
                    regearing = false;
                    resumeGrinding();
                }
                break;

            case WAIT_RETURN_ARRIVAL:
                if (!isBaritoneActive()) {
                    notify("§aBack at grind spot — resuming");
                    regearing = false;
                    resumeGrinding();
                }
                break;
        }
    }

    /** Equivalent to typing "%grind start" — resumes/starts the auto-grind loop
     *  from wherever the player currently is. */
    private void resumeGrinding() {
        running = true;
        attackTickCounter = 0;
        state = State.ACTIVE;
        notify("§aGrinding resumed");
    }

    private boolean confirmSlotFilled(Minecraft client, int slotIndex) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return false;
        AbstractContainerMenu menu = screen.getMenu();
        if (slotIndex >= menu.slots.size()) return false;
        return !menu.slots.get(slotIndex).getItem().isEmpty();
    }

    private boolean giftSlotFilled(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return false;
        AbstractContainerMenu menu = screen.getMenu();
        LocalPlayer player = client.player;

        for (var slot : menu.slots) {
            if (slot.container != player.getInventory() && !slot.getItem().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void doActiveTick(Minecraft client) {
        LocalPlayer player = client.player;

        if (!running) {
            if (isBaritoneActive()) {
                getBaritone().getPathingBehavior().cancelEverything();
                getBaritone().getCommandManager().execute("stop");
            }
            return;
        }

        if (!regearing && isMissingGear(player)) {
            regearing = true;
            notify("§cMissing gear — starting regear sequence");
            if (isBaritoneActive()) getBaritone().getPathingBehavior().cancelEverything();
            sendCommand(client, "pv 1");
            genericScreenOpened = false;
            actionTickCounter = 0;
            state = State.WAIT_VAULT_MENU;
            return;
        }

        // 1. Grind Tokens
        int tokenCount = countGrindTokens(player);
        if (tokenCount >= TOKEN_THRESHOLD) {
            notify("§e" + tokenCount + " Grind Tokens collected — pausing to gift");
            if (isBaritoneActive()) {
                getBaritone().getPathingBehavior().cancelEverything();
            }
            state = State.SEND_GIFT;
            return;
        }

        // 2. Gapple — only when Baritone isn't currently mid-path for any reason
        if (!isBaritoneActive() && needsGapple(player)) {
            int gappleIndex = findGappleInventoryIndex(player);
            if (gappleIndex != -1) {
                notify("§dRegeneration low — eating gapple");
                pendingGappleInventoryIndex = gappleIndex;
                actionTickCounter = 0;
                state = State.GAPPLE_SWAP;
                return;
            }
        }

        // 3. Find nearest endermite within search radius
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
            return;
        }

        double distance = Math.sqrt(nearestDistSq);
        faceEntity(player, nearest);

        if (distance > ATTACK_RANGE) {
            if (!isBaritoneActive()) {
                BlockPos pos = nearest.blockPosition();
                getBaritone().getCommandManager().execute(
                        "goto " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            }
            return;
        }

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
        LocalPlayer player = client.player;

        for (var slot : menu.slots) {
            if (slot.container == player.getInventory() && !slot.getItem().isEmpty()
                    && isGrindToken(slot.getItem())) {
                client.gameMode.handleInventoryMouseClick(
                        menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, player);
                return;
                    }
        }
        notify("§cCouldn't find token slot in gift menu");
    }

    private void clickConfirmSlot(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return;
        AbstractContainerMenu menu = screen.getMenu();
        client.gameMode.handleInventoryMouseClick(
                menu.containerId, 11, 0, ClickType.QUICK_MOVE, client.player);
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

    private boolean needsGapple(LocalPlayer player) {
        MobEffectInstance regen = player.getEffect(MobEffects.REGENERATION);
        return regen == null || regen.getDuration() <= REGEN_REFRESH_THRESHOLD;
    }

    private int findGappleInventoryIndex(LocalPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE) {
                return i;
            }
        }
        return -1;
    }

    private int toMenuSlotIndex(int inventoryIndex) {
        // Inventory class: 0-8 hotbar, 9-35 main storage
        // InventoryMenu:    9-35 main storage (same), 36-44 hotbar
        return inventoryIndex < 9 ? inventoryIndex + 36 : inventoryIndex;
    }

    private void swapGappleIntoHotbar(Minecraft client, int inventoryIndex) {
        LocalPlayer player = client.player;
        int menuSlot = toMenuSlotIndex(inventoryIndex);

        preGappleHotbarSlot = player.getInventory().getSelectedSlot();

        client.gameMode.handleInventoryMouseClick(
                player.inventoryMenu.containerId, menuSlot, GAPPLE_HOTBAR_SLOT, ClickType.SWAP, player);
    }

    private void selectAndEatGapple(Minecraft client) {
        LocalPlayer player = client.player;

        player.getInventory().setSelectedSlot(GAPPLE_HOTBAR_SLOT);
        player.connection.send(new ServerboundSetCarriedItemPacket(GAPPLE_HOTBAR_SLOT));

        client.options.keyUse.setDown(true); // trick vanilla into thinking right-click is held
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
    }

    private void restoreHeldSlot(Minecraft client) {
        LocalPlayer player = client.player;
        player.getInventory().setSelectedSlot(preGappleHotbarSlot);
        player.connection.send(new ServerboundSetCarriedItemPacket(preGappleHotbarSlot));
    }

    private void triggerRespawn(Minecraft client) {
        client.player.connection.send(
                new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
    }

    private boolean isMissingGear(LocalPlayer player) {
        boolean noChest = player.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
        boolean noSword = !hasSwordInHotbar(player);
        return noChest || noSword;
    }

    private boolean hasSwordInHotbar(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES))) {
                return true;
            }
        }
        return false;
    }

    /** Searches the player's own inventory slots for the first item whose
     *  display name contains {@code KIT_VOUCHER_NAME}. Returns raw inventory index (0-35), or -1. */
    private int findVoucherInInventory(LocalPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getHoverName() != null
                    && stack.getHoverName().getString().contains(KIT_VOUCHER_NAME)) {
                return i;
                    }
        }
        return -1;
    }

    /** Swaps (press-number-key style) the item at the given raw inventory index
     *  into the given hotbar slot (0-8), using the always-valid player inventory menu. */
    private void swapItemIntoHotbar(Minecraft client, int inventoryIndex, int hotbarSlot) {
        LocalPlayer player = client.player;
        int menuSlot = toMenuSlotIndex(inventoryIndex);
        client.gameMode.handleInventoryMouseClick(
                player.inventoryMenu.containerId, menuSlot, hotbarSlot, ClickType.SWAP, player);
    }

    /** Extracts exactly ONE voucher from the vault's own slots into the player's
     *  inventory, using the classic pickup/split/return-remainder 3-click trick. */
    private void buildSingleVoucherExtractQueue(Minecraft client) {
        pendingActions.clear();
        if (!(client.screen instanceof AbstractContainerScreen<?> screen)) return;
        AbstractContainerMenu menu = screen.getMenu();
        LocalPlayer player = client.player;

        Integer sourceIndex = null;
        Integer emptyTargetIndex = null;

        for (var slot : menu.slots) {
            boolean isPlayerSlot = slot.container == player.getInventory();
            ItemStack stack = slot.getItem();

            if (!isPlayerSlot && sourceIndex == null && !stack.isEmpty()
                    && stack.getHoverName() != null
                    && stack.getHoverName().getString().contains(KIT_VOUCHER_NAME)) {
                sourceIndex = slot.index;
                    }
            if (isPlayerSlot && emptyTargetIndex == null && stack.isEmpty()) {
                emptyTargetIndex = slot.index;
            }
        }

        if (sourceIndex == null) {
            notify("§cCouldn't find voucher stack in vault");
            return;
        }
        if (emptyTargetIndex == null) {
            notify("§cNo empty inventory slot to split voucher into");
            return;
        }

        final int src = sourceIndex;
        final int dst = emptyTargetIndex;

        // 1. Left-click source: whole stack jumps onto cursor
        pendingActions.add(() -> Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                    menu.containerId, src, 0, ClickType.PICKUP, player));
        // 2. Right-click an empty slot: exactly 1 item drops there, remainder stays on cursor
        pendingActions.add(() -> Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                    menu.containerId, dst, 1, ClickType.PICKUP, player));
        // 3. Left-click source again (now empty): remainder from cursor goes back
        pendingActions.add(() -> Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                    menu.containerId, src, 0, ClickType.PICKUP, player));
    }

    private void buildEquipQueue(Minecraft client) {
        pendingActions.clear();
        LocalPlayer player = client.player;
        AbstractContainerMenu menu = player.inventoryMenu;

        queueArmorMove(menu, player, EquipmentSlot.HEAD, 5);
        queueArmorMove(menu, player, EquipmentSlot.CHEST, 6);
        queueArmorMove(menu, player, EquipmentSlot.LEGS, 7);
        queueArmorMove(menu, player, EquipmentSlot.FEET, 8);

        queueItemToHotbarSlot(menu, player,
                stack -> stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES), 36);
        queueItemToHotbarSlot(menu, player, stack -> stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE, 44);
    }

    private void queueArmorMove(AbstractContainerMenu menu, LocalPlayer player,
            EquipmentSlot equipSlot, int armorMenuIndex) {
        for (var slot : menu.slots) {
            if (slot.index < 9 || slot.index > 44) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            if (equippable != null && equippable.slot() == equipSlot) {
                int sourceIndex = slot.index;
                pendingActions.add(() -> Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                            menu.containerId, sourceIndex, 0, ClickType.PICKUP, player));
                pendingActions.add(() -> Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                            menu.containerId, armorMenuIndex, 0, ClickType.PICKUP, player));
                return;
            }
        }
    }

    private void queueItemToHotbarSlot(AbstractContainerMenu menu, LocalPlayer player,
            Predicate<ItemStack> predicate, int destMenuIndex) {
        for (var slot : menu.slots) {
            if (slot.index < 9 || slot.index > 44 || slot.index == destMenuIndex) continue;
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && predicate.test(stack)) {
                int sourceIndex = slot.index;
                pendingActions.add(() -> Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                            menu.containerId, sourceIndex, 0, ClickType.PICKUP, player));
                pendingActions.add(() -> Minecraft.getInstance().gameMode.handleInventoryMouseClick(
                            menu.containerId, destMenuIndex, 0, ClickType.PICKUP, player));
                return;
            }
        }
    }
}
