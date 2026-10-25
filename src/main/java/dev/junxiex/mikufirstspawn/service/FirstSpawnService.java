package dev.junxiex.mikufirstspawn.service;

import dev.junxiex.mikufirstspawn.config.ConfigManager;
import dev.junxiex.mikufirstspawn.config.PluginConfig;
import dev.junxiex.mikufirstspawn.spawn.SpawnLocation;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 首次进服出生点的核心逻辑。
 *
 * <p>主路径：在 {@link AsyncPlayerSpawnLocationEvent} 中直接设置登录落点，玩家不会先出现在默认出生点；
 * 兜底路径：若登录阶段没有成功应用，则在进服后通过 {@code teleportAsync} 补一次传送。
 * 两条路径都不会向服务端抛出异常，配置错误最多导致“不传送”，不会阻断进服。</p>
 */
public final class FirstSpawnService {

    private static final long WARN_INTERVAL_MILLIS = 60_000L;
    private static final long MARKER_TTL_MILLIS = 300_000L;
    private static final int MARKER_PURGE_THRESHOLD = 256;
    private static final int WARN_CLEANUP_THRESHOLD = 256;

    private final JavaPlugin plugin;
    private final ConfigManager configManager;

    /** 登录阶段已经成功设置过落点的玩家；用于进服时跳过兜底传送，避免与其他传送插件争抢。 */
    private final ConcurrentHashMap<UUID, Long> loginSpawnApplied = new ConcurrentHashMap<>();
    /** 出生点不可用告警的节流表，避免刷屏。 */
    private final ConcurrentHashMap<String, Long> spawnWarnings = new ConcurrentHashMap<>();

