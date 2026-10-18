package com.cavemapplus.map;

import com.cavemapplus.scanner.StructureDetector;
import com.cavemapplus.scanner.StructureDetector.SpawnerSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronous terrain and cave cross-section pixel sampler for CaveMapPlus.
 * Uses Executors.newSingleThreadExecutor() to process chunk snapshots off the render thread.
 */
public final class MapEngine {

    private static final int CHUNKS_DISPATCHED_PER_TICK = 4;
    private static final int CAVE_VERTICAL_SCAN_WINDOW = 10;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CaveMapPlus-Worker");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final Map<Long, MapChunkData> chunkMapCache = new ConcurrentHashMap<>();
    private final Set<Long> scannedChunkKeys = ConcurrentHashMap.newKeySet();
    private final Deque<ChunkPos> scanQueue = new ArrayDeque<>();
    private final StructureDetector structureDetector = new StructureDetector();

    private volatile boolean caveMode = false;
    private volatile int caveSliceY = -16;
    private volatile boolean autoFollowPlayerY = true;

    private String activeDimensionId = "minecraft:overworld";
    private int lastCenterChunkX = Integer.MIN_VALUE;
    private int lastCenterChunkZ = Integer.MIN_VALUE;
    private int lastViewDistance = -1;
    private int lastSampledCaveY = Integer.MIN_VALUE;

