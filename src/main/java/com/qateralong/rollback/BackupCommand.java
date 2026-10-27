package com.qateralong.rollback;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.qateralong.rollback.store.Journal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

public final class BackupCommand {
    private static final int PAGE = 15;

    private BackupCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("backup")
                .requires(source -> source.hasPermission(RollbackConfig.get().permissionLevel))
                .executes(BackupCommand::status)
                .then(Commands.literal("c").executes(BackupCommand::cancel))
                .then(Commands.literal("cancel").executes(BackupCommand::cancel))
                .then(Commands.literal("status").executes(BackupCommand::status))
                .then(Commands.literal("reload").executes(context -> {
                    RollbackConfig.load();
                    Rollback.scheduleNext();
                    if (RollbackConfig.brokenConfig != null) {
                        context.getSource().sendFailure(Component.literal("Ошибка в config/rollback.json, работают значения по умолчанию: " + RollbackConfig.brokenConfig));
                        return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.literal("Конфиг rollback перезагружен"), true);
                    return 1;
                }))
                .then(Commands.literal("now").executes(context -> {
                    long id = Recorder.checkpoint(context.getSource().getServer());
                    if (id < 0) {
                        context.getSource().sendFailure(Component.literal("Сейчас точку создать нельзя"));
                        return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.literal("Точка отката создана: " + Times.format(id)), true);
                    return 1;
                }))
                .then(Commands.literal("list")
                        .executes(context -> list(context, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> list(context, IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.argument("time", StringArgumentType.greedyString())
                        .suggests((context, builder) -> {
                            List<Long> ids = Journal.ids(Recorder.world());
                            for (int i = ids.size() - 1; i >= Math.max(0, ids.size() - 12); i--) {
                                builder.suggest(Times.argument(ids.get(i)));
                            }
                            builder.suggest("15m");
                            return builder.buildFuture();
                        })
                        .executes(BackupCommand::rollback)));
    }

    private static int rollback(CommandContext<CommandSourceStack> context) {
        String text = StringArgumentType.getString(context, "time");
        long time;
        try {
            time = Times.parse(text);
        } catch (Exception e) {
            context.getSource().sendFailure(Component.literal("Не понял время «" + text + "». Примеры: 21.10.2026_21:00, 21:00, 15m, 2h"));
            return 0;
        }
        if (time > System.currentTimeMillis() / 1000L) {
            context.getSource().sendFailure(Component.literal("Это время ещё не наступило"));
            return 0;
        }
        long target = Overload.checkpointAtOrBefore(time);
        if (target < 0) {
            List<Long> ids = Journal.ids(Recorder.world());
            context.getSource().sendFailure(Component.literal(ids.isEmpty() ? "Истории пока нет"
                    : "Так далеко назад истории нет — самая ранняя точка " + Times.format(ids.get(0))));
            return 0;
        }
        if (Countdown.active()) {
            context.getSource().sendFailure(Component.literal("Откат уже запланирован — отмена: /backup c"));
            return 0;
        }
        String who = context.getSource().getTextName();
        if (!Countdown.start(context.getSource().getServer(), target, "команда " + who, false, RollbackConfig.get().countdownSeconds)) {
            context.getSource().sendFailure(Component.literal("Откат запрещён другим модом"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Откат до " + Times.format(target) + " через "
                + RollbackConfig.get().countdownSeconds + " с. Отмена: /backup c").withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> context) {
        if (!Countdown.cancel(context.getSource().getServer(), context.getSource().getTextName())) {
            context.getSource().sendFailure(Component.literal("Нечего отменять"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Откат отменён").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        List<Long> ids = Journal.ids(Recorder.world());
        RollbackConfig config = RollbackConfig.get();
        String history = ids.isEmpty() ? "нет" : Times.format(ids.get(0)) + " — " + Times.format(ids.get(ids.size() - 1));
        String pending = Countdown.active() ? "\n§6Запланирован откат до " + Times.format(Countdown.target()) : "";
        context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "§eОткаты: §fточек %d, история %s, %.1f МБ\n§7Точка каждые %d с, хранение %d дн., перегрузка: %s\n§7Сторож: %s%s",
                ids.size(), history, Maintenance.totalSize() / 1048576.0, config.checkpointSeconds, config.retentionDays,
                config.overload.enabled ? "TPS<" + config.overload.tpsBelow + " " + config.overload.lowTpsSeconds + " с или зависание "
                        + config.overload.hangSeconds + " с → откат на " + config.overload.rollbackMinutes + " мин" : "выкл", Overload.describe(), pending)), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context, int page) {
        List<Long> ids = new ArrayList<>(Journal.ids(Recorder.world()));
        if (ids.isEmpty()) {
            context.getSource().sendFailure(Component.literal("Истории пока нет"));
            return 0;
        }
        java.util.Collections.reverse(ids);
        int pages = (ids.size() + PAGE - 1) / PAGE;
        int current = Math.min(page, pages);
        MutableComponent text = Component.literal("Точки отката (" + current + "/" + pages + "), новые сверху:").withStyle(ChatFormatting.YELLOW);
        for (int i = (current - 1) * PAGE; i < Math.min(ids.size(), current * PAGE); i++) {
            long id = ids.get(i);
            String argument = Times.argument(id);
            text.append(Component.literal("\n " + Times.format(id)).withStyle(style -> style.withColor(ChatFormatting.WHITE)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/backup " + argument))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Нажмите, чтобы подставить /backup " + argument)))));
        }
        if (current < pages) {
            int next = current + 1;
            text.append(Component.literal("\n [дальше →]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/backup list " + next))));
        }
        context.getSource().sendSuccess(() -> text, false);
        return 1;
    }
}
