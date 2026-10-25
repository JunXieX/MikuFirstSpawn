package dev.junxiex.mikufirstspawn.listener;

import dev.junxiex.mikufirstspawn.service.FirstSpawnService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 兜底路径与首次进服后的附加行为（欢迎消息、重生点设置）。
 */
public final class PlayerJoinListener implements Listener {

    private final FirstSpawnService service;

    public PlayerJoinListener(FirstSpawnService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        service.handleJoin(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.handleQuit(event);
    }
}
