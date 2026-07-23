package io.lithcore.civasunder.listeners;

import io.lithcore.civasunder.CivAsunder;
import io.lithcore.civasunder.managers.VeinManager;
import io.lithcore.civasunder.veins.CustomVein;
import io.lithcore.civasunder.veins.VeinGenerationEngine;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs as part of the chunk generation pipeline. Vanilla ores are captured,
 * custom veins are generated against the original terrain, and only then are
 * the captured vanilla blocks replaced. The chunk cannot become visible in an
 * intermediate state.
 */
public class OrePreventionListener extends BlockPopulator implements Listener {

    // The largest vanilla 1.20.4 ore feature cannot reach farther than five blocks into a neighbour.
    private static final int WORLDGEN_BORDER_BLOCKS = 5;
    private static final Set<Material> VANILLA_ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE,
            Material.ANCIENT_DEBRIS
    );

    private final CivAsunder plugin;
    private final VeinManager veinManager;
    private final Map<ChunkKey, CustomVein> generatedVeins = new ConcurrentHashMap<>();

    public OrePreventionListener(CivAsunder plugin, VeinManager veinManager) {
        this.plugin = plugin;
        this.veinManager = veinManager;
    }

    public void install() {
        for (World world : Bukkit.getWorlds()) {
            install(world);
            for (Chunk chunk : world.getLoadedChunks()) {
                veinManager.loadChunk(chunk);
                if (veinManager.isProcessedChunk(chunk)) auditChunkIfNeeded(chunk);
            }
        }
    }

    private void install(World world) {
        if (!world.getPopulators().contains(this)) world.getPopulators().add(this);
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        install(event.getWorld());
    }

    @Override
    public void populate(WorldInfo worldInfo, Random ignored, int chunkX, int chunkZ, LimitedRegion region) {
        CustomVein vein = process(worldInfo.getUID(), worldInfo.getSeed(), worldInfo.getEnvironment(),
                worldInfo.getMinHeight(), worldInfo.getMaxHeight(), chunkX, chunkZ, region);
        generatedVeins.put(new ChunkKey(worldInfo.getUID(), chunkX, chunkZ), vein == null ? EmptyVein.VALUE : vein);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        veinManager.loadChunk(event.getChunk());
        if (veinManager.isProcessedChunk(event.getChunk())) auditChunkIfNeeded(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkPopulate(ChunkPopulateEvent event) {
        Chunk chunk = event.getChunk();
        CustomVein vein = generatedVeins.remove(ChunkKey.of(chunk));
        if (vein == null) {
            plugin.getLogger().warning("[CivAsunder] Vein populator result was unavailable for chunk "
                    + chunk.getX() + ',' + chunk.getZ() + "; applying safe generation fallback");
            vein = process(chunk.getWorld().getUID(), chunk.getWorld().getSeed(),
                    chunk.getWorld().getEnvironment(), chunk.getWorld().getMinHeight(),
                    chunk.getWorld().getMaxHeight(), chunk.getX(), chunk.getZ(), chunk.getWorld());
        }
        veinManager.registerGeneratedChunk(chunk, vein == EmptyVein.VALUE ? null : vein);
        auditChunkIfNeeded(chunk);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        veinManager.unloadChunk(event.getChunk());
    }

    private CustomVein process(UUID worldId, long seed, World.Environment environment,
                               int minHeight, int maxHeight, int chunkX, int chunkZ,
                               org.bukkit.RegionAccessor region) {
        int border = region instanceof LimitedRegion ? WORLDGEN_BORDER_BLOCKS : 0;
        List<OrePosition> vanilla = environment == World.Environment.THE_END
                ? List.of() : findVanillaOres(region, environment, minHeight, maxHeight,
                worldId, chunkX, chunkZ, border);

        // This order deliberately preserves the old generator's eligibility decisions.
        CustomVein vein = VeinGenerationEngine.generateNatural(region, worldId, seed,
                minHeight, maxHeight, chunkX, chunkZ);
        veinManager.stageGeneratedChunk(worldId, chunkX, chunkZ, vein);

        for (OrePosition position : vanilla) {
            if (!veinManager.isRegisteredVeinBlock(worldId, position.x, position.y, position.z)
                    && VANILLA_ORES.contains(region.getType(position.x, position.y, position.z))) {
                region.setType(position.x, position.y, position.z,
                        position.y < 0 ? Material.DEEPSLATE : Material.STONE);
            }
        }
        return vein;
    }

    private List<OrePosition> findVanillaOres(org.bukkit.RegionAccessor region, World.Environment environment,
                                              int minHeight, int maxHeight, UUID worldId,
                                              int chunkX, int chunkZ,
                                              int border) {
        int startX = (chunkX << 4) - border;
        int startZ = (chunkZ << 4) - border;
        int endX = (chunkX << 4) + 16 + border;
        int endZ = (chunkZ << 4) + 16 + border;
        int oreMinY = environment == World.Environment.NETHER ? Math.max(minHeight, 0) : minHeight;
        int oreMaxY = environment == World.Environment.NETHER ? Math.min(maxHeight, 128) : maxHeight;
        List<OrePosition> ores = new ArrayList<>();
        for (int x = startX; x < endX; x++) {
            for (int z = startZ; z < endZ; z++) {
                if (region instanceof LimitedRegion limited && !limited.isInRegion(x, oreMinY, z)) continue;
                int ownerChunkX = x >> 4;
                int ownerChunkZ = z >> 4;
                if ((ownerChunkX != chunkX || ownerChunkZ != chunkZ)
                        && !veinManager.isProcessedOrStaged(worldId, ownerChunkX, ownerChunkZ)) continue;
                // Height maps avoid walking empty sky while retaining every possible ore block.
                int columnMaxY = Math.min(oreMaxY, region.getHighestBlockYAt(x, z) + 1);
                for (int y = oreMinY; y < columnMaxY; y++) {
                    if (VANILLA_ORES.contains(region.getType(x, y, z))) ores.add(new OrePosition(x, y, z));
                }
            }
        }
        return ores;
    }

    /**
     * Palette checks make the main-thread verification effectively O(materials) in the normal case.
     * A block walk is only used if an unregistered ore material is actually present.
     */
    private void auditChunkIfNeeded(Chunk chunk) {
        if (chunk.getWorld().getEnvironment() == World.Environment.THE_END) return;
        Set<Material> registeredMaterials = veinManager.getRegisteredMaterials(chunk);
        boolean suspicious = false;
        for (Material material : VANILLA_ORES) {
            if (!registeredMaterials.contains(material) && chunk.contains(material.createBlockData())) {
                suspicious = true;
                break;
            }
        }
        if (!suspicious) return;

        World world = chunk.getWorld();
        int minY = world.getEnvironment() == World.Environment.NETHER ? Math.max(world.getMinHeight(), 0)
                : world.getMinHeight();
        int maxY = world.getEnvironment() == World.Environment.NETHER ? Math.min(world.getMaxHeight(), 128)
                : world.getMaxHeight();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int columnMaxY = Math.min(maxY, world.getHighestBlockYAt(
                        (chunk.getX() << 4) + localX, (chunk.getZ() << 4) + localZ) + 1);
                for (int y = minY; y < columnMaxY; y++) {
                    org.bukkit.block.Block block = chunk.getBlock(localX, y, localZ);
                    if (VANILLA_ORES.contains(block.getType())
                            && !veinManager.isRegisteredVeinBlock(world.getUID(),
                            block.getX(), block.getY(), block.getZ())) {
                        block.setType(y < 0 ? Material.DEEPSLATE : Material.STONE, false);
                    }
                }
            }
        }
    }

    private record OrePosition(int x, int y, int z) {}

    private record ChunkKey(UUID world, int x, int z) {
        private static ChunkKey of(Chunk chunk) {
            return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }

    /** Sentinel because ConcurrentHashMap does not accept null values. */
    private static final class EmptyVein {
        private static final CustomVein VALUE = new CustomVein(new UUID(0, 0), null,
                0, 0, 0, 60, List.of());
    }
}
