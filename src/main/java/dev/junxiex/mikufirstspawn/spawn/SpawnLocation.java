package dev.junxiex.mikufirstspawn.spawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Locale;

/**
 * 首次出生点。world 为空表示尚未设置。
 */
public record SpawnLocation(String worldName, double x, double y, double z, float yaw, float pitch) {

    public static SpawnLocation unset() {
        return new SpawnLocation("", 0.0D, 64.0D, 0.0D, 0.0F, 0.0F);
    }

    public static SpawnLocation of(String worldName, double x, double y, double z, float yaw, float pitch) {
        return new SpawnLocation(worldName.trim(), x, y, z, yaw, pitch);
    }

    public static boolean hasValidCoordinates(double x, double y, double z, float yaw, float pitch) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Float.isFinite(yaw) && Float.isFinite(pitch)
                && Math.abs(x) <= 3.0E7D && Math.abs(z) <= 3.0E7D;
    }

    public boolean isSet() {
        return !worldName.isEmpty();
    }

    /** 出生点当前可传送时返回 Location；否则返回 null。 */
    public Location resolve() {
        if (!isSet()) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null || y < world.getMinHeight() || y > world.getMaxHeight()) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }

    /** 返回出生点不可用的原因；可用时返回 null。 */
    public String validationError() {
        if (!isSet()) {
            return "spawn.world 为空（尚未设置出生点）";
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return "世界 " + worldName + " 未加载";
        }
        if (y < world.getMinHeight() || y > world.getMaxHeight()) {
            return "Y=" + format(y) + " 超出世界 " + worldName + " 的高度范围 ["
                    + world.getMinHeight() + ", " + world.getMaxHeight() + "]";
        }
        return null;
    }

    public String describe() {
        if (!isSet()) {
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
