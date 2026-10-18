package com.cavemapplus.scanner;

import com.cavemapplus.scanner.DetectedStructure.StructureType;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * High-precision block cluster pattern matcher for Minecraft 1.21.11 structures:
 * - Trial Chamber: Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.HEAVY_CORE, and copper/tuff brick clusters (Y = -40..0)
 * - Ancient City: Blocks.REINFORCED_DEEPSLATE and Blocks.SCULK_SHRIEKER clusters around Y = -52
 * - Nether Fortress: Blocks.NETHER_BRICKS and Blocks.SPAWNER with Blaze entity data
 * - Ocean Monument: Blocks.DARK_PRISMARINE and Blocks.SEA_LANTERN
 * - End City: Blocks.PURPUR_BLOCK and Blocks.END_STONE_BRICKS
 */
public final class StructureDetector {

    public record SpawnerSnapshot(BlockPos pos, String entityId) {}

    private static final Set<Block> TRIAL_COPPER_TUFF_BLOCKS = Set.of(
            Blocks.TUFF_BRICKS,
            Blocks.CHISELED_TUFF_BRICKS,
            Blocks.POLISHED_TUFF,
            Blocks.CHISELED_TUFF,
            Blocks.WAXED_COPPER_BLOCK,
            Blocks.WAXED_CUT_COPPER,
            Blocks.WAXED_OXIDIZED_COPPER,
            Blocks.WAXED_OXIDIZED_CUT_COPPER,
            Blocks.WAXED_COPPER_BULB,
            Blocks.WAXED_OXIDIZED_COPPER_BULB,
            Blocks.WAXED_OXIDIZED_CUT_COPPER_GRATE,
            Blocks.WAXED_COPPER_GRATE
    );

    private final List<DetectedStructure> detectedStructures = new CopyOnWriteArrayList<>();
    private final EnumSet<StructureType> enabledFilters = EnumSet.allOf(StructureType.class);

    public StructureDetector() {
        // Verify modern registry lookup via Registries.BLOCK and Identifier.of()
        Block trialSpawner = Registries.BLOCK.get(Identifier.of("minecraft", "trial_spawner"));
        if (trialSpawner == Blocks.AIR) {
            throw new IllegalStateException("Expected Minecraft 1.21+ block registry with minecraft:trial_spawner");
        }
    }

    /**
     * Captures MobSpawnerBlockEntity data on the client thread before background section scanning.
     */
    public static List<SpawnerSnapshot> captureSpawners(WorldChunk chunk) {
        List<SpawnerSnapshot> list = new ArrayList<>();
        Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();
        if (blockEntities == null || blockEntities.isEmpty()) {
            return list;
        }
        for (Map.Entry<BlockPos, BlockEntity> entry : blockEntities.entrySet()) {
            if (entry.getValue() instanceof MobSpawnerBlockEntity spawner) {
                String entityId = "";
                try {
                    var renderedEntity = spawner.getLogic().getRenderedEntity(chunk.getWorld(), entry.getKey());
                    if (renderedEntity != null) {
                        Identifier id = Registries.ENTITY_TYPE.getId(renderedEntity.getType());
                        if (id != null) {
                            entityId = id.toString();
                        }
                    }
                } catch (Throwable ignored) {
                }
                list.add(new SpawnerSnapshot(entry.getKey().toImmutable(), entityId));
            }
        }
        return list;
    }

    /**
     * Runs on the background thread executor to detect structure block clusters in a chunk snapshot.
     */
    public void analyzeChunkSections(
            int chunkX,
            int chunkZ,
            int bottomY,
            String dimensionId,
            ChunkSection[] sections,
            List<SpawnerSnapshot> spawners
    ) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;

        // 1. Trial Chamber counters (Y = -40 to 0)
        int trialSpawners = 0;
        int vaults = 0;
        int heavyCores = 0;
        int copperTuffClusterCount = 0;
        BlockPos trialAnchor = null;

        // 2. Ancient City counters (around Y = -52, range -60 to -15)
        int reinforcedDeepslate = 0;
        int sculkShriekers = 0;
        int sculkCatalysts = 0;
        int deepslateTiles = 0;
        BlockPos ancientCityAnchor = null;

        // 3. Nether Fortress counters
        int netherBricks = 0;
        int netherBrickFences = 0;
        int blazeSpawners = 0;
        BlockPos fortressAnchor = null;

        // 4. Ocean Monument counters
        int darkPrismarine = 0;
        int prismarineBricks = 0;
        int seaLanterns = 0;
        int wetSponges = 0;
        BlockPos monumentAnchor = null;

        // 5. End City counters
        int purpurBlocks = 0;
        int endStoneBricks = 0;
        int endRods = 0;
        BlockPos endCityAnchor = null;

        // Check captured spawners for Blaze data
        for (SpawnerSnapshot spawner : spawners) {
            if (spawner.entityId().contains("blaze")) {
                blazeSpawners++;
                fortressAnchor = spawner.pos();
            }
        }

