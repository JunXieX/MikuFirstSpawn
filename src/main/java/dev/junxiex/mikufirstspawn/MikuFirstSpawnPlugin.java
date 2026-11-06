package dev.junxiex.mikufirstspawn;

import dev.junxiex.mikufirstspawn.command.FirstSpawnCommand;
import dev.junxiex.mikufirstspawn.config.ConfigManager;
import dev.junxiex.mikufirstspawn.config.PluginConfig;
import dev.junxiex.mikufirstspawn.listener.PlayerJoinListener;
import dev.junxiex.mikufirstspawn.listener.ServerLifecycleListener;
import dev.junxiex.mikufirstspawn.listener.SpawnLocationListener;
import dev.junxiex.mikufirstspawn.service.FirstSpawnService;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * MikuFirstSpawn 入口。
 *
 * <p>onEnable 阶段不访问世界：Bukkit 的世界在插件启用之后才会加载，出生点世界延迟到实际使用时解析。</p>
 */
public final class MikuFirstSpawnPlugin extends JavaPlugin {

    private ExecutorService ioExecutor;
    private boolean permissionRegistered;

    @Override
    public void onEnable() {
        registerPermission();
        saveDefaultConfig();

        ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MikuFirstSpawn-IO");
            thread.setDaemon(true);
            return thread;
        });

        ConfigManager configManager = new ConfigManager(this, ioExecutor);
        configManager.loadInitial();

        FirstSpawnService service = new FirstSpawnService(this, configManager);
        getServer().getPluginManager().registerEvents(new SpawnLocationListener(service), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(service), this);
        getServer().getPluginManager().registerEvents(new ServerLifecycleListener(service), this);
        new FirstSpawnCommand(this, configManager).register();

        PluginConfig config = configManager.snapshot();
        if (config.enabled()) {
            getLogger().info("MikuFirstSpawn 已启用，出生点：" + config.spawn().describe());
        } else {
            getLogger().info("MikuFirstSpawn 已加载，但 config.yml 中 enabled 为 false，自动传送已关闭。");
        }
        if (config.debug()) {
            getLogger().info("[debug] 调试日志已开启");
        }
    }

    @Override
    public void onDisable() {
        if (permissionRegistered) {
            getServer().getPluginManager().removePermission(FirstSpawnCommand.PERMISSION);
        }
        if (ioExecutor == null) {
            return;
        }
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(2L, TimeUnit.SECONDS)) {
                getLogger().log(Level.WARNING, "配置写入线程未能在 2 秒内结束");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void registerPermission() {
        if (getServer().getPluginManager().getPermission(FirstSpawnCommand.PERMISSION) != null) {
            return;
        }
        getServer().getPluginManager().addPermission(new Permission(
                FirstSpawnCommand.PERMISSION, "MikuFirstSpawn 管理权限", PermissionDefault.OP));
        permissionRegistered = true;
    }
}
