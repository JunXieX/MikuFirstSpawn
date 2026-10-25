package dev.junxiex.mikufirstspawn.config;

import dev.junxiex.mikufirstspawn.spawn.SpawnLocation;
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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 配置读取、校验与原子写回。
 *
 * <p>读取/写盘全部在独立单线程执行器上完成，区域线程不接触磁盘；
 * 磁盘上的 config.yml 与内存中的不可变快照通过 volatile 交换。</p>
 */
public final class ConfigManager {

    public static final String DEFAULT_WELCOME = "<green>欢迎来到服务器！</green>";
    private static final int CONFIG_VERSION = 1;
    private static final Set<String> KNOWN_KEYS = Set.of(
            "config-version", "enabled",
            "spawn", "spawn.world", "spawn.x", "spawn.y", "spawn.z", "spawn.yaw", "spawn.pitch",
            "fallback", "fallback.enabled", "fallback.delay-ticks", "fallback.max-attempts", "fallback.retry-delay-ticks",
            "set-respawn-location", "messages", "messages.welcome", "debug");

    private final Plugin plugin;
    private final ExecutorService ioExecutor;
    private final File file;
    private final Object stateLock = new Object();

    private volatile PluginConfig snapshot = PluginConfig.disabled();

    public ConfigManager(Plugin plugin, ExecutorService ioExecutor) {
        this.plugin = plugin;
        this.ioExecutor = ioExecutor;
        this.file = new File(plugin.getDataFolder(), "config.yml");
    }

    public PluginConfig snapshot() {
        return snapshot;
    }

