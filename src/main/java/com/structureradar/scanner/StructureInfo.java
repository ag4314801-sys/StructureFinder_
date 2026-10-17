package com.structureradar.scanner;

import net.minecraft.util.math.BlockPos;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

/**
 * Immutable data model representing a detected structure in Minecraft 1.16.5.
 * Compatible with Java 8 (no Java 14+ record keyword).
 */
public final class StructureInfo {

    public enum StructureCategory {
        STRONGHOLD("Stronghold", "SH", 0xFF2ECC71, "minecraft:overworld"),
        NETHER_FORTRESS("Nether Fortress", "NF", 0xFFE74C3C, "minecraft:the_nether"),
        BASTION_REMNANT("Bastion Remnant", "BR", 0xFFF1C40F, "minecraft:the_nether"),
        END_CITY("End City", "EC", 0xFF9B59B6, "minecraft:the_end"),
        OCEAN_MONUMENT("Ocean Monument", "OM", 0xFF1ABC9C, "minecraft:overworld"),
        WOODLAND_MANSION("Woodland Mansion", "WM", 0xFF8D6E63, "minecraft:overworld"),
        DESERT_PYRAMID("Desert Pyramid", "DP", 0xFFF39C12, "minecraft:overworld"),
        JUNGLE_TEMPLE("Jungle Temple", "JT", 0xFF27AE60, "minecraft:overworld"),
        PILLAGER_OUTPOST("Pillager Outpost", "PO", 0xFF95A5A6, "minecraft:overworld"),
        MINESHAFT("Mineshaft", "MS", 0xFFBDC3C7, "minecraft:overworld"),
        DUNGEON("Monster Dungeon", "DG", 0xFFE67E22, "minecraft:overworld"),
        RUINED_PORTAL("Ruined Portal", "RP", 0xFFAA00FF, "ANY"),
        SHIPWRECK("Shipwreck / Ruin", "SW", 0xFF3498DB, "minecraft:overworld"),
        VILLAGE("Village", "VL", 0xFF58D68D, "minecraft:overworld"),
        IGLOO_OR_HUT("Igloo / Witch Hut", "IH", 0xFF85C1E9, "minecraft:overworld");

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

        public String getDisplayName() {
            return displayName;
        }

        public String getShortCode() {
            return shortCode;
        }

        public int getColorArgb() {
            return colorArgb;
        }

        public String getExpectedDimension() {
            return expectedDimension;
        }

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
    private final int confidenceScore;

    public StructureInfo(
            StructureCategory category,
            int x,
            int y,
            int z,
            String dimension,
            long discoveredTimestampMs,
            String detectionSource,
            int confidenceScore
    ) {
        this.category = Objects.requireNonNull(category, "category");
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension != null ? dimension : "minecraft:overworld";
        this.discoveredTimestampMs = discoveredTimestampMs;
        this.detectionSource = detectionSource != null ? detectionSource : "Block Signature";
        this.confidenceScore = confidenceScore;
    }

    public StructureCategory getCategory() {
        return category;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public BlockPos getBlockPos() {
        return new BlockPos(x, y, z);
    }

    public String getDimension() {
        return dimension;
    }

    public long getDiscoveredTimestampMs() {
        return discoveredTimestampMs;
    }

    public String getDetectionSource() {
        return detectionSource;
    }

    public int getConfidenceScore() {
        return confidenceScore;
    }

    public double getHorizontalDistanceTo(double playerX, double playerZ) {
        double dx = (this.x + 0.5D) - playerX;
        double dz = (this.z + 0.5D) - playerZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public double getDistance3DTo(double playerX, double playerY, double playerZ) {
        double dx = (this.x + 0.5D) - playerX;
        double dy = (this.y + 0.5D) - playerY;
        double dz = (this.z + 0.5D) - playerZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public String getFormattedTime() {
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        return fmt.format(new Date(discoveredTimestampMs));
    }

    public String getFormattedCoordinates() {
        return String.format("X: %d, Y: %d, Z: %d", x, y, z);
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
