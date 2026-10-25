package dev.junxiex.mikufirstspawn.listener;

import dev.junxiex.mikufirstspawn.service.FirstSpawnService;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 主路径：在玩家配置阶段直接决定其登录落点，玩家不会先出现在默认出生点。
 */
public final class SpawnLocationListener implements Listener {

    private final FirstSpawnService service;

    public SpawnLocationListener(FirstSpawnService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        service.handleSpawnLocation(event);
    }
}