    public FirstSpawnService(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    /**
     * 主路径：玩家配置阶段的登录落点。
     */
    public void handleSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        try {
            if (!event.isNewPlayer()) {
                return;
            }
            PluginConfig config = configManager.snapshot();
            if (!config.enabled()) {
                return;
            }
            SpawnLocation spawn = config.spawn();
            Location target = spawn.resolve();
            if (target == null) {
                warnSpawnProblem(spawn);
                return;
            }

            event.setSpawnLocation(target);

            UUID playerId = null;
            String playerName = "未知";
            try {
                var connection = event.getConnection();
                if (connection != null) {
                    var profile = connection.getProfile();
                    if (profile != null) {
                        playerId = profile.getId();
                        if (profile.getName() != null) {
                            playerName = profile.getName();
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // 读取资料失败不影响设置落点，只是少了标记与日志信息。
            }

            if (playerId != null) {
                markLoginApplied(playerId);
            }
            if (config.debug()) {
                plugin.getLogger().info("[debug] 登录阶段已应用首次出生点：" + playerName + " -> " + spawn.describe());
            }
        } catch (Throwable throwable) {
            // 这里绝不能把异常抛回配置阶段，否则会阻断玩家登录。
            plugin.getLogger().log(Level.WARNING,
                    "处理登录出生点事件时发生异常（已忽略，玩家使用默认出生点）", throwable);
        }
    }

    /**
     * 兜底路径与首次进服后的附加行为。
     */
    public void handleJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        try {
            UUID playerId = player.getUniqueId();
            boolean appliedAtLogin = loginSpawnApplied.remove(playerId) != null;

            if (player.hasPlayedBefore()) {
                return;
            }

            PluginConfig config = configManager.snapshot();
            if (!config.enabled()) {
                return;
            }

            SpawnLocation spawn = config.spawn();
            if (!spawn.configured()) {
                warnSpawnProblem(spawn);
                return;
            }

            Location target = spawn.resolve();
            boolean alreadyAtTarget = target != null && isAtTarget(player, target);
            if (alreadyAtTarget) {
                // 登录阶段其实已经生效（例如当时因资料读取失败没写入内存标记），不必再传送一次。
                appliedAtLogin = true;
            }

            boolean fallbackScheduled = false;
            if (!appliedAtLogin && config.fallback().enabled()) {
                // 即使这一刻出生点世界还没加载，也进入兜底流程：每次尝试都会重新解析；
                // 在世界加载完成后的重试窗口内可以自动补上。
                scheduleFallback(player, config, 1);
                fallbackScheduled = true;
            }

            sendWelcome(player, config);

            if (target == null) {
                warnSpawnProblem(spawn);
            } else if (config.setRespawnLocation() && (!fallbackScheduled || appliedAtLogin)) {
                // 兜底还没完成时，等传送成功后再设置重生点，避免重复触发 PlayerSetSpawnEvent。
                applyRespawnLocation(player, target, config);
            }

            if (config.debug()) {
                String reason;
                if (alreadyAtTarget) {
                    reason = "：已在目标出生点";
                } else if (appliedAtLogin) {
                    reason = "：登录阶段已应用出生点";
                } else if (fallbackScheduled) {
                    reason = "：进入兜底传送流程";
                } else {
                    reason = "：未执行传送（兜底关闭）";
                }
                plugin.getLogger().info("[debug] 首次进服玩家 " + player.getName() + reason);
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING,
                    "处理首次进服逻辑时发生异常（已忽略，不影响玩家进服）", throwable);
        }
    }

    public void handleQuit(PlayerQuitEvent event) {
        loginSpawnApplied.remove(event.getPlayer().getUniqueId());
    }

    /**
     * 服务端加载完成后校验一次出生点状态（此时世界已经加载）。
     */
    public void handleServerLoad() {
        try {
            PluginConfig config = configManager.snapshot();
            if (!config.enabled()) {
                plugin.getLogger().info("自动传送已在 config.yml 中关闭。");
                return;
            }

            SpawnLocation spawn = config.spawn();
            if (!spawn.configured()) {
                plugin.getLogger().warning("尚未设置首次出生点：请先在 config.yml 中填写 spawn，"
                        + "或执行 /firstspawn set <world> <x> <y> <z>；在设置之前不会传送任何玩家。");
                return;
            }

            String problem = spawn.validationError();
            if (problem != null) {
                plugin.getLogger().warning("首次出生点当前不可用：" + problem
                        + "（玩家将使用默认出生点，可用 /firstspawn set 修正）");
            } else {
                plugin.getLogger().info("首次出生点已就绪：" + spawn.describe());
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "校验首次出生点时发生异常", throwable);
        }
    }

    /**
     * 出生点世界延迟加载时（如 Multiverse 场景），在世界加载后给出提示。
     */
    public void handleWorldLoad(WorldLoadEvent event) {
        try {
            PluginConfig config = configManager.snapshot();
            if (!config.enabled()) {
                return;
            }
            SpawnLocation spawn = config.spawn();
            if (spawn.configured() && spawn.worldName().equals(event.getWorld().getName())) {
                plugin.getLogger().info("出生点所在世界已加载：" + spawn.describe());
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, "处理世界加载事件时发生异常", throwable);
        }
    }

    private void sendWelcome(Player player, PluginConfig config) {
        String raw = config.welcomeMessage();
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            Component message = MiniMessage.miniMessage()
                    .deserialize(raw.replace("{player}", player.getName()));
            player.sendMessage(message);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "发送首次进服欢迎消息失败（玩家：" + player.getName() + "）", exception);
        }
    }

    private void applyRespawnLocation(Player player, Location target, PluginConfig config) {
        // 实体状态修改必须在该实体所属区域线程执行。
        // override=true：普通坐标才会被当作有效重生点；false 时非床/重生锚坐标会在重生校验中被忽略。
        // 该调用只记录重生配置并触发 PlayerSetSpawnEvent，不会在设置时同步加载区块，Folia 下安全。
        player.getScheduler().execute(plugin, () -> {
            try {
                player.setRespawnLocation(target, true);
                if (config.debug()) {
                    plugin.getLogger().info("[debug] 已设置首次进服重生点：" + player.getName());
                }
            } catch (Throwable throwable) {
                plugin.getLogger().log(Level.WARNING,
                        "设置首次进服重生点时发生异常（玩家：" + player.getName() + "）", throwable);
            }
        }, null, 0L);
    }