    /**
     * 插件启用时的同步加载。此时服务端尚未加载世界，因此这里只解析配置，不校验世界存在性。
     */
    public void loadInitial() {
        try {
            snapshot = parse(readFromDisk());
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "读取 config.yml 失败，自动传送功能已禁用；修复后执行 /firstspawn reload", exception);
            snapshot = PluginConfig.disabled();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "解析 config.yml 失败，自动传送功能已禁用；修复后执行 /firstspawn reload", exception);
            snapshot = PluginConfig.disabled();
        }
    }

    /**
     * 异步重载。回调在 IO 线程执行。
     */
    public void reloadAsync(Consumer<ReloadResult> callback) {
        PluginConfig before = snapshot;
        try {
            ioExecutor.execute(() -> {
                ReloadResult result;
                try {
                    PluginConfig parsed = parse(readFromDisk());
                    PluginConfig applied;
                    synchronized (stateLock) {
                        // 期间如果管理员又执行了 /firstspawn set，则以新设置为准。
                        if (snapshot == before) {
                            snapshot = parsed;
                        }
                        applied = snapshot;
                    }
                    result = ReloadResult.ok(applied);
                } catch (IOException | InvalidConfigurationException exception) {
                    result = ReloadResult.failure(exception);
                } catch (RuntimeException exception) {
                    result = ReloadResult.failure(exception);
                }
                callback.accept(result);
            });
        } catch (RuntimeException exception) {
            callback.accept(ReloadResult.failure(exception));
        }
    }

    /**
     * 更新出生点：内存立即生效，磁盘异步原子写入。
     */
    public void updateSpawn(SpawnLocation spawn) {
        PluginConfig updated;
        synchronized (stateLock) {
            PluginConfig previous = snapshot;
            updated = new PluginConfig(previous.enabled(), spawn, previous.fallback(),
                    previous.setRespawnLocation(), previous.welcomeMessage(), previous.debug());
            snapshot = updated;
        }
        try {
            ioExecutor.execute(() -> write(updated));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "提交配置写盘任务失败", exception);
        }
    }

    private YamlConfiguration readFromDisk() throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(this.file);
        return yaml;
    }

    private PluginConfig parse(ConfigurationSection yaml) {
        int configVersion = yaml.getInt("config-version", CONFIG_VERSION);
        if (configVersion > CONFIG_VERSION) {
            plugin.getLogger().warning("config.yml 的 config-version=" + configVersion
                    + " 高于插件支持的 " + CONFIG_VERSION + "，将按当前版本解析，未知项会被忽略");
        }

        String world = yaml.getString("spawn.world", "");
        double x = yaml.getDouble("spawn.x", 0.0D);
        double y = yaml.getDouble("spawn.y", 64.0D);
        double z = yaml.getDouble("spawn.z", 0.0D);
        float yaw = (float) yaml.getDouble("spawn.yaw", 0.0D);
        float pitch = clampPitch((float) yaml.getDouble("spawn.pitch", 0.0D));

        boolean fallbackEnabled = yaml.getBoolean("fallback.enabled", true);
        long delayTicks = clamp(yaml.getLong("fallback.delay-ticks", 1L), 0L, 200L);
        int maxAttempts = (int) clamp(yaml.getLong("fallback.max-attempts", 3L), 1L, 20L);
        long retryDelayTicks = clamp(yaml.getLong("fallback.retry-delay-ticks", 20L), 1L, 200L);

        String welcome = yaml.getString("messages.welcome", DEFAULT_WELCOME);
        if (welcome != null) {
            welcome = welcome.trim();
        }
        if (welcome != null && !welcome.isEmpty()) {
            try {
                MiniMessage.miniMessage().deserialize(welcome);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "messages.welcome 不是合法的 MiniMessage 文本，已关闭欢迎消息", exception);
                welcome = "";
            }
        }

        return new PluginConfig(
                yaml.getBoolean("enabled", true),
                SpawnLocation.of(world, x, y, z, yaw, pitch),
                new PluginConfig.Fallback(fallbackEnabled, delayTicks, maxAttempts, retryDelayTicks),
                yaml.getBoolean("set-respawn-location", true),
                welcome == null ? "" : welcome,
                yaml.getBoolean("debug", false));
    }

    private void write(PluginConfig config) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().setHeader(List.of(
                    "============================================================",
                    "MikuFirstSpawn 配置文件",
                    "作者：JunXieX  |  MikuMC系列插件交流群：1105054380",
                    "============================================================"));

            // 先合并旧文件里的自定义键，再写已知键：即使自定义键与已知键路径冲突，也以插件写入为准。
            int existingVersion = mergeUnknownKeys(yaml);
            yaml.set("config-version", Math.max(CONFIG_VERSION, existingVersion));
            yaml.set("enabled", config.enabled());
            yaml.setComments("enabled", List.of("是否启用自动传送。关闭后不会传送任何玩家，但命令仍可使用。"));

            SpawnLocation spawn = config.spawn();
            yaml.set("spawn.world", spawn.worldName());
            yaml.set("spawn.x", spawn.x());
            yaml.set("spawn.y", spawn.y());
            yaml.set("spawn.z", spawn.z());
            yaml.set("spawn.yaw", spawn.yaw());
            yaml.set("spawn.pitch", spawn.pitch());
            yaml.setComments("spawn", List.of(
                    "首次进服出生点。world 留空表示尚未设置 —— 此时不会传送任何玩家，请用 /firstspawn set 设置。"));
            yaml.setComments("spawn.world", List.of("世界名称（必须由服务器加载）"));

            PluginConfig.Fallback fallback = config.fallback();
            yaml.set("fallback.enabled", fallback.enabled());
            yaml.set("fallback.delay-ticks", fallback.delayTicks());
            yaml.set("fallback.max-attempts", fallback.maxAttempts());
            yaml.set("fallback.retry-delay-ticks", fallback.retryDelayTicks());
            yaml.setComments("fallback", List.of(
                    "兜底传送：仅当玩家在登录阶段没能直接落到出生点时才执行（例如出生点世界当时未加载）。",
                    "装有登录/传送类插件时可适当调大 delay-ticks，避免与它们争抢玩家位置。"));

            yaml.set("set-respawn-location", config.setRespawnLocation());
            yaml.setComments("set-respawn-location", List.of(
                    "首次出生点是否同时设置为该玩家的重生点（未设置床时死亡会回到这里）。"));

            yaml.set("messages.welcome", config.welcomeMessage());
            yaml.setComments("messages", List.of("提示消息。"));
            yaml.setComments("messages.welcome", List.of(
                    "首次进服欢迎消息，MiniMessage 格式；{player} 会替换为玩家名；留空则不发送。"));

            yaml.set("debug", config.debug());
            yaml.setComments("debug", List.of("调试日志（排查问题时开启）。"));

            writeAtomically(yaml.saveToString());
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "写入 config.yml 失败", exception);
        }
    }

    /**
     * 保留管理员在 config.yml 里自加的其他键，避免 /firstspawn set 写回时丢失。
     */
    private int mergeUnknownKeys(YamlConfiguration target) {
        try {
            if (!this.file.isFile()) {
                return CONFIG_VERSION;
            }
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(this.file);
            int version = existing.getInt("config-version", CONFIG_VERSION);
            for (String key : existing.getKeys(true)) {
                if (KNOWN_KEYS.contains(key) || existing.isConfigurationSection(key)) {
                    continue;
                }
                Object value = existing.get(key);
                if (value != null) {
                    target.set(key, value);
                }
            }
            return version;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "读取旧配置以保留自定义键时失败（已忽略）", exception);
            return CONFIG_VERSION;
        }
    }

    private void writeAtomically(String content) throws IOException {
        Path target = this.file.toPath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
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

    private static float clampPitch(float value) {
        if (!Float.isFinite(value)) {
            return 0.0F;
        }
        return Math.max(-90.0F, Math.min(90.0F, value));
    }

    /**
     * 重载结果。error 为 null 表示成功。
     */
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
