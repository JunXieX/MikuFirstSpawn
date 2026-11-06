package dev.junxiex.mikufirstspawn.config;

import dev.junxiex.mikufirstspawn.spawn.SpawnLocation;
import net.kyori.adventure.text.Component;

/**
 * 不可变配置快照。加载/重载时整体替换，运行期只读。
 */
public record PluginConfig(
        boolean enabled,
        SpawnLocation spawn,
        Fallback fallback,
        boolean setRespawnLocation,
        String welcomeText,
        Component welcome,
        boolean debug
) {

    public record Fallback(boolean enabled, long delayTicks, int maxAttempts, long retryDelayTicks) {
    }

    public static PluginConfig disabled() {
        return new PluginConfig(false, SpawnLocation.unset(),
                new Fallback(false, 1L, 1, 20L), false, "", null, false);
    }
}
