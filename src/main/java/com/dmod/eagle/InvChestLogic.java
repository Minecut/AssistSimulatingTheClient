package com.dmod.eagle;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Rarity;

/**
 * Chest looting, one item at a time and deliberately slowly.
 *
 * <p>There is no packet forging here. Moving a stack out of a container is a thing the game already
 * knows how to do: {@code ClientPlayerInteractionManager#clickSlot} with {@link SlotActionType#QUICK_MOVE}
 * is exactly the shift-click a player performs, so the server sees a normal container interaction and
 * the client's own prediction stays in step.
 *
 * <p>The work is entirely in <em>what</em> to take and <em>when</em>:
 *
 * <ul>
 *   <li>Contents are scored into five tiers, see {@link #tierOf}. The highest tier available wins,
 *       then the biggest stack, so valuable things come out first.</li>
 *   <li>Anything the player already carries is skipped, so a chest full of cobblestone does not bury
 *       the one diamond.</li>
 *   <li>One click per {@code invChestDelayMs}, default 850 ms, which is roughly how fast a person
 *       empties a chest by hand.</li>
 * </ul>
 */
public final class InvChestLogic {

    /** Highest tier a stack can reach. */
    private static final int MAX_TIER = 5;

    /** Explicit tiers. Anything absent falls through to the rarity based guess. */
    private static final Map<Item, Integer> TIERS = new HashMap<>();

    private static long nextTakeAt = 0L;
    private static int lastSyncId = -1;
    private static boolean taking = false;

    private InvChestLogic() {
    }

    /** True when a stack was moved during the current tick. Drives the HUD. */
    public static boolean isTaking() {
        return taking;
    }

    public static void reset() {
        nextTakeAt = 0L;
        lastSyncId = -1;
        taking = false;
    }

    public static void onEndTick(MinecraftClient mc) {
        taking = false;

        EagleConfig cfg = EagleConfig.get();

        if (!cfg.invChestEnabled
                || mc == null
                || mc.player == null
                || mc.world == null
                || mc.interactionManager == null
                || !(mc.currentScreen instanceof HandledScreen<?>)) {
            reset();
            return;
        }

        ClientPlayerEntity player = mc.player;
        ScreenHandler handler = player.currentScreenHandler;

        if (handler == null) {
            reset();
            return;
        }

        int containerSlots = containerSlotCount(handler);

        if (containerSlots <= 0) {
            reset();
            return;
        }

        // A different container just opened: give it one full interval before touching anything, so
        // opening a chest never produces an instant click.
        if (handler.syncId != lastSyncId) {
            lastSyncId = handler.syncId;
            nextTakeAt = System.currentTimeMillis() + Math.max(0, cfg.invChestDelayMs);
            return;
        }

        long now = System.currentTimeMillis();

        if (now < nextTakeAt) {
            return;
        }

        int slot = pickSlot(player, handler, containerSlots, cfg);

        if (slot < 0) {
            if (cfg.invChestCloseWhenDone) {
                player.closeHandledScreen();
                reset();
            }
            return;
        }

        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, player);

