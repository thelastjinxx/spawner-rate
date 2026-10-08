package com.thelastjinxx.spawnerrate;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

/**
 * You can't type chat with a chest open, so the commands "arm" the mod
 * and the mod reads the chest the next time you open one.
 *
 * /spawner <count> [price] [rate]   -> theoretical rates
 * /spawner measure start <spawners> -> then open your output chest
 * /spawner measure stop [price]     -> later, then open the same chest
 * /spawner measure cancel
 */
public class SpawnerRateCommand {
    private static final double DEFAULT_RATE = 1.75;
    private static final Item ITEM = Items.BONE;
    private static final int READ_DELAY_TICKS = 10; // let the chest contents sync

    private enum State { IDLE, ARM_START, MEASURING, ARM_STOP }

    private static State state = State.IDLE;
    private static int spawners;
    private static double stopPrice;
    private static long startTime;
    private static int startCount;
    private static int waitTicks;

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
                            .executes(ctx -> arm(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "spawners")))))
                    .then(ClientCommandManager.literal("stop")
                        .executes(ctx -> armStop(ctx.getSource(), 0))
                        .then(ClientCommandManager.argument("price", DoubleArgumentType.doubleArg(0))
                            .executes(ctx -> armStop(ctx.getSource(),
                                DoubleArgumentType.getDouble(ctx, "price")))))
                    .then(ClientCommandManager.literal("cancel")
                        .executes(ctx -> {
                            state = State.IDLE;
                            msg(ctx.getSource(), "§7Measurement cancelled.");
                            return 1;
                        }))));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
    }

    private static int arm(FabricClientCommandSource src, int count) {
        spawners = count;
        state = State.ARM_START;
        waitTicks = 0;
        msg(src, "§aReady. §fNow open your output chest §7and I'll record the bones.");
        return 1;
    }

    private static int armStop(FabricClientCommandSource src, double price) {
        if (state != State.MEASURING && state != State.ARM_STOP) {
            msg(src, "§cNo measurement running. Use §f/spawner measure start <spawners>");
            return 0;
        }
        stopPrice = price;
        state = State.ARM_STOP;
        waitTicks = 0;
        msg(src, "§aReady. §fNow open the same chest §7to get the result.");
        return 1;
    }

    private static void tick(MinecraftClient mc) {
        if (mc.player == null) return;
        if (state != State.ARM_START && state != State.ARM_STOP) return;

        if (!(mc.currentScreen instanceof HandledScreen<?>)
            || mc.player.currentScreenHandler == mc.player.playerScreenHandler) {
            waitTicks = 0;
            return;
        }
        if (++waitTicks < READ_DELAY_TICKS) return;
        waitTicks = 0;

        int c = countOpenContainer(mc);
        if (state == State.ARM_START) {
            startTime = System.currentTimeMillis();
            startCount = c;
            state = State.MEASURING;
            say(mc, String.format("§aMeasuring started: §f%d bones in chest, %d spawners.", c, spawners));
            say(mc, "§7Leave the bones in there. Later run §f/spawner measure stop §7and open the chest.");
        } else {
            finish(mc, c);
        }
    }

    private static void finish(MinecraftClient mc, int c) {
        double minutes = (System.currentTimeMillis() - startTime) / 60000.0;
        int gained = c - startCount;
        if (gained < 0) {
            say(mc, "§cBone count went down (" + gained + "). Did you take bones out? Start again.");
            state = State.IDLE;
            return;
        }
        if (minutes < 1) {
            say(mc, "§eOnly " + String.format("%.1f", minutes) + " min passed. Wait longer, then run stop again.");
            state = State.MEASURING;
            return;
        }
        double perMin = gained / minutes;
        double perSpawner = perMin / spawners;

        say(mc, String.format("§aThe rate is §f%.2f bones/min §aper spawner", perSpawner));
        say(mc, String.format("§7%d spawners: %.1f/min, %.0f/hr, %.0f/day (measured over %.1f min)",
            spawners, perMin, perMin * 60, perMin * 1440, minutes));
        if (stopPrice > 0) {
            say(mc, String.format("§6Money: §f%,.0f/hr  %,.0f/day", perMin * 60 * stopPrice, perMin * 1440 * stopPrice));
        }
        if (gained > 0 && c >= 27 * 64) {
            say(mc, "§cChest may have been full, real rate could be higher.");
        }
        state = State.IDLE;
    }

    private static int countOpenContainer(MinecraftClient mc) {
        ScreenHandler h = mc.player.currentScreenHandler;
        int total = 0;
        for (Slot slot : h.slots) {
            if (slot.inventory == mc.player.getInventory()) continue;
            ItemStack s = slot.getStack();
            if (s.isOf(ITEM)) total += s.getCount();
        }
        return total;
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

    private static void say(MinecraftClient mc, String s) {
        mc.player.sendMessage(Text.literal(s), false);
    }
}
