package com.cavemapplus.scanner;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Immutable Java 21 record representing a verified structure detected within loaded chunks.
 */
public record DetectedStructure(
        StructureType type,
        BlockPos pos,
        String dimensionId,
        long discoveredAtEpochMs,
        String signatureDetail,
        int anchorPriority
) {
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public enum StructureType {
        TRIAL_CHAMBER("Trial Chamber", "TC", 0xFFE67E22, Identifier.of("cavemapplus", "trial_chamber"), 96),
        ANCIENT_CITY("Ancient City", "AC", 0xFF00E5FF, Identifier.of("cavemapplus", "ancient_city"), 128),
        NETHER_FORTRESS("Nether Fortress", "NF", 0xFFE74C3C, Identifier.of("cavemapplus", "nether_fortress"), 96),
        OCEAN_MONUMENT("Ocean Monument", "OM", 0xFF1ABC9C, Identifier.of("cavemapplus", "ocean_monument"), 80),
        END_CITY("End City", "EC", 0xFF9B59B6, Identifier.of("cavemapplus", "end_city"), 96);

        private final String displayName;
        private final String shortCode;
        private final int colorArgb;
        private final Identifier iconId;
        private final int clusterRadiusBlocks;

        StructureType(String displayName, String shortCode, int colorArgb, Identifier iconId, int clusterRadiusBlocks) {
            this.displayName = displayName;
            this.shortCode = shortCode;
            this.colorArgb = colorArgb;
            this.iconId = iconId;
            this.clusterRadiusBlocks = clusterRadiusBlocks;
        }

        public String displayName() {
            return displayName;
        }

        public String shortCode() {
            return shortCode;
        }

        public int colorArgb() {
            return colorArgb;
        }

        public Identifier iconId() {
            return iconId;
        }

        public int clusterRadiusBlocks() {
            return clusterRadiusBlocks;
        }
    }

    public int chunkX() {
        return pos.getX() >> 4;
    }

    public int chunkZ() {
        return pos.getZ() >> 4;
    }

    public double distanceTo(double playerX, double playerY, double playerZ) {
        double dx = (pos.getX() + 0.5D) - playerX;
        double dy = (pos.getY() + 0.5D) - playerY;
        double dz = (pos.getZ() + 0.5D) - playerZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double horizontalDistanceTo(double playerX, double playerZ) {
        double dx = (pos.getX() + 0.5D) - playerX;
        double dz = (pos.getZ() + 0.5D) - playerZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public boolean isWithinViewDistance(int centerChunkX, int centerChunkZ, int viewDistanceChunks) {
        int dcx = Math.abs(chunkX() - centerChunkX);
        int dcz = Math.abs(chunkZ() - centerChunkZ);
        return Math.max(dcx, dcz) <= viewDistanceChunks;
    }

    public String formattedCoordinates() {
        return String.format("X: %d, Y: %d, Z: %d", pos.getX(), pos.getY(), pos.getZ());
    }

    public String formattedTimestamp() {
        return TIME_FMT.format(Instant.ofEpochMilli(discoveredAtEpochMs));
    }
}
