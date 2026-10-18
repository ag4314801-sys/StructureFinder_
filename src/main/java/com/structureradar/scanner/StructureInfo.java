package com.structureradar.scanner;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.Objects;

/**
 * Holds verified structure coordinates, merge radius, and player-relative directional math
 * for Minecraft 1.16.5.
 */
public final class StructureInfo {

    public enum StructureCategory {
        STRONGHOLD("Stronghold", "SH", 0xFF2ECC71, "minecraft:overworld", 96),
        NETHER_FORTRESS("Fortress", "NF", 0xFFE74C3C, "minecraft:the_nether", 96),
        BASTION_REMNANT("Bastion", "BR", 0xFFF1C40F, "minecraft:the_nether", 88),
        END_CITY("End City", "EC", 0xFF9B59B6, "minecraft:the_end", 88),
        OCEAN_MONUMENT("Monument", "OM", 0xFF1ABC9C, "minecraft:overworld", 72),
        WOODLAND_MANSION("Mansion", "WM", 0xFF8D6E63, "minecraft:overworld", 96),
        DESERT_PYRAMID("Desert Temple", "DP", 0xFFF39C12, "minecraft:overworld", 48),
        JUNGLE_TEMPLE("Jungle Temple", "JT", 0xFF27AE60, "minecraft:overworld", 48),
        PILLAGER_OUTPOST("Outpost", "PO", 0xFF95A5A6, "minecraft:overworld", 64),
        MINESHAFT("Mineshaft", "MS", 0xFFBDC3C7, "minecraft:overworld", 64),
        DUNGEON("Spawner", "DG", 0xFFE67E22, "minecraft:overworld", 24),
        RUINED_PORTAL("Ruined Portal", "RP", 0xFFAA00FF, "ANY", 40),
        SHIPWRECK("Shipwreck", "SW", 0xFF3498DB, "minecraft:overworld", 48),
        VILLAGE("Village", "VL", 0xFF58D68D, "minecraft:overworld", 80),
        IGLOO_OR_HUT("Hut / Igloo", "IH", 0xFF85C1E9, "minecraft:overworld", 48);

        private final String displayName;
        private final String shortCode;
        private final int colorArgb;
        private final String expectedDimension;
        private final int mergeRadiusBlocks;

        StructureCategory(String displayName, String shortCode, int colorArgb, String expectedDimension, int mergeRadiusBlocks) {
            this.displayName = displayName;
            this.shortCode = shortCode;
            this.colorArgb = colorArgb;
            this.expectedDimension = expectedDimension;
            this.mergeRadiusBlocks = mergeRadiusBlocks;
        }

        public String getDisplayName() { return displayName; }
        public String getShortCode() { return shortCode; }
        public int getColorArgb() { return colorArgb; }
        public String getExpectedDimension() { return expectedDimension; }
        public int getMergeRadiusBlocks() { return mergeRadiusBlocks; }

        public boolean matchesDimension(String dimensionId) {
            return "ANY".equals(expectedDimension) || expectedDimension.equals(dimensionId);
        }
    }

    private final StructureCategory category;
    private int x;
    private int y;
    private int z;
    private final String dimension;
    private final long discoveredTimestampMs;
    private String anchorDescription;
    private int anchorPriority;

    public StructureInfo(
            StructureCategory category,
            int x,
            int y,
            int z,
            String dimension,
            long discoveredTimestampMs,
            String anchorDescription,
            int anchorPriority
    ) {
        this.category = Objects.requireNonNull(category, "category");
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension != null ? dimension : "minecraft:overworld";
        this.discoveredTimestampMs = discoveredTimestampMs;
        this.anchorDescription = anchorDescription != null ? anchorDescription : "Verified";
        this.anchorPriority = anchorPriority;
    }

    /**
     * If an adjacent chunk in the same multi-chunk structure contains a higher-priority centerpiece block
     * (for example, finding the End Portal Frame after scanning a Stronghold hallway chunk),
     * snaps the structure coordinates to the higher-priority centerpiece block!
     */
    public synchronized void refineIfHigherPriority(int newX, int newY, int newZ, String newDesc, int newPriority) {
        if (newPriority > this.anchorPriority) {
            this.x = newX;
            this.y = newY;
            this.z = newZ;
            this.anchorDescription = newDesc;
            this.anchorPriority = newPriority;
        }
    }

    public StructureCategory getCategory() { return category; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public int getChunkX() { return x >> 4; }
    public int getChunkZ() { return z >> 4; }
    public BlockPos getBlockPos() { return new BlockPos(x, y, z); }
    public String getDimension() { return dimension; }
    public long getDiscoveredTimestampMs() { return discoveredTimestampMs; }
    public String getAnchorDescription() { return anchorDescription; }
    public int getAnchorPriority() { return anchorPriority; }

    public double getHorizontalDistanceTo(double playerX, double playerZ) {
        double dx = (this.x + 0.5D) - playerX;
        double dz = (this.z + 0.5D) - playerZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Returns whether this structure is within the player's active chunk render distance.
     */
    public boolean isWithinRenderDistance(int playerChunkX, int playerChunkZ, int viewDistanceChunks) {
        int dcx = Math.abs(getChunkX() - playerChunkX);
        int dcz = Math.abs(getChunkZ() - playerChunkZ);
        return Math.max(dcx, dcz) <= viewDistanceChunks;
    }

    /**
     * Exact signed angle in degrees [-180, +180] from the player's current facing yaw to this structure.
     * 0 deg = directly ahead, +90 deg = right, -90 deg = left, +/-180 deg = behind.
     */
    public double getRelativeAngleDegrees(double playerX, double playerZ, float playerYaw) {
        double dx = (this.x + 0.5D) - playerX;
        double dz = (this.z + 0.5D) - playerZ;
        double targetWorldYaw = Math.toDegrees(Math.atan2(-dx, dz));
        return MathHelper.wrapDegrees(targetWorldYaw - (double) playerYaw);
    }

    public String getRelativeDirectionArrow(double playerX, double playerZ, float playerYaw) {
        double rel = getRelativeAngleDegrees(playerX, playerZ, playerYaw);
        if (rel >= -22.5D && rel <= 22.5D)   return "\u25B2"; // ▲ Ahead
        if (rel > 22.5D && rel <= 67.5D)     return "\u2197"; // ↗ Ahead-Right
        if (rel > 67.5D && rel <= 112.5D)    return "\u25B6"; // ▶ Right
        if (rel > 112.5D && rel <= 157.5D)   return "\u2198"; // ↘ Back-Right
        if (rel < -22.5D && rel >= -67.5D)   return "\u2196"; // ↖ Ahead-Left
        if (rel < -67.5D && rel >= -112.5D)  return "\u25C0"; // ◀ Left
        if (rel < -112.5D && rel >= -157.5D) return "\u2199"; // ↙ Back-Left
        return "\u25BC";                                      // ▼ Behind
    }

    public String getWorldCardinalFromPlayer(double playerX, double playerZ) {
        double dx = (this.x + 0.5D) - playerX;
        double dz = (this.z + 0.5D) - playerZ;
        double degFromNorth = Math.toDegrees(Math.atan2(dx, -dz));
        if (degFromNorth < 0) {
            degFromNorth += 360.0D;
        }
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int idx = (int) Math.floor(((degFromNorth + 22.5D) % 360.0D) / 45.0D);
        return dirs[idx];
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
