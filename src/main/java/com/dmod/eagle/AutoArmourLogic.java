package com.dmod.eagle;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Wears the best armour the player is carrying.
 *
 * <p>Equipping happens through the player's own inventory handler, which is always synced whether or
 * not a screen is open, by sending shift-clicks at it. Shift-clicking an armour piece in the inventory
 * is a thing vanilla already does - {@code PlayerScreenHandler.quickMove} spots the
 * {@code EquipmentSlot.Type.HUMANOID_ARMOR} and moves it across.
 *
 * <p>There is a catch in that branch, though: it only fires when the armour slot is <em>empty</em>
 * ({@code !this.slots.get(...).hasStack()}). So a replacement is two clicks - shift-click the worn
 * piece out to the inventory, then shift-click the new one in. Armour does not stack, so the second
 * click cannot disturb the first and the candidate's slot index stays valid.
 *
 * <p>One piece per {@code autoArmourDelayMs}, rather than four at once, so a full re-gear reads as a
 * sequence of deliberate swaps instead of a burst.
 */
public final class AutoArmourLogic {

    /** Head down to feet; {@code getEntitySlotId()} runs 3, 2, 1, 0 in that order. */
    private static final EquipmentSlot[] ARMOUR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** 27 main inventory slots plus 9 hotbar, starting right after the equipment slots. */
    private static final int INVENTORY_SIZE = 36;

    private static final Map<Item, Integer> MATERIAL_TIERS = new HashMap<>();

    /** Anything slower than 0.01 blocks per tick counts as standing still. */
    private static final double IDLE_SPEED_SQUARED = 1.0E-4D;

    private static long nextCheckAt;
    private static boolean equippedThisTick;

    private AutoArmourLogic() {
    }

    /** True when something was swapped during the current tick. Drives the HUD. */
    public static boolean isEquipping() {
        return equippedThisTick;
    }

    public static void reset() {
        nextCheckAt = 0L;
        equippedThisTick = false;
    }

    public static void onEndTick(MinecraftClient mc) {
        equippedThisTick = false;

        EagleConfig cfg = EagleConfig.get();

        if (!cfg.autoArmourEnabled
                || mc == null
                || mc.player == null
                || mc.world == null
                || mc.interactionManager == null
                // Any open container would make the player's own handler the wrong one to click at.
                || mc.currentScreen != null) {
            return;
        }

        ClientPlayerEntity player = mc.player;

        if (!player.isAlive() || player.isSpectator()) {
            return;
        }

        // Standing still is the whole point of the gate: a slot click sent mid-sprint is a movement
        // violation by itself, and no amount of state juggling makes it not one. Holding the swap
        // until the player settles is slower, but it is true rather than merely undetected.
        if (cfg.autoArmourIdleOnly && isMoving(player)) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now < nextCheckAt) {
            return;
        }

        nextCheckAt = now + Math.max(50, cfg.autoArmourDelayMs);

        PlayerScreenHandler handler = player.playerScreenHandler;

        for (EquipmentSlot slot : ARMOUR_SLOTS) {
            ItemStack worn = player.getEquippedStack(slot);

            if (cfg.autoArmourKeepElytra && slot == EquipmentSlot.CHEST && worn.isOf(Items.ELYTRA)) {
                continue;
            }

            double bestScore = score(worn);
            int bestSlot = -1;

            for (int i = PlayerScreenHandler.INVENTORY_START;
                    i < PlayerScreenHandler.INVENTORY_START + INVENTORY_SIZE; i++) {
                ItemStack candidate = handler.getSlot(i).getStack();

                if (candidate.isEmpty() || candidate.isOf(Items.ELYTRA)) {
                    continue;
                }

                if (player.getPreferredEquipmentSlot(candidate) != slot) {
                    continue;
                }

                double candidateScore = score(candidate);

                if (candidateScore > bestScore) {
                    bestScore = candidateScore;
                    bestSlot = i;
                }
            }

            if (bestSlot < 0) {
                continue;
            }

            if (!worn.isEmpty()) {
                int armourIndex = PlayerScreenHandler.EQUIPMENT_START + (3 - slot.getEntitySlotId());
                mc.interactionManager.clickSlot(handler.syncId, armourIndex, 0, SlotActionType.QUICK_MOVE, player);
            }

            mc.interactionManager.clickSlot(handler.syncId, bestSlot, 0, SlotActionType.QUICK_MOVE, player);
            equippedThisTick = true;
            return;
        }
    }

    // ------------------------------------------------------------------ scoring

    /** Sprinting, or drifting faster than a hair over a hundredth of a block per tick. */
    private static boolean isMoving(ClientPlayerEntity player) {
        return player.isSprinting() || player.getVelocity().horizontalLengthSquared() > IDLE_SPEED_SQUARED;
    }

    /**
     * Material first, then enchantment levels, then remaining durability.
     *
     * <p>The material gap is deliberately enormous: users reading "best armour" mean the best
     * <em>material</em>, and no pile of enchantments should make a leather cap beat a bare diamond
     * helmet. Enchantments and wear only decide between pieces of the same material.
     */
    private static double score(ItemStack stack) {
        if (stack.isEmpty()) {
            return -1.0D;
        }

        Integer tier = MATERIAL_TIERS.get(stack.getItem());
        double material = (tier == null ? 0 : tier) * 10000.0D;
        return material + enchantLevels(stack) * 500.0D + durability(stack) * 200.0D;
    }

    private static int enchantLevels(ItemStack stack) {
        ItemEnchantmentsComponent enchantments = stack.getEnchantments();
        int total = 0;

        for (RegistryEntry<Enchantment> entry : enchantments.getEnchantments()) {
            total += enchantments.getLevel(entry);
        }

        return total;
    }

    private static double durability(ItemStack stack) {
        int max = stack.getMaxDamage();

        if (max <= 0) {
            return 1.0D;
        }

        return Math.max(0.0D, (max - stack.getDamage()) / (double) max);
    }

    private static void tier(int level, Item... items) {
        for (Item item : items) {
            MATERIAL_TIERS.put(item, level);
        }
    }

    static {
        tier(6, Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE,
                Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS);
        tier(5, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
                Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
        tier(4, Items.TURTLE_HELMET);
        tier(3, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
        tier(2, Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE,
                Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS);
        tier(1, Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE,
                Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS);
        tier(0, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE,
                Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
    }
}
