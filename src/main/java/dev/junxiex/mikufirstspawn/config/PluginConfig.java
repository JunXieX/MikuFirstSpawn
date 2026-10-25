package dev.junxiex.mikufirstspawn.config;

import dev.junxiex.mikufirstspawn.spawn.SpawnLocation;

/**
 * 不可变配置快照。加载/重载时整体替换，运行期只读，避免跨线程可见性问题。
 */
public record PluginConfig(
        boolean enabled,
        SpawnLocation spawn,
        Fallback fallback,
        boolean setRespawnLocation,
        String welcomeMessage,
        boolean debug
) {

    public record Fallback(boolean enabled, long delayTicks, int maxAttempts, long retryDelayTicks) {
    }

    /** 配置无法读取时使用的安全快照：所有自动行为关闭。 */
    public static PluginConfig disabled() {
        return new PluginConfig(false, SpawnLocation.unset(),
                new Fallback(false, 1L, 1, 20L), false, "", false);
    }
}