    public void onClientTick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return;
        }

        ClientWorld world = client.world;
        String dimId = world.getRegistryKey().getValue().toString();
        if (!dimId.equals(activeDimensionId)) {
            activeDimensionId = dimId;
            clearAll();
        }

        if (autoFollowPlayerY) {
            this.caveSliceY = MathHelper.clamp(client.player.getBlockY(), world.getBottomY() + 2, world.getTopY() - 2);
        }

        // View distance via MinecraftClient.getInstance().options.getViewDistance().getValue()
        int viewDistance = MinecraftClient.getInstance().options.getViewDistance().getValue();
        int playerChunkX = client.player.getBlockX() >> 4;
        int playerChunkZ = client.player.getBlockZ() >> 4;

        boolean caveDepthMoved = caveMode && Math.abs(caveSliceY - lastSampledCaveY) >= 4;

        if (playerChunkX != lastCenterChunkX
                || playerChunkZ != lastCenterChunkZ
                || viewDistance != lastViewDistance
                || caveDepthMoved) {
            lastCenterChunkX = playerChunkX;
            lastCenterChunkZ = playerChunkZ;
            lastViewDistance = viewDistance;
            if (caveDepthMoved) {
                lastSampledCaveY = caveSliceY;
                scannedChunkKeys.clear();
            }
            pruneOutsideViewDistance(playerChunkX, playerChunkZ, viewDistance);
            rebuildSpiralQueue(world, playerChunkX, playerChunkZ, viewDistance);
        }

        int dispatched = 0;
        while (dispatched < CHUNKS_DISPATCHED_PER_TICK && !scanQueue.isEmpty()) {
            ChunkPos pos = scanQueue.pollFirst();
            if (pos == null) break;

            if (Math.max(Math.abs(pos.x - playerChunkX), Math.abs(pos.z - playerChunkZ)) > viewDistance) {
                continue;
            }

            long chunkKey = pos.toLong();
            if (scannedChunkKeys.contains(chunkKey)) {
                continue;
            }
            if (!world.getChunkManager().isChunkLoaded(pos.x, pos.z)) {
                continue;
            }

            WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z, false);
            if (chunk == null || chunk.isEmpty()) {
                continue;
            }

            scannedChunkKeys.add(chunkKey);

            // Capture thread-safe section references and surface heightmap on client thread
            ChunkSection[] rawSections = chunk.getSectionArray();
            final ChunkSection[] sectionsCopy = new ChunkSection[rawSections.length];
            System.arraycopy(rawSections, 0, sectionsCopy, 0, rawSections.length);

            final short[] surfaceHeights = new short[MapChunkData.PIXEL_COUNT];
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    surfaceHeights[(lz << 4) | lx] =
                            (short) chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, lx, lz);
                }
            }

            final int bottomY = world.getBottomY();
            final int topY = world.getTopY();
            final int targetSliceY = this.caveSliceY;
            final int cx = pos.x;
            final int cz = pos.z;
            final List<SpawnerSnapshot> spawners = StructureDetector.captureSpawners(chunk);

            executor.submit(() -> processChunkAsync(
                    cx, cz, bottomY, topY, targetSliceY, dimId, sectionsCopy, surfaceHeights, spawners
            ));
            dispatched++;
        }
    }

    private void processChunkAsync(
            int chunkX,
            int chunkZ,
            int bottomY,
            int topY,
            int sliceY,
            String dimensionId,
            ChunkSection[] sections,
            short[] surfaceHeights,
            List<SpawnerSnapshot> spawners
    ) {
        try {
            int[] surfacePixels = sampleSurfaceColors(sections, surfaceHeights, bottomY, topY);
            int[] cavePixels = sampleCaveSliceColors(sections, sliceY, bottomY, topY);

            long key = ChunkPos.toLong(chunkX, chunkZ);
            MapChunkData data = chunkMapCache.computeIfAbsent(key, k -> new MapChunkData(chunkX, chunkZ));
            data.updateSurfaceData(surfacePixels, surfaceHeights);
            data.updateCaveSliceData(cavePixels, sliceY);

            structureDetector.analyzeChunkSections(chunkX, chunkZ, bottomY, dimensionId, sections, spawners);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Samples top-down surface colors with north-west elevation hillshading.
     */
    private int[] sampleSurfaceColors(ChunkSection[] sections, short[] heights, int bottomY, int topY) {
        int[] pixels = new int[MapChunkData.PIXEL_COUNT];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int idx = (lz << 4) | lx;
                int y = MathHelper.clamp(heights[idx], bottomY, topY - 1);
                BlockState state = getBlockStateInSections(sections, lx, y, lz, bottomY);
                while (state.isAir() && y > bottomY) {
                    y--;
                    state = getBlockStateInSections(sections, lx, y, lz, bottomY);
                }

                int baseRgb = extractBlockRgb(state);
                int hNorth = heights[(Math.max(0, lz - 1) << 4) | lx];
                int hWest = heights[(lz << 4) | Math.max(0, lx - 1)];
                int slope = (y - hNorth) + (y - hWest);
                int shade = MathHelper.clamp(slope * 9, -36, 36);

                pixels[idx] = applyShade(baseRgb, shade);
            }
        }
        return pixels;
    }

    /**
     * Samples horizontal cross-sections around sliceY to map hollow cave systems, tunnels,
     * lava/water pools, and underground chambers.
     */
    private int[] sampleCaveSliceColors(ChunkSection[] sections, int sliceY, int bottomY, int topY) {
        int[] pixels = new int[MapChunkData.PIXEL_COUNT];
        int clampedSlice = MathHelper.clamp(sliceY, bottomY + 1, topY - 2);
        int minSearchY = Math.max(bottomY, clampedSlice - CAVE_VERTICAL_SCAN_WINDOW);
        int maxSearchY = Math.min(topY - 1, clampedSlice + CAVE_VERTICAL_SCAN_WINDOW);

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int idx = (lz << 4) | lx;

                // Search for hollow air/cave_air space near sliceY and find its floor block
                int airThickness = 0;
                int floorY = Integer.MIN_VALUE;
                BlockState floorState = Blocks.AIR.getDefaultState();

                for (int y = maxSearchY; y >= minSearchY; y--) {
                    BlockState state = getBlockStateInSections(sections, lx, y, lz, bottomY);
                    if (state.isAir() || state.isOf(Blocks.CAVE_AIR) || state.isOf(Blocks.VOID_AIR)) {
                        airThickness++;
                    } else if (airThickness > 0) {
                        floorY = y;
                        floorState = state;
                        break;
                    }
                }

                if (floorY == Integer.MIN_VALUE) {
                    // Solid rock wall at this slice depth
                    BlockState solid = getBlockStateInSections(sections, lx, clampedSlice, lz, bottomY);
                    int wallRgb = extractBlockRgb(solid);
                    pixels[idx] = darkenColor(wallRgb, 0.28F);
                } else {
                    // Hollow cave tunnel / chamber floor
                    int floorRgb = extractBlockRgb(floorState);
                    if (floorState.isOf(Blocks.LAVA)) {
                        floorRgb = 0xFFFF5722;
                    } else if (floorState.isOf(Blocks.WATER)) {
                        floorRgb = 0xFF1E88E5;
                    } else if (floorState.isOf(Blocks.SCULK) || floorState.isOf(Blocks.SCULK_VEIN)) {
                        floorRgb = 0xFF00ACC1;
                    }
                    int depthDelta = (floorY - clampedSlice) * 4 + Math.min(28, airThickness * 3);
                    pixels[idx] = applyShade(floorRgb, depthDelta);
                }
            }
        }
        return pixels;
    }

    private BlockState getBlockStateInSections(ChunkSection[] sections, int lx, int worldY, int lz, int bottomY) {
        int secIdx = (worldY - bottomY) >> 4;
        if (secIdx < 0 || secIdx >= sections.length) {
            return Blocks.AIR.getDefaultState();
        }
        ChunkSection section = sections[secIdx];
        if (section == null || section.isEmpty()) {
            return Blocks.AIR.getDefaultState();
        }
        return section.getBlockState(lx & 15, worldY & 15, lz & 15);
    }

    private int extractBlockRgb(BlockState state) {
        if (state == null || state.isAir()) {
            return 0x10161E;
        }
        try {
            MapColor mapColor = state.getMapColor(null, BlockPos.ORIGIN);
            if (mapColor != null && mapColor.color != 0) {
                return mapColor.color;
            }
        } catch (Throwable ignored) {
        }
        return 0x455A64;
    }

    private int applyShade(int rgb, int delta) {
        int r = MathHelper.clamp(((rgb >> 16) & 0xFF) + delta, 0, 255);
        int g = MathHelper.clamp(((rgb >> 8) & 0xFF) + delta, 0, 255);
        int b = MathHelper.clamp((rgb & 0xFF) + delta, 0, 255);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int darkenColor(int rgb, float factor) {
        int r = MathHelper.clamp((int) (((rgb >> 16) & 0xFF) * factor), 0, 255);
        int g = MathHelper.clamp((int) (((rgb >> 8) & 0xFF) * factor), 0, 255);
        int b = MathHelper.clamp((int) ((rgb & 0xFF) * factor), 0, 255);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void rebuildSpiralQueue(ClientWorld world, int centerX, int centerZ, int viewDistance) {
        scanQueue.clear();
        for (int r = 0; r <= viewDistance; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = centerX + dx;
                    int cz = centerZ + dz;
                    long key = ChunkPos.toLong(cx, cz);
                    if (!scannedChunkKeys.contains(key) && world.getChunkManager().isChunkLoaded(cx, cz)) {
                        scanQueue.addLast(new ChunkPos(cx, cz));
                    }
                }
            }
        }
    }

    private void pruneOutsideViewDistance(int playerChunkX, int playerChunkZ, int viewDistance) {
        for (Iterator<Map.Entry<Long, MapChunkData>> it = chunkMapCache.entrySet().iterator(); it.hasNext(); ) {
            MapChunkData data = it.next().getValue();
            int dcx = Math.abs(data.getChunkX() - playerChunkX);
            int dcz = Math.abs(data.getChunkZ() - playerChunkZ);
            if (Math.max(dcx, dcz) > viewDistance + 2) {
                it.remove();
            }
        }
        for (Iterator<Long> it = scannedChunkKeys.iterator(); it.hasNext(); ) {
            long key = it.next();
            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            if (Math.max(Math.abs(cx - playerChunkX), Math.abs(cz - playerChunkZ)) > viewDistance) {
                it.remove();
            }
        }
        structureDetector.pruneOutsideViewDistance(activeDimensionId, playerChunkX, playerChunkZ, viewDistance);
    }

    public void forceRescanCaveSlice() {
        this.scannedChunkKeys.clear();
        this.lastCenterChunkX = Integer.MIN_VALUE;
        this.lastCenterChunkZ = Integer.MIN_VALUE;
    }

    public void toggleCaveMode() {
        this.caveMode = !this.caveMode;
        forceRescanCaveSlice();
    }

    public void setCaveMode(boolean caveMode) {
        this.caveMode = caveMode;
        forceRescanCaveSlice();
    }

    public boolean isCaveMode() {
        return caveMode;
    }

    public int getCaveSliceY() {
        return caveSliceY;
    }

    public void setCaveSliceY(int sliceY) {
        this.autoFollowPlayerY = false;
        this.caveSliceY = sliceY;
        forceRescanCaveSlice();
    }

    public Map<Long, MapChunkData> getChunkMapCache() {
        return chunkMapCache;
    }

    public StructureDetector getStructureDetector() {
        return structureDetector;
    }

    public String getActiveDimensionId() {
        return activeDimensionId;
    }

    public void clearAll() {
        chunkMapCache.clear();
        scannedChunkKeys.clear();
        scanQueue.clear();
        structureDetector.clearAll();
        lastCenterChunkX = Integer.MIN_VALUE;
        lastCenterChunkZ = Integer.MIN_VALUE;
    }
}
