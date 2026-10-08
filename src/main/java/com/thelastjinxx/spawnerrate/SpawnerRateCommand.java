package com.thelastjinxx.spawnerrate;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

/**
 * /spawner <count> [price] [rate]   -> theoretical rates
 * /spawner measure start <spawners> -> (chest open) records bones + time
 * /spawner measure stop [price]     -> (same chest open) tells you the real rate
 * /spawner measure cancel
 */
public class SpawnerRateCommand {
    private static final double DEFAULT_RATE = 1.75;
    private static final Item ITEM = Items.BONE;

    private static boolean measuring = false;
    private static long startTime;
    private static int startCount;
    private static int spawners;

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("spawner")
                .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1))
                    .executes(ctx -> calc(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "count"), 0, DEFAULT_RATE))
                    .then(ClientCommandManager.argument("price", DoubleArgumentType.doubleArg(0))
                        .executes(ctx -> calc(ctx.getSource(),
                            IntegerArgumentType.getInteger(ctx, "count"),
                            DoubleArgumentType.getDouble(ctx, "price"), DEFAULT_RATE))
                        .then(ClientCommandManager.argument("rate", DoubleArgumentType.doubleArg(0))
                            .executes(ctx -> calc(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "count"),
                                DoubleArgumentType.getDouble(ctx, "price"),
                                DoubleArgumentType.getDouble(ctx, "rate"))))))
                .then(ClientCommandManager.literal("measure")
                    .then(ClientCommandManager.literal("start")
                        .then(ClientCommandManager.argument("spawners", IntegerArgumentType.integer(1))
                            .executes(ctx -> start(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "spawners")))))
                    .then(ClientCommandManager.literal("stop")
                        .executes(ctx -> stop(ctx.getSource(), 0))
                        .then(ClientCommandManager.argument("price", DoubleArgumentType.doubleArg(0))
                            .executes(ctx -> stop(ctx.getSource(),
                                DoubleArgumentType.getDouble(ctx, "price")))))
                    .then(ClientCommandManager.literal("cancel")
                        .executes(ctx -> {
                            measuring = false;
                            msg(ctx.getSource(), "§7Measurement cancelled.");
                            return 1;
                        }))));
        });
    }

    /** Counts ITEM in the open container (chest, barrel, shulker...), -1 if none open. */
    private static int countOpenContainer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return -1;
        ScreenHandler h = mc.player.currentScreenHandler;
        if (h == null || h == mc.player.playerScreenHandler) return -1;
        int total = 0;
        for (Slot slot : h.slots) {
            if (slot.inventory == mc.player.getInventory()) continue;
            ItemStack s = slot.getStack();
            if (s.isOf(ITEM)) total += s.getCount();
        }
        return total;
    }

    private static int start(FabricClientCommandSource src, int count) {
        int c = countOpenContainer();
        if (c < 0) {
            msg(src, "§cOpen your output chest first, then run the command.");
            return 0;
        }
        measuring = true;
        startTime = System.currentTimeMillis();
        startCount = c;
        spawners = count;
        msg(src, String.format("§aMeasuring started: §f%d bones in chest, %d spawners.", c, count));
        msg(src, "§7Leave the bones in the chest, then open it later and run §f/spawner measure stop");
        return 1;
    }

    private static int stop(FabricClientCommandSource src, double price) {
        if (!measuring) {
            msg(src, "§cNo measurement running. Use §f/spawner measure start <spawners>");
            return 0;
        }
        int c = countOpenContainer();
        if (c < 0) {
            msg(src, "§cOpen the same chest first, then run the command.");
            return 0;
        }
        double minutes = (System.currentTimeMillis() - startTime) / 60000.0;
        int gained = c - startCount;
        if (gained < 0) {
            msg(src, "§cBone count went down (" + gained + "). Did you take bones out? Use measure start again.");
            measuring = false;
            return 0;
        }
        if (minutes < 1) {
            msg(src, "§eOnly " + String.format("%.1f", minutes) + " min passed, wait longer for an accurate result.");
            return 0;
        }
        double perMin = gained / minutes;
        double perSpawner = perMin / spawners;

        msg(src, String.format("§aThe rate is §f%.2f bones/min §aper spawner", perSpawner));
        msg(src, String.format("§7%d spawners: %.1f/min, %.0f/hr, %.0f/day (measured over %.1f min)",
            spawners, perMin, perMin * 60, perMin * 1440, minutes));
        if (price > 0) {
            msg(src, String.format("§6Money: §f%,.0f/hr  %,.0f/day", perMin * 60 * price, perMin * 1440 * price));
        }
        if (gained > 0 && c >= 27 * 64) {
            msg(src, "§cChest may have been full, real rate could be higher.");
        }
        measuring = false;
        return 1;
    }

    private static int calc(FabricClientCommandSource src, int count, double price, double rate) {
        double perMin = count * rate;
        msg(src, String.format("§e%d spawners §7@ %.2f/min each", count, rate));
        msg(src, String.format("§aItems: §f%.1f/min  %.0f/hr  %.0f/day", perMin, perMin * 60, perMin * 1440));
        if (price > 0) {
            msg(src, String.format("§6Money: §f%,.0f/hr  %,.0f/day", perMin * 60 * price, perMin * 1440 * price));
        }
        return 1;
    }

    private static void msg(FabricClientCommandSource src, String s) {
        src.sendFeedback(Text.literal(s));
    }
}
