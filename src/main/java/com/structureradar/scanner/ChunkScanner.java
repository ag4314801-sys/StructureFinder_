package com.structureradar.scanner;

import com.structureradar.config.ModConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.feature.StructureFeature;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Asynchronous Render-Distance Chunk Scanner for Minecraft 1.16.5.
 * Computes the exact weighted block centroid (average X, Y, Z of matched signature blocks)
 * so the radar pointer aims directly at the heart of the structure.
 */
public class ChunkScanner {

    private static final class ChunkSnapshot {
        final int chunkX;
        final int chunkZ;
        final String dimensionId;
        final ChunkSection[] sections;
        final List<SpawnerRecord> spawners;

        ChunkSnapshot(int chunkX, int chunkZ, String dimensionId, ChunkSection[] sections, List<SpawnerRecord> spawners) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.dimensionId = dimensionId;
            this.sections = sections;
            this.spawners = spawners;
        }
    }

    private static final class SpawnerRecord {
        final BlockPos pos;
        final String entityId;

        SpawnerRecord(BlockPos pos, String entityId) {
            this.pos = pos;
            this.entityId = entityId;
        }
    }

    public static final class HudNotification {
        public final StructureInfo structure;
        public final long createdTimeMs;

        public HudNotification(StructureInfo structure, long createdTimeMs) {
            this.structure = structure;
            this.createdTimeMs = createdTimeMs;
        }
    }

    private final List<StructureInfo> discoveredStructures = new CopyOnWriteArrayList<StructureInfo>();
    private final Set<Long> scannedChunkKeys = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
    private final Deque<ChunkPos> pendingChunksQueue = new ArrayDeque<ChunkPos>();
    private final Deque<HudNotification> recentNotifications = new ArrayDeque<HudNotification>();

    private final Map<Block, Map<StructureInfo.StructureCategory, Integer>> blockWeights =
            new IdentityHashMap<Block, Map<StructureInfo.StructureCategory, Integer>>();
    private final Map<StructureInfo.StructureCategory, Integer> scoreThresholds =
            new EnumMap<StructureInfo.StructureCategory, Integer>(StructureInfo.StructureCategory.class);

    private final ExecutorService workerPool;

    private volatile StructureInfo trackedTarget = null;
    private String currentDimensionId = "minecraft:overworld";
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int lastViewDistance = -1;

    public ChunkScanner() {
        this.workerPool = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private int counter = 1;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "StructureRadar-Worker-" + (counter++));
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });
        initializeSignatureTables();
    }

    private void initializeSignatureTables() {
        // 1. Stronghold
        addWeight(Blocks.END_PORTAL_FRAME, StructureInfo.StructureCategory.STRONGHOLD, 65);
        addWeight(Blocks.END_PORTAL, StructureInfo.StructureCategory.STRONGHOLD, 65);
        addWeight(Blocks.INFESTED_STONE_BRICKS, StructureInfo.StructureCategory.STRONGHOLD, 12);
        addWeight(Blocks.INFESTED_MOSSY_STONE_BRICKS, StructureInfo.StructureCategory.STRONGHOLD, 14);
        addWeight(Blocks.INFESTED_CRACKED_STONE_BRICKS, StructureInfo.StructureCategory.STRONGHOLD, 14);
        addWeight(Blocks.INFESTED_CHISELED_STONE_BRICKS, StructureInfo.StructureCategory.STRONGHOLD, 16);
        scoreThresholds.put(StructureInfo.StructureCategory.STRONGHOLD, 38);

        // 2. Nether Fortress
        addWeight(Blocks.NETHER_BRICK_FENCE, StructureInfo.StructureCategory.NETHER_FORTRESS, 6);
        addWeight(Blocks.NETHER_BRICK_STAIRS, StructureInfo.StructureCategory.NETHER_FORTRESS, 4);
        addWeight(Blocks.NETHER_WART, StructureInfo.StructureCategory.NETHER_FORTRESS, 12);
        addWeight(Blocks.NETHER_BRICKS, StructureInfo.StructureCategory.NETHER_FORTRESS, 1);
        scoreThresholds.put(StructureInfo.StructureCategory.NETHER_FORTRESS, 42);

        // 3. Bastion Remnant
        addWeight(Blocks.GILDED_BLACKSTONE, StructureInfo.StructureCategory.BASTION_REMNANT, 16);
        addWeight(Blocks.CHISELED_POLISHED_BLACKSTONE, StructureInfo.StructureCategory.BASTION_REMNANT, 10);
        addWeight(Blocks.LODESTONE, StructureInfo.StructureCategory.BASTION_REMNANT, 45);
        addWeight(Blocks.GOLD_BLOCK, StructureInfo.StructureCategory.BASTION_REMNANT, 10);
        addWeight(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, StructureInfo.StructureCategory.BASTION_REMNANT, 4);
        scoreThresholds.put(StructureInfo.StructureCategory.BASTION_REMNANT, 40);

        // 4. End City
        addWeight(Blocks.DRAGON_HEAD, StructureInfo.StructureCategory.END_CITY, 70);
        addWeight(Blocks.DRAGON_WALL_HEAD, StructureInfo.StructureCategory.END_CITY, 70);
        addWeight(Blocks.END_ROD, StructureInfo.StructureCategory.END_CITY, 10);
        addWeight(Blocks.PURPUR_PILLAR, StructureInfo.StructureCategory.END_CITY, 5);
        addWeight(Blocks.PURPUR_STAIRS, StructureInfo.StructureCategory.END_CITY, 4);
        addWeight(Blocks.PURPUR_BLOCK, StructureInfo.StructureCategory.END_CITY, 2);
        addWeight(Blocks.MAGENTA_STAINED_GLASS, StructureInfo.StructureCategory.END_CITY, 8);
        scoreThresholds.put(StructureInfo.StructureCategory.END_CITY, 36);

        // 5. Ocean Monument
        addWeight(Blocks.WET_SPONGE, StructureInfo.StructureCategory.OCEAN_MONUMENT, 30);
        addWeight(Blocks.DARK_PRISMARINE, StructureInfo.StructureCategory.OCEAN_MONUMENT, 6);
        addWeight(Blocks.PRISMARINE_BRICKS, StructureInfo.StructureCategory.OCEAN_MONUMENT, 4);
        addWeight(Blocks.SEA_LANTERN, StructureInfo.StructureCategory.OCEAN_MONUMENT, 8);
        addWeight(Blocks.PRISMARINE, StructureInfo.StructureCategory.OCEAN_MONUMENT, 2);
        scoreThresholds.put(StructureInfo.StructureCategory.OCEAN_MONUMENT, 45);

        // 6. Woodland Mansion
        addWeight(Blocks.RED_CARPET, StructureInfo.StructureCategory.WOODLAND_MANSION, 5);
        addWeight(Blocks.WHITE_CARPET, StructureInfo.StructureCategory.WOODLAND_MANSION, 5);
        addWeight(Blocks.BOOKSHELF, StructureInfo.StructureCategory.WOODLAND_MANSION, 5);
        addWeight(Blocks.DARK_OAK_STAIRS, StructureInfo.StructureCategory.WOODLAND_MANSION, 2);
        scoreThresholds.put(StructureInfo.StructureCategory.WOODLAND_MANSION, 55);

        // 7. Desert Pyramid
        addWeight(Blocks.BLUE_TERRACOTTA, StructureInfo.StructureCategory.DESERT_PYRAMID, 18);
        addWeight(Blocks.ORANGE_TERRACOTTA, StructureInfo.StructureCategory.DESERT_PYRAMID, 8);
        addWeight(Blocks.CHISELED_SANDSTONE, StructureInfo.StructureCategory.DESERT_PYRAMID, 12);
        addWeight(Blocks.CUT_SANDSTONE, StructureInfo.StructureCategory.DESERT_PYRAMID, 4);
        addWeight(Blocks.TNT, StructureInfo.StructureCategory.DESERT_PYRAMID, 12);
        scoreThresholds.put(StructureInfo.StructureCategory.DESERT_PYRAMID, 42);

        // 8. Jungle Temple
        addWeight(Blocks.TRIPWIRE_HOOK, StructureInfo.StructureCategory.JUNGLE_TEMPLE, 24);
        addWeight(Blocks.CHISELED_STONE_BRICKS, StructureInfo.StructureCategory.JUNGLE_TEMPLE, 12);
        addWeight(Blocks.STICKY_PISTON, StructureInfo.StructureCategory.JUNGLE_TEMPLE, 16);
        addWeight(Blocks.DISPENSER, StructureInfo.StructureCategory.JUNGLE_TEMPLE, 16);
        scoreThresholds.put(StructureInfo.StructureCategory.JUNGLE_TEMPLE, 42);

        // 9. Pillager Outpost
        addWeight(Blocks.CARVED_PUMPKIN, StructureInfo.StructureCategory.PILLAGER_OUTPOST, 15);
        addWeight(Blocks.HAY_BLOCK, StructureInfo.StructureCategory.PILLAGER_OUTPOST, 6);
        addWeight(Blocks.DARK_OAK_FENCE, StructureInfo.StructureCategory.PILLAGER_OUTPOST, 4);
        addWeight(Blocks.WHITE_WALL_BANNER, StructureInfo.StructureCategory.PILLAGER_OUTPOST, 12);
        scoreThresholds.put(StructureInfo.StructureCategory.PILLAGER_OUTPOST, 40);

        // 10. Mineshaft
        addWeight(Blocks.COBWEB, StructureInfo.StructureCategory.MINESHAFT, 7);
        addWeight(Blocks.RAIL, StructureInfo.StructureCategory.MINESHAFT, 5);
        addWeight(Blocks.CHAIN, StructureInfo.StructureCategory.MINESHAFT, 5);
        scoreThresholds.put(StructureInfo.StructureCategory.MINESHAFT, 35);

        // 11. Monster Dungeon
        Block spawner = Registry.BLOCK.get(new Identifier("minecraft", "spawner"));
        addWeight(spawner != Blocks.AIR ? spawner : Blocks.SPAWNER, StructureInfo.StructureCategory.DUNGEON, 45);
        addWeight(Blocks.MOSSY_COBBLESTONE, StructureInfo.StructureCategory.DUNGEON, 2);
        scoreThresholds.put(StructureInfo.StructureCategory.DUNGEON, 55);

        // 12. Ruined Portal
        addWeight(Blocks.CRYING_OBSIDIAN, StructureInfo.StructureCategory.RUINED_PORTAL, 20);
        scoreThresholds.put(StructureInfo.StructureCategory.RUINED_PORTAL, 35);

        // 13. Village
        addWeight(Blocks.BELL, StructureInfo.StructureCategory.VILLAGE, 50);
        addWeight(Blocks.LECTERN, StructureInfo.StructureCategory.VILLAGE, 15);
        addWeight(Blocks.BREWING_STAND, StructureInfo.StructureCategory.VILLAGE, 14);
        addWeight(Blocks.BLAST_FURNACE, StructureInfo.StructureCategory.VILLAGE, 12);
        scoreThresholds.put(StructureInfo.StructureCategory.VILLAGE, 48);
    }

    private void addWeight(Block block, StructureInfo.StructureCategory category, int weight) {
        if (block == null || block == Blocks.AIR) return;
        Map<StructureInfo.StructureCategory, Integer> map = blockWeights.get(block);
        if (map == null) {
            map = new EnumMap<StructureInfo.StructureCategory, Integer>(StructureInfo.StructureCategory.class);
            blockWeights.put(block, map);
        }
        map.put(category, weight);
    }

    public void tick(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (recentNotifications) {
            while (!recentNotifications.isEmpty() && (now - recentNotifications.peekFirst().createdTimeMs) > 3500L) {
                recentNotifications.pollFirst();
            }
        }

        ModConfig config = ModConfig.get();
        if (!config.scanningEnabled) {
            return;
        }

        ClientWorld world = client.world;
        String dimId = world.getRegistryKey().getValue().toString();
        if (!dimId.equals(currentDimensionId)) {
            currentDimensionId = dimId;
            scannedChunkKeys.clear();
            pendingChunksQueue.clear();
            trackedTarget = null;
            lastPlayerChunkX = Integer.MIN_VALUE;
            lastPlayerChunkZ = Integer.MIN_VALUE;
        }

        int playerChunkX = ((int) Math.floor(client.player.getX())) >> 4;
        int playerChunkZ = ((int) Math.floor(client.player.getZ())) >> 4;
        int viewDistance = Math.max(2, client.options.viewDistance);

        if (playerChunkX != lastPlayerChunkX || playerChunkZ != lastPlayerChunkZ || viewDistance != lastViewDistance) {
            lastPlayerChunkX = playerChunkX;
            lastPlayerChunkZ = playerChunkZ;
            lastViewDistance = viewDistance;
            rebuildSpiralQueue(world, playerChunkX, playerChunkZ, viewDistance);
        }

        int budget = Math.max(1, config.chunksPerTickBudget);
        int dispatched = 0;

        while (dispatched < budget && !pendingChunksQueue.isEmpty()) {
            ChunkPos pos = pendingChunksQueue.pollFirst();
            if (pos == null) break;

            long key = pos.toLong();
            if (scannedChunkKeys.contains(key)) continue;
            if (!world.getChunkManager().isChunkLoaded(pos.x, pos.z)) continue;

            WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z, false);
            if (chunk == null || chunk.isEmpty()) continue;

            scannedChunkKeys.add(key);
            inspectChunkStructureStarts(chunk, dimId);

            final ChunkSnapshot snapshot = captureSnapshot(chunk, dimId);
            workerPool.submit(new Runnable() {
                @Override
                public void run() {
                    analyzeChunkSnapshotAsync(snapshot);
                }
            });
            dispatched++;
        }
    }

    private void rebuildSpiralQueue(ClientWorld world, int centerX, int centerZ, int viewDistance) {
        pendingChunksQueue.clear();
        for (int r = 0; r <= viewDistance; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = centerX + dx;
                    int cz = centerZ + dz;
                    long key = ChunkPos.toLong(cx, cz);
                    if (!scannedChunkKeys.contains(key) && world.getChunkManager().isChunkLoaded(cx, cz)) {
                        pendingChunksQueue.addLast(new ChunkPos(cx, cz));
                    }
                }
            }
        }
    }

    private void inspectChunkStructureStarts(WorldChunk chunk, String dimId) {
        Map<StructureFeature<?>, StructureStart<?>> starts = chunk.getStructureStarts();
        if (starts == null || starts.isEmpty()) return;

        for (Map.Entry<StructureFeature<?>, StructureStart<?>> entry : starts.entrySet()) {
            StructureFeature<?> feature = entry.getKey();
            StructureStart<?> start = entry.getValue();
            if (feature == null || start == null || !start.hasChildren()) continue;

            Identifier featureId = Registry.STRUCTURE_FEATURE.getId(feature);
            if (featureId == null) continue;

            StructureInfo.StructureCategory category = mapFeatureIdToCategory(featureId.getPath());
            if (category == null) continue;

            BlockBox box = start.getBoundingBox();
            if (box != null) {
                int cx = (box.minX + box.maxX) / 2;
                int cy = Math.max(1, (box.minY + box.maxY) / 2);
                int cz = (box.minZ + box.maxZ) / 2;
                recordStructureDiscovery(category, cx, cy, cz, dimId, "Synced Start");
            }
        }
    }

    private StructureInfo.StructureCategory mapFeatureIdToCategory(String path) {
        if (path == null) return null;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("stronghold")) return StructureInfo.StructureCategory.STRONGHOLD;
        if (lower.contains("fortress")) return StructureInfo.StructureCategory.NETHER_FORTRESS;
        if (lower.contains("bastion")) return StructureInfo.StructureCategory.BASTION_REMNANT;
        if (lower.contains("endcity") || lower.contains("end_city")) return StructureInfo.StructureCategory.END_CITY;
        if (lower.contains("monument")) return StructureInfo.StructureCategory.OCEAN_MONUMENT;
        if (lower.contains("mansion")) return StructureInfo.StructureCategory.WOODLAND_MANSION;
        if (lower.contains("desert_pyramid")) return StructureInfo.StructureCategory.DESERT_PYRAMID;
        if (lower.contains("jungle_pyramid")) return StructureInfo.StructureCategory.JUNGLE_TEMPLE;
        if (lower.contains("pillager_outpost")) return StructureInfo.StructureCategory.PILLAGER_OUTPOST;
        if (lower.contains("mineshaft")) return StructureInfo.StructureCategory.MINESHAFT;
        if (lower.contains("ruined_portal")) return StructureInfo.StructureCategory.RUINED_PORTAL;
        if (lower.contains("shipwreck") || lower.contains("ocean_ruin")) return StructureInfo.StructureCategory.SHIPWRECK;
        if (lower.contains("village")) return StructureInfo.StructureCategory.VILLAGE;
        if (lower.contains("igloo") || lower.contains("swamp_hut")) return StructureInfo.StructureCategory.IGLOO_OR_HUT;
        return null;
    }

    private ChunkSnapshot captureSnapshot(WorldChunk chunk, String dimId) {
        ChunkSection[] rawSections = chunk.getSectionArray();
        ChunkSection[] copy = new ChunkSection[rawSections.length];
        System.arraycopy(rawSections, 0, copy, 0, rawSections.length);

        List<SpawnerRecord> spawners = new ArrayList<SpawnerRecord>();
        Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();
        if (blockEntities != null && !blockEntities.isEmpty()) {
            for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
                BlockEntity be = entry.getValue();
                if (be instanceof MobSpawnerBlockEntity) {
                    MobSpawnerBlockEntity spawner = (MobSpawnerBlockEntity) be;
                    String entityName = "";
                    try {
                        if (spawner.getLogic().getRenderedEntity() != null) {
                            Identifier eid = Registry.ENTITY_TYPE.getId(spawner.getLogic().getRenderedEntity().getType());
                            if (eid != null) entityName = eid.toString();
                        }
                    } catch (Throwable ignored) {}
                    spawners.add(new SpawnerRecord(entry.getKey().toImmutable(), entityName));
                }
            }
        }
        return new ChunkSnapshot(chunk.getPos().x, chunk.getPos().z, dimId, copy, spawners);
    }

    private void analyzeChunkSnapshotAsync(ChunkSnapshot snapshot) {
        try {
            EnumMap<StructureInfo.StructureCategory, Integer> scores =
                    new EnumMap<StructureInfo.StructureCategory, Integer>(StructureInfo.StructureCategory.class);
            // Weighted coordinate accumulators for exact structure center calculation
            EnumMap<StructureInfo.StructureCategory, long[]> weightedSums =
                    new EnumMap<StructureInfo.StructureCategory, long[]>(StructureInfo.StructureCategory.class);

            for (SpawnerRecord spawner : snapshot.spawners) {
                String eid = spawner.entityId;
                StructureInfo.StructureCategory targetCat = StructureInfo.StructureCategory.DUNGEON;
                int bonus = 45;
                if (eid.contains("blaze")) {
                    targetCat = StructureInfo.StructureCategory.NETHER_FORTRESS;
                    bonus = 85;
                } else if (eid.contains("silverfish")) {
                    targetCat = StructureInfo.StructureCategory.STRONGHOLD;
                    bonus = 90;
                } else if (eid.contains("cave_spider")) {
                    targetCat = StructureInfo.StructureCategory.MINESHAFT;
                    bonus = 75;
                }
                scores.put(targetCat, bonus);
                weightedSums.put(targetCat, new long[]{
                        (long) spawner.pos.getX() * bonus,
                        (long) spawner.pos.getY() * bonus,
                        (long) spawner.pos.getZ() * bonus,
                        bonus
                });
            }

            int startWorldX = snapshot.chunkX << 4;
            int startWorldZ = snapshot.chunkZ << 4;

            ChunkSection[] sections = snapshot.sections;
            for (int secIdx = 0; secIdx < sections.length; secIdx++) {
                ChunkSection section = sections[secIdx];
                if (section == null || ChunkSection.isEmpty(section)) continue;
                int baseY = secIdx << 4;

                for (int ly = 0; ly < 16; ly++) {
                    int worldY = baseY + ly;
                    for (int lz = 0; lz < 16; lz++) {
                        for (int lx = 0; lx < 16; lx++) {
                            BlockState state = section.getBlockState(lx, ly, lz);
                            Block block = state.getBlock();
                            if (block == Blocks.AIR || block == Blocks.STONE || block == Blocks.NETHERRACK) continue;

                            Map<StructureInfo.StructureCategory, Integer> weights = blockWeights.get(block);
                            if (weights == null) continue;

                            int wx = startWorldX + lx;
                            int wz = startWorldZ + lz;

                            for (Map.Entry<StructureInfo.StructureCategory, Integer> entry : weights.entrySet()) {
                                StructureInfo.StructureCategory cat = entry.getKey();
                                if (!cat.matchesDimension(snapshot.dimensionId)) continue;
                                if ((cat == StructureInfo.StructureCategory.WOODLAND_MANSION
                                        || cat == StructureInfo.StructureCategory.PILLAGER_OUTPOST) && worldY < 55) {
                                    continue;
                                }

                                int w = entry.getValue();
                                Integer prev = scores.get(cat);
                                scores.put(cat, (prev == null ? 0 : prev) + w);

                                long[] acc = weightedSums.get(cat);
                                if (acc == null) {
                                    acc = new long[4];
                                    weightedSums.put(cat, acc);
                                }
                                acc[0] += (long) wx * w;
                                acc[1] += (long) worldY * w;
                                acc[2] += (long) wz * w;
                                acc[3] += w;
                            }
                        }
                    }
                }
            }

            for (Map.Entry<StructureInfo.StructureCategory, Integer> entry : scores.entrySet()) {
                StructureInfo.StructureCategory category = entry.getKey();
                int totalScore = entry.getValue();
                Integer threshold = scoreThresholds.get(category);
                if (threshold != null && totalScore >= threshold) {
                    long[] acc = weightedSums.get(category);
                    int centerX = startWorldX + 8;
                    int centerY = 64;
                    int centerZ = startWorldZ + 8;
                    if (acc != null && acc[3] > 0) {
                        centerX = (int) Math.round((double) acc[0] / (double) acc[3]);
                        centerY = (int) Math.round((double) acc[1] / (double) acc[3]);
                        centerZ = (int) Math.round((double) acc[2] / (double) acc[3]);
                    }
                    recordStructureDiscovery(category, centerX, centerY, centerZ, snapshot.dimensionId, "Block Scan");
                }
            }
        } catch (Throwable ignored) {}
    }

    public synchronized void recordStructureDiscovery(
            StructureInfo.StructureCategory category,
            int x,
            int y,
            int z,
            String dimensionId,
            String source
    ) {
        ModConfig config = ModConfig.get();
        double mergeRadius = Math.max(24, config.clusterMergeRadius);

        for (StructureInfo existing : discoveredStructures) {
            if (existing.getCategory() == category && existing.getDimension().equals(dimensionId)) {
                if (existing.getHorizontalDistanceTo(x, z) <= mergeRadius) {
                    return;
                }
            }
        }

        final StructureInfo info = new StructureInfo(
                category, x, y, z, dimensionId, System.currentTimeMillis(), source
        );
        discoveredStructures.add(info);

        if (trackedTarget == null && dimensionId.equals(currentDimensionId)) {
            trackedTarget = info;
        }

        if (!config.isCategoryEnabled(category)) {
            return;
        }

        synchronized (recentNotifications) {
            recentNotifications.addLast(new HudNotification(info, System.currentTimeMillis()));
            while (recentNotifications.size() > 2) {
                recentNotifications.pollFirst();
            }
        }

        if (config.chatAlertsEnabled) {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                client.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (client.player != null) {
                            int dist = (int) Math.round(info.getHorizontalDistanceTo(client.player.getX(), client.player.getZ()));
                            String msg = Formatting.AQUA + "[Radar] "
                                    + Formatting.YELLOW + info.getCategory().getDisplayName()
                                    + Formatting.GRAY + " at "
                                    + Formatting.GREEN + info.getCompactCoords()
                                    + Formatting.WHITE + " (" + dist + "m)";
                            client.player.sendMessage(new LiteralText(msg), false);
                        }
                    }
                });
            }
        }
    }

    public List<StructureInfo> getFilteredStructuresSorted(final double playerX, final double playerZ, StructureInfo.StructureCategory singleFilter) {
        ModConfig config = ModConfig.get();
        List<StructureInfo> result = new ArrayList<StructureInfo>();
        for (StructureInfo info : discoveredStructures) {
            if (!info.getDimension().equals(currentDimensionId)) continue;
            if (singleFilter != null && info.getCategory() != singleFilter) continue;
            if (singleFilter == null && !config.isCategoryEnabled(info.getCategory())) continue;
            result.add(info);
        }
        Collections.sort(result, new Comparator<StructureInfo>() {
            @Override
            public int compare(StructureInfo a, StructureInfo b) {
                return Double.compare(
                        a.getHorizontalDistanceTo(playerX, playerZ),
                        b.getHorizontalDistanceTo(playerX, playerZ)
                );
            }
        });
        return result;
    }

    /**
     * Returns the user-pinned structure if set in this dimension, or the closest detected structure.
     */
    public StructureInfo getPrimaryTarget(double playerX, double playerZ) {
        if (trackedTarget != null && trackedTarget.getDimension().equals(currentDimensionId)) {
            return trackedTarget;
        }
        List<StructureInfo> sorted = getFilteredStructuresSorted(playerX, playerZ, null);
        return sorted.isEmpty() ? null : sorted.get(0);
    }

    public StructureInfo getTrackedTarget() {
        return trackedTarget;
    }

    public void setTrackedTarget(StructureInfo target) {
        this.trackedTarget = target;
    }

    public List<HudNotification> getActiveHudNotifications() {
        synchronized (recentNotifications) {
            return new ArrayList<HudNotification>(recentNotifications);
        }
    }

    public void clearCache() {
        discoveredStructures.clear();
        scannedChunkKeys.clear();
        pendingChunksQueue.clear();
        trackedTarget = null;
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
    }

    public void rescanAroundPlayer() {
        scannedChunkKeys.clear();
        pendingChunksQueue.clear();
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
    }

    public int getScannedChunkCount() {
        return scannedChunkKeys.size();
    }
}
