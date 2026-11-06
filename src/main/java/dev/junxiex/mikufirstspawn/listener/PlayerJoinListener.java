package dev.junxiex.mikufirstspawn.listener;

import dev.junxiex.mikufirstspawn.service.FirstSpawnService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerJoinListener implements Listener {

    private final FirstSpawnService service;

    public PlayerJoinListener(FirstSpawnService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        service.handleJoin(event);
    }
}
