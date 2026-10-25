package dev.junxiex.mikufirstspawn.listener;

import dev.junxiex.mikufirstspawn.service.FirstSpawnService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * 世界在插件启用之后才加载，因此出生点状态校验放到 ServerLoadEvent / WorldLoadEvent。
 */
public final class ServerLifecycleListener implements Listener {

    private final FirstSpawnService service;

    public ServerLifecycleListener(FirstSpawnService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        service.handleServerLoad();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        service.handleWorldLoad(event);
    }
}
