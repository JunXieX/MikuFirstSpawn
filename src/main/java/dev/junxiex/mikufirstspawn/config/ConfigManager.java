package dev.junxiex.mikufirstspawn.config;

import dev.junxiex.mikufirstspawn.spawn.SpawnLocation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 配置读取、校验与写回。所有磁盘操作都在单线程执行器上串行执行。
 */
public final class ConfigManager {

    private static final String DEFAULT_WELCOME = "<green>欢迎来到服务器！</green>";

    private final Plugin plugin;
    private final ExecutorService ioExecutor;
    private final File file;

    private volatile PluginConfig snapshot = PluginConfig.disabled();

    public ConfigManager(Plugin plugin, ExecutorService ioExecutor) {
        this.plugin = plugin;
        this.ioExecutor = ioExecutor;
        this.file = new File(plugin.getDataFolder(), "config.yml");
    }

    public PluginConfig snapshot() {
        return snapshot;
    }

    public void loadInitial() {
        try {
            snapshot = parse(readFromDisk());
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "读取 config.yml 失败，自动传送功能已禁用；修复后执行 /firstspawn reload", exception);
            snapshot = PluginConfig.disabled();
        }
    }

    /** 回调在 IO 线程执行。 */
    public void reloadAsync(Consumer<ReloadResult> callback) {
        ioExecutor.execute(() -> {
            ReloadResult result;
            try {
                PluginConfig parsed = parse(readFromDisk());
                snapshot = parsed;
                result = ReloadResult.ok(parsed);
            } catch (Exception exception) {
                result = ReloadResult.failure(exception);
            }
            callback.accept(result);
        });
    }

    public CompletableFuture<Boolean> updateSpawn(SpawnLocation spawn) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        ioExecutor.execute(() -> {
            try {
                YamlConfiguration disk = readFromDisk();
                PluginConfig diskConfig = parse(disk);
                writeSpawn(disk, spawn);
                snapshot = new PluginConfig(diskConfig.enabled(), spawn, diskConfig.fallback(),
                        diskConfig.setRespawnLocation(), diskConfig.welcomeText(), diskConfig.welcome(),
                        diskConfig.debug());
                result.complete(true);
            } catch (Exception exception) {
                plugin.getLogger().log(Level.SEVERE, "保存出生点失败，config.yml 未修改", exception);
                result.complete(false);
            }
        });
        return result;
    }

    private YamlConfiguration readFromDisk() throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(this.file);
        return yaml;
    }

    private PluginConfig parse(ConfigurationSection yaml) {
        String world = yaml.getString("spawn.world", "");
        if (world == null) {
            world = "";
        }
        double x = yaml.getDouble("spawn.x", 0.0D);
        double y = yaml.getDouble("spawn.y", 64.0D);
        double z = yaml.getDouble("spawn.z", 0.0D);
        float yaw = (float) yaml.getDouble("spawn.yaw", 0.0D);
        float pitch = (float) yaml.getDouble("spawn.pitch", 0.0D);
        pitch = Math.max(-90.0F, Math.min(90.0F, pitch));

        SpawnLocation spawn;
        if (SpawnLocation.hasValidCoordinates(x, y, z, yaw, pitch)) {
            spawn = SpawnLocation.of(world, x, y, z, yaw, pitch);
        } else {
            plugin.getLogger().warning("config.yml 的 spawn 坐标非法（NaN/Infinity 或 X/Z 超出 ±30000000），已按未设置处理");
            spawn = SpawnLocation.unset();
        }

        boolean fallbackEnabled = yaml.getBoolean("fallback.enabled", true);
        long delayTicks = clamp(yaml.getLong("fallback.delay-ticks", 1L), 0L, 200L);
        int maxAttempts = (int) clamp(yaml.getLong("fallback.max-attempts", 3L), 1L, 20L);
        long retryDelayTicks = clamp(yaml.getLong("fallback.retry-delay-ticks", 20L), 1L, 200L);

        String welcomeText = yaml.getString("messages.welcome", DEFAULT_WELCOME);
        if (welcomeText == null) {
            welcomeText = "";
        }
        welcomeText = welcomeText.trim();
        Component welcome = null;
        if (!welcomeText.isEmpty()) {
            try {
                welcome = MiniMessage.miniMessage().deserialize(welcomeText);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "messages.welcome 不是合法的 MiniMessage 文本，已关闭欢迎消息", exception);
                welcomeText = "";
            }
        }

        return new PluginConfig(
                yaml.getBoolean("enabled", true),
                spawn,
                new PluginConfig.Fallback(fallbackEnabled, delayTicks, maxAttempts, retryDelayTicks),
                yaml.getBoolean("set-respawn-location", true),
                welcomeText,
                welcome,
                yaml.getBoolean("debug", false));
    }

    private void writeSpawn(YamlConfiguration yaml, SpawnLocation spawn) throws IOException {
        yaml.set("spawn.world", spawn.worldName());
        yaml.set("spawn.x", spawn.x());
        yaml.set("spawn.y", spawn.y());
        yaml.set("spawn.z", spawn.z());
        yaml.set("spawn.yaw", spawn.yaw());
        yaml.set("spawn.pitch", spawn.pitch());
        applyComments(yaml);
        writeAtomically(yaml.saveToString());
    }

    private static void applyComments(YamlConfiguration yaml) {
        yaml.options().setHeader(List.of(
                "============================================================",
                "MikuFirstSpawn 配置文件",
                "作者：JunXieX  |  MikuMC系列插件交流群：1105054380",
                "============================================================"));

        yaml.setComments("enabled", List.of("是否启用自动传送。关闭后不会传送任何玩家，但命令仍可使用。"));
        yaml.setComments("spawn", List.of(
                "首次进服出生点。world 留空表示尚未设置 —— 此时不会传送任何玩家，请用 /firstspawn set 设置。"));
        yaml.setComments("spawn.world", List.of("世界名称（必须由服务器加载）"));
        yaml.setComments("fallback", List.of(
                "兜底传送：仅当玩家在登录阶段没能直接落到出生点时才执行（例如出生点世界当时未加载）。",
                "装有登录/传送类插件时可适当调大 delay-ticks，避免与它们争抢玩家位置。"));
        yaml.setComments("set-respawn-location", List.of(
                "首次出生点是否同时设置为该玩家的重生点（未设置床时死亡会回到这里）。"));
        yaml.setComments("messages", List.of("提示消息。"));
        yaml.setComments("messages.welcome", List.of(
                "首次进服欢迎消息，MiniMessage 格式；{player} 会替换为玩家名；留空则不发送。"));
        yaml.setComments("debug", List.of("调试日志（排查问题时开启）。"));
    }

    private void writeAtomically(String content) throws IOException {
        Path target = this.file.toPath();
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(this.file.getName() + ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record ReloadResult(PluginConfig config, Exception error) {

        public static ReloadResult ok(PluginConfig config) {
            return new ReloadResult(config, null);
        }

        public static ReloadResult failure(Exception error) {
            return new ReloadResult(null, error);
        }

        public boolean success() {
            return this.error == null;
        }
    }
}
