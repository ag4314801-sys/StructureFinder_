package com.cavemapplus.map;

import java.util.Arrays;

/**
 * Thread-safe per-chunk color cache storing both the 16x16 top-down Surface terrain colors
 * and the 16x16 horizontal Cave cross-section slice colors.
 */
public final class MapChunkData {

    public static final int CHUNK_SIDE = 16;
    public static final int PIXEL_COUNT = CHUNK_SIDE * CHUNK_SIDE;

    private final int chunkX;
    private final int chunkZ;
    private final int[] surfaceColors;
    private final short[] surfaceHeights;
    private volatile int[] caveColors;
    private volatile int sampledCaveSliceY;
    private volatile long lastUpdatedMs;

    public MapChunkData(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.surfaceColors = new int[PIXEL_COUNT];
        this.surfaceHeights = new short[PIXEL_COUNT];
        this.caveColors = new int[PIXEL_COUNT];
        this.sampledCaveSliceY = Integer.MIN_VALUE;
        this.lastUpdatedMs = System.currentTimeMillis();
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public synchronized void updateSurfaceData(int[] newColors, short[] newHeights) {
        if (newColors != null && newColors.length == PIXEL_COUNT) {
            System.arraycopy(newColors, 0, this.surfaceColors, 0, PIXEL_COUNT);
        }
        if (newHeights != null && newHeights.length == PIXEL_COUNT) {
            System.arraycopy(newHeights, 0, this.surfaceHeights, 0, PIXEL_COUNT);
        }
        this.lastUpdatedMs = System.currentTimeMillis();
    }

    public synchronized void updateCaveSliceData(int[] newCaveColors, int sliceY) {
        if (newCaveColors != null && newCaveColors.length == PIXEL_COUNT) {
            this.caveColors = Arrays.copyOf(newCaveColors, PIXEL_COUNT);
            this.sampledCaveSliceY = sliceY;
            this.lastUpdatedMs = System.currentTimeMillis();
        }
    }

    public int getSurfaceColor(int localX, int localZ) {
        return surfaceColors[(localZ << 4) | (localX & 15)];
    }

    public int getSurfaceHeight(int localX, int localZ) {
        return surfaceHeights[(localZ << 4) | (localX & 15)];
    }

    public int getCaveColor(int localX, int localZ) {
        return caveColors[(localZ << 4) | (localX & 15)];
    }

    public int getPixelColor(int localX, int localZ, boolean caveMode) {
        return caveMode ? getCaveColor(localX, localZ) : getSurfaceColor(localX, localZ);
    }

    public int getSampledCaveSliceY() {
        return sampledCaveSliceY;
    }

    public long getLastUpdatedMs() {
        return lastUpdatedMs;
    }
}
