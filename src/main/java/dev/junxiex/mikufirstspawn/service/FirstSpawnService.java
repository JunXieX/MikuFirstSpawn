package dev.junxiex.mikufirstspawn.service;

import dev.junxiex.mikufirstspawn.config.ConfigManager;
import dev.junxiex.mikufirstspawn.config.PluginConfig;
import dev.junxiex.mikufirstspawn.spawn.SpawnLocation;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * 首次进服出生点。
 *
 * <p>主路径在登录配置阶段直接设置落点；未成功时在进服后异步补传一次。事件处理器只记录异常，不向服务端抛出。</p>
 */
public final class FirstSpawnService {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;

    public FirstSpawnService(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

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
                return;
            }
            event.setSpawnLocation(target);
            if (config.debug()) {
                plugin.getLogger().info("[debug] 登录阶段已应用首次出生点：" + spawn.describe());
            }
        } catch (RuntimeException exception) {
            // 配置阶段抛出异常会阻断登录，这里必须兜住。
            plugin.getLogger().log(Level.WARNING,
                    "处理登录出生点事件时发生异常（已忽略，玩家使用默认出生点）", exception);
        }
    }

    public void handleJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        try {
            if (player.hasPlayedBefore()) {
                return;
            }
            PluginConfig config = configManager.snapshot();
            if (!config.enabled()) {
                return;
            }
            SpawnLocation spawn = config.spawn();
            if (!spawn.isSet()) {
                return;
            }

            Location target = spawn.resolve();
            boolean atTarget = target != null && isAtTarget(player, target);
            boolean fallback = !atTarget && config.fallback().enabled();
            if (fallback) {
                player.getScheduler().runDelayed(plugin,
                        task -> attemptFallback(player, config, 1), null,
                        config.fallback().delayTicks());
            }

            sendWelcome(player, config);

            if (target != null && config.setRespawnLocation() && (!fallback || atTarget)) {
                // 兜底占用中时等传送成功后再设置重生点，避免重复触发 PlayerSetSpawnEvent。
                applyRespawnLocation(player, target);
            }

            if (config.debug()) {
                String reason = atTarget ? "已在目标出生点" : fallback ? "进入兜底传送流程" : "未执行传送（兜底关闭）";
                plugin.getLogger().info("[debug] 首次进服玩家 " + player.getName() + "：" + reason);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "处理首次进服逻辑时发生异常（已忽略，不影响玩家进服）", exception);
        }
    }

    public void handleServerLoad() {
        PluginConfig config = configManager.snapshot();
        if (!config.enabled()) {
            plugin.getLogger().info("自动传送当前不可用（enabled=false 或配置加载失败）。");
            return;
        }
        SpawnLocation spawn = config.spawn();
        if (!spawn.isSet()) {
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
    }

    public void handleWorldLoad(WorldLoadEvent event) {
        PluginConfig config = configManager.snapshot();
        SpawnLocation spawn = config.spawn();
        if (config.enabled() && spawn.isSet() && spawn.worldName().equals(event.getWorld().getName())) {
            plugin.getLogger().info("出生点所在世界已加载：" + spawn.describe());
        }
    }

    private void sendWelcome(Player player, PluginConfig config) {
        Component welcome = config.welcome();
        if (welcome == null) {
            return;
        }
        player.sendMessage(welcome.replaceText(builder ->
                builder.matchLiteral("{player}").replacement(player.getName())));
    }

    private void applyRespawnLocation(Player player, Location target) {
        // 实体状态修改必须在该实体所属区域线程执行；override=true 才能让普通坐标在死亡重生时生效。
        player.getScheduler().execute(plugin, () -> player.setRespawnLocation(target, true), null, 0L);
    }

    private void attemptFallback(Player player, PluginConfig config, int attempt) {
        if (!player.isOnline()) {
            return;
        }
        SpawnLocation spawn = config.spawn();
        Location target = spawn.resolve();
        if (target == null) {
            retryFallback(player, config, attempt);
            return;
        }

        player.teleportAsync(target).whenComplete((success, error) -> {
            try {
                if (error == null && Boolean.TRUE.equals(success)) {
                    if (config.setRespawnLocation()) {
                        applyRespawnLocation(player, target);
                    }
                    if (config.debug()) {
                        plugin.getLogger().info("[debug] 兜底传送成功："
                                + player.getName() + " -> " + spawn.describe());
                    }
                    return;
                }
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING,
                            "首次出生点兜底传送异常（玩家：" + player.getName() + "，第 " + attempt + " 次）",
                            error);
                }
                retryFallback(player, config, attempt);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.WARNING,
                        "处理兜底传送结果时发生异常（玩家：" + player.getName() + "）", exception);
            }
        });
    }

    private void retryFallback(Player player, PluginConfig config, int attempt) {
        if (attempt >= config.fallback().maxAttempts()) {
            plugin.getLogger().warning("玩家 " + player.getName()
                    + " 的首次出生点兜底传送失败，已放弃（目标：" + config.spawn().describe()
                    + "）；请检查目标位置或相关传送插件。");
            return;
        }
        player.getScheduler().runDelayed(plugin,
                task -> attemptFallback(player, config, attempt + 1), null,
                config.fallback().retryDelayTicks());
    }

    private static boolean isAtTarget(Player player, Location target) {
        Location current = player.getLocation();
        return current.getWorld() != null && current.getWorld() == target.getWorld()
                && current.getBlockX() == target.getBlockX()
                && current.getBlockY() == target.getBlockY()
                && current.getBlockZ() == target.getBlockZ();
    }
}
