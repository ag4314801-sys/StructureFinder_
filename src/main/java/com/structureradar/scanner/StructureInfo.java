package com.structureradar.scanner;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

/**
 * Represents a discovered structure with exact coordinates and player-relative directional math
 * for Minecraft 1.16.5.
 */
public final class StructureInfo {

    public enum StructureCategory {
        STRONGHOLD("Stronghold", "SH", 0xFF2ECC71, "minecraft:overworld"),
        NETHER_FORTRESS("Fortress", "NF", 0xFFE74C3C, "minecraft:the_nether"),
        BASTION_REMNANT("Bastion", "BR", 0xFFF1C40F, "minecraft:the_nether"),
        END_CITY("End City", "EC", 0xFF9B59B6, "minecraft:the_end"),
        OCEAN_MONUMENT("Monument", "OM", 0xFF1ABC9C, "minecraft:overworld"),
        WOODLAND_MANSION("Mansion", "WM", 0xFF8D6E63, "minecraft:overworld"),
        DESERT_PYRAMID("Desert Temple", "DP", 0xFFF39C12, "minecraft:overworld"),
        JUNGLE_TEMPLE("Jungle Temple", "JT", 0xFF27AE60, "minecraft:overworld"),
        PILLAGER_OUTPOST("Outpost", "PO", 0xFF95A5A6, "minecraft:overworld"),
        MINESHAFT("Mineshaft", "MS", 0xFFBDC3C7, "minecraft:overworld"),
        DUNGEON("Spawner Room", "DG", 0xFFE67E22, "minecraft:overworld"),
        RUINED_PORTAL("Ruined Portal", "RP", 0xFFAA00FF, "ANY"),
        SHIPWRECK("Shipwreck", "SW", 0xFF3498DB, "minecraft:overworld"),
        VILLAGE("Village", "VL", 0xFF58D68D, "minecraft:overworld"),
        IGLOO_OR_HUT("Witch Hut/Igloo", "IH", 0xFF85C1E9, "minecraft:overworld");

        private final String displayName;
        private final String shortCode;
        private final int colorArgb;
        private final String expectedDimension;

        StructureCategory(String displayName, String shortCode, int colorArgb, String expectedDimension) {
            this.displayName = displayName;
            this.shortCode = shortCode;
            this.colorArgb = colorArgb;
            this.expectedDimension = expectedDimension;
        }

        public String getDisplayName() { return displayName; }
        public String getShortCode() { return shortCode; }
        public int getColorArgb() { return colorArgb; }
        public String getExpectedDimension() { return expectedDimension; }

        public boolean matchesDimension(String dimensionId) {
            return "ANY".equals(expectedDimension) || expectedDimension.equals(dimensionId);
        }
    }

    private final StructureCategory category;
    private final int x;
    private final int y;
    private final int z;
    private final String dimension;
    private final long discoveredTimestampMs;
    private final String detectionSource;

    public StructureInfo(
            StructureCategory category,
            int x,
            int y,
            int z,
            String dimension,
            long discoveredTimestampMs,
            String detectionSource
    ) {
        this.category = Objects.requireNonNull(category, "category");
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension != null ? dimension : "minecraft:overworld";
        this.discoveredTimestampMs = discoveredTimestampMs;
        this.detectionSource = detectionSource != null ? detectionSource : "Signature";
    }

    public StructureCategory getCategory() { return category; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public BlockPos getBlockPos() { return new BlockPos(x, y, z); }
    public String getDimension() { return dimension; }
    public long getDiscoveredTimestampMs() { return discoveredTimestampMs; }
    public String getDetectionSource() { return detectionSource; }

    public double getHorizontalDistanceTo(double playerX, double playerZ) {
        double dx = (this.x + 0.5D) - playerX;
        double dz = (this.z + 0.5D) - playerZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Computes the exact signed angle in degrees [-180, +180] from the player's current facing yaw
     * to this structure in Minecraft 1.16.5 coordinates:
     * - South (+Z) is 0 deg, West (-X) is +90 deg, North (-Z) is +/-180 deg, East (+X) is -90 deg.
     * - Returns 0 deg when the player is looking directly at the structure,
     *   +90 deg when the structure is to the player's right,
     *   -90 deg when the structure is to the player's left,
     *   and +/-180 deg when the structure is directly behind the player.
     */
    public double getRelativeAngleDegrees(double playerX, double playerZ, float playerYaw) {
        double dx = (this.x + 0.5D) - playerX;
        double dz = (this.z + 0.5D) - playerZ;
        double targetWorldYaw = Math.toDegrees(Math.atan2(-dx, dz));
        return MathHelper.wrapDegrees(targetWorldYaw - (double) playerYaw);
    }

    /**
     * Returns an 8-way directional arrow showing which way the player should turn/walk
     * relative to where they are currently looking.
     */
    public String getRelativeDirectionArrow(double playerX, double playerZ, float playerYaw) {
        double rel = getRelativeAngleDegrees(playerX, playerZ, playerYaw);
        if (rel >= -22.5D && rel <= 22.5D)   return "\u25B2"; // ▲ Straight Ahead
        if (rel > 22.5D && rel <= 67.5D)     return "\u2197"; // ↗ Ahead-Right
        if (rel > 67.5D && rel <= 112.5D)    return "\u25B6"; // ▶ Right
        if (rel > 112.5D && rel <= 157.5D)   return "\u2198"; // ↘ Behind-Right
        if (rel < -22.5D && rel >= -67.5D)   return "\u2196"; // ↖ Ahead-Left
        if (rel < -67.5D && rel >= -112.5D)  return "\u25C0"; // ◀ Left
        if (rel < -112.5D && rel >= -157.5D) return "\u2199"; // ↙ Behind-Left
        return "\u25BC";                                      // ▼ Behind
    }

    /**
     * Returns the world cardinal direction (N, NE, E, SE, S, SW, W, NW) from the player to the structure.
     */
    public String getWorldCardinalFromPlayer(double playerX, double playerZ) {
        double dx = (this.x + 0.5D) - playerX;
        double dz = (this.z + 0.5D) - playerZ;
        // Angle where 0 = North (-Z), 90 = East (+X), 180 = South (+Z), -90 = West (-X)
        double degFromNorth = Math.toDegrees(Math.atan2(dx, -dz));
        if (degFromNorth < 0) {
            degFromNorth += 360.0D;
        }
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int idx = (int) Math.floor(((degFromNorth + 22.5D) % 360.0D) / 45.0D);
        return dirs[idx];
    }

    public String getFormattedTime() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date(discoveredTimestampMs));
    }

    public String getCompactCoords() {
        return x + ", " + y + ", " + z;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StructureInfo)) return false;
        StructureInfo that = (StructureInfo) o;
        return x == that.x && y == that.y && z == that.z
                && category == that.category
                && Objects.equals(dimension, that.dimension);
    }

    @Override
    public int hashCode() {
        return Objects.hash(category, x, y, z, dimension);
    }
}
