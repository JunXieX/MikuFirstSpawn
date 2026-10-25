package dev.junxiex.mikufirstspawn.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.junxiex.mikufirstspawn.config.ConfigManager;
import dev.junxiex.mikufirstspawn.config.PluginConfig;
import dev.junxiex.mikufirstspawn.spawn.SpawnLocation;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.Collectors;

/**
 * /firstspawn 命令（Brigadier）。
 */
public final class FirstSpawnCommand {

    public static final String PERMISSION = "mikufirstspawn.admin";
    private static final List<String> ALIASES = List.of("fs");

    private final JavaPlugin plugin;
    private final ConfigManager configManager;

    public FirstSpawnCommand(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(build(), "MikuFirstSpawn 管理命令", ALIASES));
    }

    private LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("firstspawn")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(this::help);

        root.then(Commands.literal("help").executes(this::help));
        root.then(Commands.literal("info").executes(this::info));
        root.then(Commands.literal("reload").executes(this::reload));
        root.then(Commands.literal("tp").executes(this::teleportSelf));

        root.then(Commands.literal("set")
                .executes(this::setHere)
                .then(Commands.argument("world", StringArgumentType.string())
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(context -> setExplicit(context, 0.0F, 0.0F))
                                                .then(Commands.argument("yaw", FloatArgumentType.floatArg(-180.0F, 180.0F))
                                                        .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90.0F, 90.0F))
                                                                .executes(context -> setExplicit(context,
                                                                        FloatArgumentType.getFloat(context, "yaw"),
                                                                        FloatArgumentType.getFloat(context, "pitch"))))))))));

        return root.build();
    }

    private int help(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        sender.sendMessage(Component.text("MikuFirstSpawn 命令：", NamedTextColor.AQUA));
        sender.sendMessage(helpLine("/firstspawn set", "把当前位置设为首次出生点"));
        sender.sendMessage(helpLine("/firstspawn set <world> <x> <y> <z> [yaw] [pitch]", "指定坐标设置出生点"));
        sender.sendMessage(helpLine("/firstspawn info", "查看出生点与运行状态"));
        sender.sendMessage(helpLine("/firstspawn reload", "重新读取 config.yml"));
        sender.sendMessage(helpLine("/firstspawn tp", "传送到出生点（自测用）"));
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        PluginConfig config = configManager.snapshot();

        sender.sendMessage(Component.text("MikuFirstSpawn " + plugin.getPluginMeta().getVersion(),
                NamedTextColor.AQUA, TextDecoration.BOLD));
        sender.sendMessage(infoLine("运行平台", platformDescription()));
        sender.sendMessage(infoLine("功能状态", config.enabled() ? "已启用" : "已关闭"));

        SpawnLocation spawn = config.spawn();
        sender.sendMessage(infoLine("出生点", spawn.describe()));
        String problem = spawn.validationError();
        sender.sendMessage(infoLine("出生点状态", problem == null ? "可用" : "不可用：" + problem));

        PluginConfig.Fallback fallback = config.fallback();
        sender.sendMessage(infoLine("兜底传送", fallback.enabled()
                ? "开启（延迟 " + fallback.delayTicks() + " tick，最多 " + fallback.maxAttempts() + " 次）"
                : "关闭"));
        sender.sendMessage(infoLine("设为重生点", config.setRespawnLocation() ? "是" : "否"));
        sender.sendMessage(infoLine("欢迎消息", config.welcomeMessage().isEmpty() ? "关闭" : "开启"));
        sender.sendMessage(infoLine("调试日志", config.debug() ? "开启" : "关闭"));

        if (!spawn.configured()) {
            sender.sendMessage(Component.text("提示：使用 /firstspawn set 设置首次出生点。", NamedTextColor.YELLOW));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        sender.sendMessage(Component.text("正在重新读取 config.yml ...", NamedTextColor.GRAY));
        configManager.reloadAsync(result -> {
            if (!result.success()) {
                sendLater(sender, Component.text("配置重载失败："
                        + result.error().getMessage(), NamedTextColor.RED));
                return;
            }
            PluginConfig config = result.config();
            sendLater(sender, Component.text("配置已重载。", NamedTextColor.GREEN));
            if (!config.enabled()) {
                sendLater(sender, Component.text("注意：enabled 为 false，自动传送仍关闭。", NamedTextColor.YELLOW));
                return;
            }
            String problem = config.spawn().validationError();
            if (problem != null) {
                sendLater(sender, Component.text("出生点尚不可用：" + problem, NamedTextColor.YELLOW));
            } else {
                sendLater(sender, Component.text("出生点已就绪：" + config.spawn().describe(), NamedTextColor.GREEN));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int teleportSelf(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(error("该命令只能由玩家执行"));
            return 0;
        }
        SpawnLocation spawn = configManager.snapshot().spawn();
        Location target = spawn.resolve();
        if (target == null) {
            sender.sendMessage(error("出生点不可用：" + spawn.validationError()));
            return 0;
        }
        player.teleportAsync(target).whenComplete((success, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().warning("传送到出生点时发生异常（玩家：" + player.getName() + "）：" + throwable.getMessage());
                sendLater(player, error("传送失败，详见控制台日志"));
                return;
            }
            if (Boolean.TRUE.equals(success)) {
                sendLater(player, Component.text("已传送到出生点。", NamedTextColor.GREEN));
            } else {
                sendLater(player, error("传送未完成"));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int setHere(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(error("控制台请使用：/firstspawn set <world> <x> <y> <z> [yaw] [pitch]"));
            return 0;
        }
        Location location = player.getLocation();
        SpawnLocation spawn = SpawnLocation.of(location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
        return applySpawn(sender, spawn);
    }

    private int setExplicit(CommandContext<CommandSourceStack> context, float yaw, float pitch) {
        CommandSender sender = context.getSource().getSender();
        String worldName = StringArgumentType.getString(context, "world");
        double x = DoubleArgumentType.getDouble(context, "x");
        double y = DoubleArgumentType.getDouble(context, "y");
        double z = DoubleArgumentType.getDouble(context, "z");

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(error("世界 " + worldName + " 未加载。已加载世界：" + loadedWorldNames()));
            return 0;
        }
        return applySpawn(sender, SpawnLocation.of(worldName, x, y, z, yaw, pitch));
    }

    private int applySpawn(CommandSender sender, SpawnLocation spawn) {
        String problem = spawn.validationError();
        if (problem != null) {
            sender.sendMessage(error("出生点无效：" + problem));
            return 0;
        }
        configManager.updateSpawn(spawn);
        sender.sendMessage(Component.text("首次出生点已设置为：" + spawn.describe(), NamedTextColor.GREEN));
        sender.sendMessage(Component.text("配置已保存，新玩家进服将直接出现在该位置。", NamedTextColor.GRAY));
        plugin.getLogger().info(sender.getName() + " 设置了首次出生点：" + spawn.describe());
        return Command.SINGLE_SUCCESS;
    }

    private void sendLater(CommandSender sender, Component message) {
        if (sender instanceof Player player) {
            player.getScheduler().execute(plugin, () -> sender.sendMessage(message), null, 0L);
        } else {
            sender.sendMessage(message);
        }
    }

    private static String platformDescription() {
        ServerBuildInfo buildInfo = ServerBuildInfo.buildInfo();
        String build = buildInfo.buildNumber().isPresent()
                ? " (build " + buildInfo.buildNumber().getAsInt() + ")"
                : "";
        return buildInfo.brandName() + " " + buildInfo.minecraftVersionName() + build;
    }

    private static String loadedWorldNames() {
        return Bukkit.getWorlds().stream()
                .map(World::getName)
                .sorted()
                .collect(Collectors.joining(", "));
    }

    private static Component infoLine(String label, String value) {
        return Component.text("▪ ", NamedTextColor.DARK_AQUA)
                .append(Component.text(label + "：", NamedTextColor.AQUA))
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    private static Component helpLine(String usage, String description) {
        return Component.text("  " + usage, NamedTextColor.YELLOW)
                .append(Component.text(" - " + description, NamedTextColor.GRAY));
    }

    private static Component error(String message) {
        return Component.text("✘ " + message, NamedTextColor.RED);
    }
}