    private void scheduleFallback(Player player, PluginConfig config, int attempt) {
        long delay = attempt == 1 ? config.fallback().delayTicks() : config.fallback().retryDelayTicks();
        player.getScheduler().runDelayed(plugin,
                task -> attemptFallback(player, config, attempt), null, delay);
    }

    private void attemptFallback(Player player, PluginConfig config, int attempt) {
        if (!player.isOnline()) {
            return;
        }
        SpawnLocation spawn = config.spawn();
        Location target = spawn.resolve();
        if (target == null) {
            // 出生点世界可能还没加载：按重试次数继续等待，而不是直接放弃。
            warnSpawnProblem(spawn);
            retryFallback(player, config, attempt);
            return;
        }

        // teleportAsync 会先异步加载目标区块，再执行传送；Paper 与 Folia 都支持，且线程安全。
        player.teleportAsync(target).whenComplete((success, throwable) -> {
            try {
                if (throwable != null) {
                    plugin.getLogger().log(Level.WARNING,
                            "首次出生点兜底传送异常（玩家：" + player.getName() + "，第 " + attempt + " 次）",
                            throwable);
                    retryFallback(player, config, attempt);
                    return;
                }
                if (Boolean.TRUE.equals(success)) {
                    if (config.debug()) {
                        plugin.getLogger().info("[debug] 兜底传送成功："
                                + player.getName() + " -> " + spawn.describe());
                    }
                    if (config.setRespawnLocation()) {
                        applyRespawnLocation(player, target, config);
                    }
                    return;
                }
                if (config.debug()) {
                    plugin.getLogger().info("[debug] 兜底传送未成功（玩家：" + player.getName()
                            + "，第 " + attempt + " 次）");
                }
                retryFallback(player, config, attempt);
            } catch (Throwable unexpected) {
                plugin.getLogger().log(Level.WARNING,
                        "处理兜底传送结果时发生异常（玩家：" + player.getName() + "）", unexpected);
            }
        });
    }

    private void retryFallback(Player player, PluginConfig config, int attempt) {
        if (attempt >= config.fallback().maxAttempts()) {
            SpawnLocation spawn = config.spawn();
            String problem = spawn.validationError();
            plugin.getLogger().warning("玩家 " + player.getName()
                    + " 的首次出生点兜底传送失败，已放弃（目标：" + spawn.describe()
                    + (problem == null ? "" : "，原因：" + problem)
                    + "）；请检查目标位置或相关传送插件。");
            return;
        }
        int nextAttempt = attempt + 1;
        player.getScheduler().runDelayed(plugin,
                task -> attemptFallback(player, config, nextAttempt), null,
                config.fallback().retryDelayTicks());
    }

    private static boolean isAtTarget(Player player, Location target) {
        Location current = player.getLocation();
        return current.getWorld() != null && current.getWorld() == target.getWorld()
                && current.getBlockX() == target.getBlockX()
                && current.getBlockY() == target.getBlockY()
                && current.getBlockZ() == target.getBlockZ();
    }

    private void markLoginApplied(UUID playerId) {
        long now = System.currentTimeMillis();
        loginSpawnApplied.put(playerId, now);
        if (loginSpawnApplied.size() > MARKER_PURGE_THRESHOLD) {
            loginSpawnApplied.entrySet().removeIf(entry -> now - entry.getValue() > MARKER_TTL_MILLIS);
        }
    }

    private void warnSpawnProblem(SpawnLocation spawn) {
        String problem = spawn.validationError();
        if (problem == null) {
            return;
        }
        String key = spawn.worldName().isEmpty() ? "<unset>" : spawn.worldName();
        long now = System.currentTimeMillis();
        Long previous = spawnWarnings.get(key);
        if (previous != null && now - previous < WARN_INTERVAL_MILLIS) {
            return;
        }
        spawnWarnings.put(key, now);
        if (spawnWarnings.size() > WARN_CLEANUP_THRESHOLD) {
            spawnWarnings.entrySet().removeIf(entry -> now - entry.getValue() >= WARN_INTERVAL_MILLIS);
        }
        plugin.getLogger().warning("首次出生点不可用：" + problem
                + "；玩家将使用默认出生点，请用 /firstspawn set 修正。");
    }
}