        for (int secIdx = 0; secIdx < sections.length; secIdx++) {
            ChunkSection section = sections[secIdx];
            if (section == null || section.isEmpty()) {
                continue;
            }
            int sectionBaseY = bottomY + (secIdx << 4);

            for (int ly = 0; ly < 16; ly++) {
                int worldY = sectionBaseY + ly;
                for (int lz = 0; lz < 16; lz++) {
                    int worldZ = startZ + lz;
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        Block block = state.getBlock();
                        if (block == Blocks.AIR || block == Blocks.STONE || block == Blocks.DEEPSLATE
                                || block == Blocks.NETHERRACK || block == Blocks.WATER) {
                            continue;
                        }
                        int worldX = startX + lx;

                        // Trial Chamber signature check (Y = -40 to 0)
                        if (worldY >= -45 && worldY <= 5) {
                            if (block == Blocks.TRIAL_SPAWNER) {
                                trialSpawners++;
                                if (trialAnchor == null) trialAnchor = new BlockPos(worldX, worldY, worldZ);
                            } else if (block == Blocks.VAULT) {
                                vaults++;
                                trialAnchor = new BlockPos(worldX, worldY, worldZ);
                            } else if (block == Blocks.HEAVY_CORE) {
                                heavyCores++;
                                trialAnchor = new BlockPos(worldX, worldY, worldZ);
                            } else if (TRIAL_COPPER_TUFF_BLOCKS.contains(block)) {
                                copperTuffClusterCount++;
                                if (trialAnchor == null) trialAnchor = new BlockPos(worldX, worldY, worldZ);
                            }
                        }

                        // Ancient City signature check (around Y = -52)
                        if (worldY >= -62 && worldY <= -15) {
                            if (block == Blocks.REINFORCED_DEEPSLATE) {
                                reinforcedDeepslate++;
                                ancientCityAnchor = new BlockPos(worldX, worldY, worldZ);
                            } else if (block == Blocks.SCULK_SHRIEKER) {
                                sculkShriekers++;
                                if (ancientCityAnchor == null) ancientCityAnchor = new BlockPos(worldX, worldY, worldZ);
                            } else if (block == Blocks.SCULK_CATALYST) {
                                sculkCatalysts++;
                            } else if (block == Blocks.DEEPSLATE_TILES || block == Blocks.DEEPSLATE_BRICKS) {
                                deepslateTiles++;
                            }
                        }

                        // Nether Fortress signature check
                        if (block == Blocks.NETHER_BRICKS) {
                            netherBricks++;
                            if (fortressAnchor == null) fortressAnchor = new BlockPos(worldX, worldY, worldZ);
                        } else if (block == Blocks.NETHER_BRICK_FENCE) {
                            netherBrickFences++;
                            if (fortressAnchor == null) fortressAnchor = new BlockPos(worldX, worldY, worldZ);
                        } else if (block == Blocks.SPAWNER && "minecraft:the_nether".equals(dimensionId)) {
                            blazeSpawners++;
                            fortressAnchor = new BlockPos(worldX, worldY, worldZ);
                        }

                        // Ocean Monument signature check
                        if (block == Blocks.DARK_PRISMARINE) {
                            darkPrismarine++;
                            if (monumentAnchor == null) monumentAnchor = new BlockPos(worldX, worldY, worldZ);
                        } else if (block == Blocks.PRISMARINE_BRICKS) {
                            prismarineBricks++;
                        } else if (block == Blocks.SEA_LANTERN) {
                            seaLanterns++;
                            if (monumentAnchor == null) monumentAnchor = new BlockPos(worldX, worldY, worldZ);
                        } else if (block == Blocks.WET_SPONGE) {
                            wetSponges++;
                            monumentAnchor = new BlockPos(worldX, worldY, worldZ);
                        }

                        // End City signature check
                        if (block == Blocks.PURPUR_BLOCK || block == Blocks.PURPUR_PILLAR) {
                            purpurBlocks++;
                            if (endCityAnchor == null) endCityAnchor = new BlockPos(worldX, worldY, worldZ);
                        } else if (block == Blocks.END_STONE_BRICKS) {
                            endStoneBricks++;
                        } else if (block == Blocks.END_ROD || block == Blocks.DRAGON_HEAD || block == Blocks.DRAGON_WALL_HEAD) {
                            endRods++;
                            endCityAnchor = new BlockPos(worldX, worldY, worldZ);
                        }
                    }
                }
            }
        }

        // Evaluate Trial Chamber
        if ("minecraft:overworld".equals(dimensionId) && trialAnchor != null) {
            if (trialSpawners >= 1 || vaults >= 1 || heavyCores >= 1 || copperTuffClusterCount >= 48) {
                int priority = (vaults > 0 || heavyCores > 0) ? 100 : (trialSpawners > 0 ? 90 : 65);
                String detail = String.format("Spawners: %d, Vaults: %d, Tuff/Copper: %d",
                        trialSpawners, vaults, copperTuffClusterCount);
                registerOrRefine(StructureType.TRIAL_CHAMBER, trialAnchor, dimensionId, detail, priority);
            }
        }

        // Evaluate Ancient City
        if ("minecraft:overworld".equals(dimensionId) && ancientCityAnchor != null) {
            if (reinforcedDeepslate >= 2 || (sculkShriekers >= 2 && deepslateTiles >= 25)
                    || (sculkShriekers >= 3 && sculkCatalysts >= 1)) {
                int priority = reinforcedDeepslate >= 2 ? 100 : 80;
                String detail = String.format("Reinforced Deepslate: %d, Shriekers: %d",
                        reinforcedDeepslate, sculkShriekers);
                registerOrRefine(StructureType.ANCIENT_CITY, ancientCityAnchor, dimensionId, detail, priority);
            }
        }

        // Evaluate Nether Fortress
        if ("minecraft:the_nether".equals(dimensionId) && fortressAnchor != null) {
            if (blazeSpawners >= 1 || (netherBricks >= 55 && netherBrickFences >= 6)) {
                int priority = blazeSpawners >= 1 ? 100 : 75;
                String detail = blazeSpawners >= 1
                        ? "Blaze Spawner + " + netherBricks + " Nether Bricks"
                        : netherBricks + " Nether Bricks, " + netherBrickFences + " Fences";
                registerOrRefine(StructureType.NETHER_FORTRESS, fortressAnchor, dimensionId, detail, priority);
            }
        }

        // Evaluate Ocean Monument
        if ("minecraft:overworld".equals(dimensionId) && monumentAnchor != null) {
            if ((darkPrismarine >= 12 && seaLanterns >= 4)
                    || (wetSponges >= 1 && (darkPrismarine + prismarineBricks) >= 20)
                    || ((darkPrismarine + prismarineBricks) >= 50 && seaLanterns >= 5)) {
                int priority = wetSponges >= 1 ? 100 : 85;
                String detail = String.format("Dark Prismarine: %d, Sea Lanterns: %d",
                        darkPrismarine, seaLanterns);
                registerOrRefine(StructureType.OCEAN_MONUMENT, monumentAnchor, dimensionId, detail, priority);
            }
        }

        // Evaluate End City
        if ("minecraft:the_end".equals(dimensionId) && endCityAnchor != null) {
            if (purpurBlocks >= 20 && (endStoneBricks >= 12 || endRods >= 2)) {
                int priority = endRods >= 2 ? 95 : 80;
                String detail = String.format("Purpur: %d, End Stone Bricks: %d",
                        purpurBlocks, endStoneBricks);
                registerOrRefine(StructureType.END_CITY, endCityAnchor, dimensionId, detail, priority);
            }
        }
    }

    private synchronized void registerOrRefine(
            StructureType type,
            BlockPos pos,
            String dimensionId,
            String detail,
            int priority
    ) {
        double mergeRadius = type.clusterRadiusBlocks();
        for (int i = 0; i < detectedStructures.size(); i++) {
            DetectedStructure existing = detectedStructures.get(i);
            if (existing.type() == type && existing.dimensionId().equals(dimensionId)) {
                if (existing.horizontalDistanceTo(pos.getX(), pos.getZ()) <= mergeRadius) {
                    if (priority > existing.anchorPriority()) {
                        detectedStructures.set(i, new DetectedStructure(
                                type, pos, dimensionId, existing.discoveredAtEpochMs(), detail, priority
                        ));
                    }
                    return;
                }
            }
        }

        detectedStructures.add(new DetectedStructure(
                type, pos, dimensionId, System.currentTimeMillis(), detail, priority
        ));
    }

    public List<DetectedStructure> getVisibleStructures(
            String dimensionId,
            int playerChunkX,
            int playerChunkZ,
            int viewDistanceChunks
    ) {
        List<DetectedStructure> result = new ArrayList<>();
        for (DetectedStructure s : detectedStructures) {
            if (!s.dimensionId().equals(dimensionId)) continue;
            if (!enabledFilters.contains(s.type())) continue;
            if (!s.isWithinViewDistance(playerChunkX, playerChunkZ, viewDistanceChunks)) continue;
            result.add(s);
        }
        return result;
    }

    public synchronized void pruneOutsideViewDistance(String dimensionId, int playerChunkX, int playerChunkZ, int viewDistanceChunks) {
        detectedStructures.removeIf(s ->
                !s.dimensionId().equals(dimensionId)
                        || !s.isWithinViewDistance(playerChunkX, playerChunkZ, viewDistanceChunks)
        );
    }

    public boolean isFilterEnabled(StructureType type) {
        return enabledFilters.contains(type);
    }

    public void toggleFilter(StructureType type) {
        if (enabledFilters.contains(type)) {
            enabledFilters.remove(type);
        } else {
            enabledFilters.add(type);
        }
    }

    public void clearAll() {
        detectedStructures.clear();
    }
}
