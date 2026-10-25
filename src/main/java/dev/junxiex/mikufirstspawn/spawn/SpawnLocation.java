package dev.junxiex.mikufirstspawn.spawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;

/**
 * 首次出生点值对象。world 名为空表示尚未设置。
 */
public record SpawnLocation(String worldName, double x, double y, double z, float yaw, float pitch, boolean configured) {

    private static final SpawnLocation UNSET =
            new SpawnLocation("", 0.0D, 64.0D, 0.0D, 0.0F, 0.0F, false);

    public static SpawnLocation unset() {
        return UNSET;
    }

    public static SpawnLocation of(String worldName, double x, double y, double z, float yaw, float pitch) {
        String cleaned = worldName == null ? "" : worldName.trim();
        return new SpawnLocation(cleaned, x, y, z, yaw, pitch, !cleaned.isEmpty());
    }

    public boolean hasFiniteNumbers() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Float.isFinite(yaw) && Float.isFinite(pitch);
    }

    /** 已加载的世界；未配置或未加载时返回 null。 */
    public World loadedWorld() {
        return configured ? Bukkit.getWorld(worldName) : null;
    }

    /**
     * 返回出生点不可用的原因；可用时返回 null。
     */
    public String validationError() {
        if (!configured) {
            return "spawn.world 为空（尚未设置出生点）";
        }
        if (!hasFiniteNumbers()) {
            return "坐标包含 NaN/Infinity";
        }
        World world = loadedWorld();
        if (world == null) {
            return "世界 " + worldName + " 未加载";
        }
        if (y < world.getMinHeight() || y > world.getMaxHeight()) {
            return "Y=" + format(y) + " 超出世界 " + worldName + " 的高度范围 ["
                    + world.getMinHeight() + ", " + world.getMaxHeight() + "]";
        }
        return null;
    }

    /**
     * 解析为可传送的 Location；不可用时返回 null。
     */
    public Location resolve() {
        if (!configured || !hasFiniteNumbers()) {
            return null;
        }
        World world = loadedWorld();
        if (world == null || y < world.getMinHeight() || y > world.getMaxHeight()) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }

    public String describe() {
        if (!configured) {
            return "未设置";
        }
        return worldName + " (" + format(x) + ", " + format(y) + ", " + format(z) + ")"
                + " yaw=" + format(yaw) + " pitch=" + format(pitch);
    }

    private static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1.0E9D) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