        nextTakeAt = now + Math.max(50, cfg.invChestDelayMs);
        taking = true;
    }

    // ------------------------------------------------------------------ picking

    /**
     * The container slot worth taking next, or {@code -1} when nothing qualifies.
     *
     * <p>Highest tier first, then the largest stack. The stack size matters because a click moves a
     * whole stack, so a stack of 64 is worth far more than a stack of 1 for the same latency.
     */
    private static int pickSlot(ClientPlayerEntity player, ScreenHandler handler, int containerSlots, EagleConfig cfg) {
        int bestSlot = -1;
        int bestTier = Integer.MIN_VALUE;
        int bestCount = -1;

        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();

            if (stack.isEmpty()) {
                continue;
            }

            if (cfg.invChestSkipExisting && inventoryHas(player, stack)) {
                continue;
            }

            int tier = tierOf(stack);

            if (tier < cfg.invChestMinTier) {
                continue;
            }

            if (tier > bestTier || (tier == bestTier && stack.getCount() > bestCount)) {
                bestTier = tier;
                bestCount = stack.getCount();
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    private static boolean inventoryHas(ClientPlayerEntity player, ItemStack stack) {
        Item item = stack.getItem();
        PlayerInventory inventory = player.getInventory();

        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).getItem() == item) {
                return true;
            }
        }

        return player.getOffHandStack().getItem() == item;
    }

    /**
     * How many of the handler's slots belong to the container rather than the player.
     *
     * <p>Only real storage screens are handled. The player's own inventory, the crafting table, the
     * furnace and friends all fall through to zero and are left alone.
     */
    private static int containerSlotCount(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler generic) {
            // Chests, barrels, ender chests, dispensers and droppers all share this handler.
            return generic.getRows() * 9;
        }

        if (handler instanceof ShulkerBoxScreenHandler) {
            return 27;
        }

        return 0;
    }

    // ------------------------------------------------------------------ item tiers

    /**
     * Scores a stack from 1 (junk) to 5 (never leave it behind).
     *
     * <p>Vanilla rarity is a poor guide on its own - diamond and netherite gear are both {@code COMMON}
     * - so the items that actually matter are listed explicitly and everything else falls back to its
     * rarity. Enchantments then lift anything they touch, because an enchanted iron sword is worth
     * more than the plain one sitting next to it.
     */
    private static int tierOf(ItemStack stack) {
        Item item = stack.getItem();

        // Every shulker box colour, without listing all seventeen of them.
        if (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
            return MAX_TIER;
        }

        Integer known = TIERS.get(item);
        int tier = known != null ? known : fallbackTier(stack);

        if (stack.hasEnchantments()) {
            tier = Math.max(tier, 3) + 1;
        }

        return Math.min(MAX_TIER, tier);
    }

    private static int fallbackTier(ItemStack stack) {
        Rarity rarity = stack.getRarity();

        if (rarity == Rarity.EPIC) {
            return 4;
        }
        if (rarity == Rarity.RARE) {
            return 3;
        }
        if (rarity == Rarity.UNCOMMON) {
            return 2;
        }
        return 1;
    }

    private static void tier(int level, Item... items) {
        for (Item item : items) {
            TIERS.put(item, level);
        }
    }

    static {
        tier(5,
                Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
                Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL,
                Items.NETHERITE_HOE,
                Items.NETHERITE_INGOT, Items.NETHERITE_BLOCK, Items.NETHERITE_SCRAP, Items.ANCIENT_DEBRIS,
                Items.ELYTRA, Items.TOTEM_OF_UNDYING, Items.ENCHANTED_GOLDEN_APPLE,
                Items.NETHER_STAR, Items.DRAGON_EGG, Items.BEACON, Items.CONDUIT, Items.ENCHANTED_BOOK);

        tier(4,
                Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
                Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE,
                Items.DIAMOND, Items.DIAMOND_BLOCK,
                Items.EMERALD, Items.EMERALD_BLOCK,
                Items.TRIDENT, Items.CROSSBOW, Items.BOW, Items.END_CRYSTAL, Items.RESPAWN_ANCHOR,
                Items.GOLDEN_APPLE, Items.EXPERIENCE_BOTTLE,
                Items.ENDER_PEARL, Items.ENDER_EYE, Items.SHULKER_SHELL);

        tier(3,
                Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
                Items.IRON_SWORD, Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL, Items.IRON_HOE,
                Items.IRON_INGOT, Items.IRON_BLOCK, Items.GOLD_INGOT, Items.GOLD_BLOCK,
                Items.SHIELD, Items.OBSIDIAN, Items.CRYING_OBSIDIAN,
                Items.REDSTONE, Items.REDSTONE_BLOCK, Items.LAPIS_LAZULI, Items.LAPIS_BLOCK,
                Items.QUARTZ, Items.AMETHYST_SHARD,
                Items.ARROW, Items.SPECTRAL_ARROW, Items.TIPPED_ARROW,
                Items.BLAZE_ROD, Items.BLAZE_POWDER, Items.END_ROD, Items.GOLDEN_CARROT,
                Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON,
                Items.COOKED_SALMON, Items.COOKED_COD, Items.BREAD);

        tier(2,
                Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS,
                Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS,
                Items.CHAINMAIL_BOOTS,
                Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS,
                Items.STONE_SWORD, Items.STONE_PICKAXE, Items.STONE_AXE, Items.IRON_NUGGET, Items.GOLD_NUGGET,
                Items.COAL, Items.CHARCOAL, Items.COAL_BLOCK, Items.LEATHER, Items.STRING, Items.FEATHER,
                Items.FLINT, Items.STICK, Items.BONE, Items.SLIME_BALL, Items.GUNPOWDER, Items.EGG);
    }
}
