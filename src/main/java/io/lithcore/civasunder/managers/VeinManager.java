package io.lithcore.civasunder.managers;

import io.lithcore.civasunder.CivAsunder;
import io.lithcore.civasunder.veins.CustomVein;
import io.lithcore.civasunder.veins.VeinGenerationEngine;
import io.lithcore.civasunder.veins.VeinRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class VeinManager {

    public static final int SEARCH_RADIUS_CHUNKS = 12;
    public static final int MAX_SEARCH_RESULTS = 3;
    private static final int MAX_EFFECTIVE_FATIGUE = 14;
    private static final int MAX_FATIGUE_HISTORY_SIZE = 250_000;
    private static final long FATIGUE_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L;

    private final CivAsunder plugin;
    private final VeinRegistry registry;
    private final NamespacedKey generatedKey;
    private final Map<Location, VeinBlockData> coolingDownVeins = new ConcurrentHashMap<>();
    private final Map<Location, Integer> veinFatigueHistory = new ConcurrentHashMap<>();
    private final Map<Location, Long> veinFatigueLastTouched = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Set<Location>> coolingVeinsByChunk = new ConcurrentHashMap<>();
    private final Set<ChunkKey> processedChunks = ConcurrentHashMap.newKeySet();
    private final PriorityQueue<RespawnEntry> respawnQueue = new PriorityQueue<>();
    private final AtomicBoolean fatigueTrimScheduled = new AtomicBoolean();

    public enum VeinType {
        COAL(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, 0.50, 90, 0, 120),
        IRON(Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, 0.55, 180, -30, 80),
        GOLD(Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, 0.65, 300, -64, 30),
        DIAMOND(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, 0.75, 600, -64, -10);

        public final Material normal;
        public final Material deepslate;
        public final double noiseThreshold;
        public final int baseRegenSeconds;
        public final int minY;
        public final int maxY;

        VeinType(Material normal, Material deepslate, double noiseThreshold, int baseRegenSeconds,
                 int minY, int maxY) {
            this.normal = normal;
            this.deepslate = deepslate;
            this.noiseThreshold = noiseThreshold;
            this.baseRegenSeconds = baseRegenSeconds;
            this.minY = minY;
            this.maxY = maxY;
        }

        public static VeinType fromMaterial(Material material) {
            for (VeinType type : values()) {
                if (type.normal == material || type.deepslate == material) return type;
            }
            return null;
        }
    }

    public static class VeinBlockData {
        public final Material oreMaterial;
        public final long respawnTimestamp;
        public final int fatigueCount;

        public VeinBlockData(Material oreMaterial, long respawnTimestamp, int fatigueCount) {
            this.oreMaterial = oreMaterial;
            this.respawnTimestamp = respawnTimestamp;
            this.fatigueCount = fatigueCount;
        }
    }

    public VeinManager(CivAsunder plugin) {
        this.plugin = plugin;
        this.registry = new VeinRegistry(plugin);
        this.generatedKey = new NamespacedKey(plugin, "veins_generated");
        loadTimersFromFile();
        loadFatigueFromFile();
        startRegenerationScheduler();
        startFatigueCleanupTask();
    }

    public void registerGeneratedChunk(Chunk chunk, CustomVein vein) {
        if (vein != null && !vein.blocks().isEmpty()) registry.register(chunk.getWorld().getUID(), vein);
        processedChunks.add(ChunkKey.of(chunk));
        markGeneratedAndSave(chunk);
    }

    /** Safe to call from the generation worker before the chunk becomes visible. */
    public void stageGeneratedChunk(UUID worldId, int chunkX, int chunkZ, CustomVein vein) {
        if (vein != null && !vein.blocks().isEmpty()) registry.register(worldId, vein);
        processedChunks.add(new ChunkKey(worldId, chunkX, chunkZ));
    }

    public boolean isRegisteredVeinBlock(UUID worldId, int x, int y, int z) {
        return registry.isRegistered(worldId, x, y, z);
    }

    public Set<Material> getRegisteredMaterials(Chunk chunk) {
        return registry.registeredMaterials(chunk);
    }

    public boolean isProcessedOrStaged(UUID worldId, int chunkX, int chunkZ) {
        return processedChunks.contains(new ChunkKey(worldId, chunkX, chunkZ));
    }

    public void loadChunk(Chunk chunk) {
        if (isProcessedChunk(chunk)) processedChunks.add(ChunkKey.of(chunk));
        if (!registry.load(chunk) && isProcessedChunk(chunk)) migrateLegacyChunk(chunk);
        restoreReadyVeins(chunk);
    }

    public void unloadChunk(Chunk chunk) {
        registry.save(chunk);
        registry.evict(chunk);
    }

    public boolean isProcessedChunk(Chunk chunk) {
        return chunk.getPersistentDataContainer().has(generatedKey, PersistentDataType.BYTE);
    }

    private void migrateLegacyChunk(Chunk chunk) {
        VeinGenerationEngine.VeinPlan plan = VeinGenerationEngine.plan(
                chunk.getWorld().getSeed(), chunk.getX(), chunk.getZ());
        if (plan != null) {
            List<CustomVein.VeinBlock> blocks = discoverLegacyBlocks(chunk, plan);
            if (!blocks.isEmpty()) {
                UUID id = UUID.nameUUIDFromBytes(("natural:" + chunk.getWorld().getUID() + ':'
                        + chunk.getX() + ':' + chunk.getZ()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                registry.register(chunk.getWorld().getUID(), new CustomVein(id, plan.type(),
                        plan.centerX(), plan.centerY(), plan.centerZ(), plan.type().baseRegenSeconds, blocks));
            }
        }
        registry.save(chunk);
    }

    /**
     * One-time compatibility import. It only probes deterministic positions owned by the old
     * generator and never classifies arbitrary ore blocks as veins.
     */
    private List<CustomVein.VeinBlock> discoverLegacyBlocks(Chunk chunk, VeinGenerationEngine.VeinPlan plan) {
        Random random = VeinGenerationEngine.chunkRandom(chunk.getWorld().getSeed(), chunk.getX(), chunk.getZ());
        random.nextInt(8);
        random.nextInt(8);
        random.nextInt(Math.abs(plan.type().maxY - plan.type().minY) + 1);
        int clusterSize = 20 + random.nextInt(31);
        int x = plan.centerX();
        int y = plan.centerY();
        int z = plan.centerZ();
        Set<String> seen = new java.util.HashSet<>();
        List<CustomVein.VeinBlock> found = new ArrayList<>();
        for (int i = 0; i < clusterSize * 2; i++) {
            if (y >= chunk.getWorld().getMinHeight() && y < chunk.getWorld().getMaxHeight()) {
                Material expected = y < 0 ? plan.type().deepslate : plan.type().normal;
                if (chunk.getWorld().getBlockAt(x, y, z).getType() == expected && seen.add(x + ":" + y + ":" + z)) {
                    found.add(new CustomVein.VeinBlock(x, y, z, expected));
                }
            }
            x += random.nextInt(3) - 1;
            y += random.nextInt(3) - 1;
            z += random.nextInt(3) - 1;
            if (Math.abs(x - plan.centerX()) > 4) x = plan.centerX() + random.nextInt(3) - 1;
            if (Math.abs(y - plan.centerY()) > 4) y = plan.centerY() + random.nextInt(3) - 1;
            if (Math.abs(z - plan.centerZ()) > 4) z = plan.centerZ() + random.nextInt(3) - 1;
        }
        return found;
    }

    private void markGeneratedAndSave(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        pdc.set(generatedKey, PersistentDataType.BYTE, (byte) 1);
        registry.save(chunk);
    }

    public int generateSingleCluster(Chunk chunk, VeinType forcedType) {
        CustomVein vein = VeinGenerationEngine.generateForced(chunk.getWorld(), UUID.randomUUID(), new Random(),
                forcedType, chunk.getWorld().getMinHeight(), chunk.getWorld().getMaxHeight(),
                chunk.getX(), chunk.getZ());
        if (!vein.blocks().isEmpty()) {
            registry.register(chunk.getWorld().getUID(), vein);
            markGeneratedAndSave(chunk);
        }
        return vein.blocks().size();
    }

    public boolean handleBlockBreak(Block block) {
        VeinRegistry.RegisteredBlock registered = registry.find(block);
        if (registered == null || block.getType() != registered.material()) return false;

        Location location = block.getLocation();
        int fatigue = Math.min(MAX_EFFECTIVE_FATIGUE, veinFatigueHistory.getOrDefault(location, 0) + 1);
        veinFatigueHistory.put(location, fatigue);
        veinFatigueLastTouched.put(location, System.currentTimeMillis());
        requestFatigueTrim();

        double fatigueMultiplier = Math.min(5.0, 1.0 + fatigue * 0.3);
        long delay = (long) (registered.vein().baseRegenSeconds() * fatigueMultiplier * 1000L);
        long respawnAt = System.currentTimeMillis() + delay;
        registerCooldown(location, new VeinBlockData(registered.material(), respawnAt, fatigue));

        block.breakNaturally();
        block.setType(Material.AIR, false);
        return true;
    }

    public boolean isCoolingDown(Location location) {
        return coolingDownVeins.containsKey(location);
    }

    private void startRegenerationScheduler() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            while (!respawnQueue.isEmpty() && respawnQueue.peek().timestamp <= now) {
                RespawnEntry ready = respawnQueue.poll();
                VeinBlockData data = coolingDownVeins.get(ready.location);
                if (data == null || data.respawnTimestamp != ready.timestamp) continue;
                if (isLocationChunkLoaded(ready.location)) restoreVein(ready.location, data);
            }
        }, 40L, 40L);
    }

    private void startFatigueCleanupTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long cutoff = System.currentTimeMillis() - FATIGUE_RETENTION_MILLIS;
            for (Map.Entry<Location, Long> entry : veinFatigueLastTouched.entrySet()) {
                Location location = entry.getKey();
                if (entry.getValue() < cutoff && !coolingDownVeins.containsKey(location)
                        && veinFatigueLastTouched.remove(location, entry.getValue())) {
                    veinFatigueHistory.remove(location);
                }
            }
            trimFatigueHistory();
        }, 72000L, 72000L);
    }

    private void trimFatigueHistory() {
        int excess = veinFatigueHistory.size() - MAX_FATIGUE_HISTORY_SIZE * 9 / 10;
        if (excess <= 0) return;
        List<Map.Entry<Location, Long>> oldest = new ArrayList<>(veinFatigueLastTouched.entrySet());
        oldest.sort(Map.Entry.comparingByValue());
        for (Map.Entry<Location, Long> entry : oldest) {
            if (excess <= 0) break;
            Location location = entry.getKey();
            if (coolingDownVeins.containsKey(location)) continue;
            if (veinFatigueLastTouched.remove(location, entry.getValue())) {
                veinFatigueHistory.remove(location);
                excess--;
            }
        }
    }

    private void requestFatigueTrim() {
        if (veinFatigueHistory.size() <= MAX_FATIGUE_HISTORY_SIZE
                || !fatigueTrimScheduled.compareAndSet(false, true)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                trimFatigueHistory();
            } finally {
                fatigueTrimScheduled.set(false);
            }
        });
    }

    private boolean isLocationChunkLoaded(Location location) {
        World world = location.getWorld();
        return world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private void restoreVein(Location location, VeinBlockData data) {
        if (!isLocationChunkLoaded(location)) return;
        if (!registry.isRegistered(location)) {
            VeinType type = VeinType.fromMaterial(data.oreMaterial);
            registry.registerLegacyBlock(location, data.oreMaterial,
                    type == null ? 60 : type.baseRegenSeconds);
        }
        location.getWorld().getBlockAt(location).setType(data.oreMaterial, false);
        coolingDownVeins.remove(location, data);
        removeFromChunkIndex(location);
    }

    public void restoreReadyVeins(Chunk chunk) {
        Set<Location> locations = coolingVeinsByChunk.get(ChunkKey.of(chunk));
        if (locations == null || locations.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Location location : new ArrayList<>(locations)) {
            VeinBlockData data = coolingDownVeins.get(location);
            if (data != null && data.respawnTimestamp <= now) restoreVein(location, data);
        }
    }

    private void registerCooldown(Location location, VeinBlockData data) {
        coolingDownVeins.put(location, data);
        coolingVeinsByChunk.computeIfAbsent(ChunkKey.of(location), ignored -> ConcurrentHashMap.newKeySet())
                .add(location);
        respawnQueue.add(new RespawnEntry(location, data.respawnTimestamp));
    }

    private void removeFromChunkIndex(Location location) {
        ChunkKey key = ChunkKey.of(location);
        Set<Location> locations = coolingVeinsByChunk.get(key);
        if (locations != null && locations.remove(location) && locations.isEmpty()) {
            coolingVeinsByChunk.remove(key, locations);
        }
    }

    public List<MapResult> findNearestVeins(Player player, int radiusChunks, int maxVeinsCount) {
        World world = player.getWorld();
        Location playerLocation = player.getLocation();
        int playerChunkX = playerLocation.getBlockX() >> 4;
        int playerChunkZ = playerLocation.getBlockZ() >> 4;
        List<SearchCandidate> candidates = new ArrayList<>();
        for (VeinRegistry.ActiveVein active : registry.findActiveVeins(world,
                playerChunkX - radiusChunks, playerChunkX + radiusChunks,
                playerChunkZ - radiusChunks, playerChunkZ + radiusChunks)) {
            CustomVein vein = active.vein();
            double diffX = vein.centerX() - playerLocation.getX();
            double diffY = vein.centerY() - playerLocation.getY();
            double selectionDistance = Math.sqrt(diffX * diffX + diffY * diffY);
            if (selectionDistance > radiusChunks * 16) continue;

            CustomVein.VeinBlock nearestBlock = null;
            double nearestDistanceSquared = Double.MAX_VALUE;
            for (CustomVein.VeinBlock block : active.activeBlocks()) {
                double blockX = block.x() + 0.5 - playerLocation.getX();
                double blockY = block.y() + 0.5 - playerLocation.getY();
                double blockZ = block.z() + 0.5 - playerLocation.getZ();
                double distanceSquared = blockX * blockX + blockY * blockY + blockZ * blockZ;
                if (distanceSquared < nearestDistanceSquared
                        || (distanceSquared == nearestDistanceSquared
                        && compareBlockCoordinates(block, nearestBlock) < 0)) {
                    nearestDistanceSquared = distanceSquared;
                    nearestBlock = block;
                }
            }
            if (nearestBlock == null) continue;
            String typeName = vein.type() == null ? nearestBlock.material().name() : vein.type().name();
            candidates.add(new SearchCandidate(vein.id(), typeName, selectionDistance,
                    vein.centerX(), vein.centerY(), nearestBlock, Math.sqrt(nearestDistanceSquared)));
        }

        candidates.sort(java.util.Comparator.comparingDouble(SearchCandidate::selectionDistance)
                .thenComparing(candidate -> candidate.id().toString()));
        List<SearchCandidate> filtered = new ArrayList<>();
        for (SearchCandidate candidate : candidates) {
            boolean duplicate = false;
            for (SearchCandidate accepted : filtered) {
                if (accepted.typeName.equals(candidate.typeName)) {
                    double dx = candidate.centerX - accepted.centerX;
                    double dy = candidate.centerY - accepted.centerY;
                    if (Math.sqrt(dx * dx + dy * dy) < 48.0) {
                        duplicate = true;
                        break;
                    }
                }
            }
            if (!duplicate) filtered.add(candidate);
        }
        return filtered.stream().limit(maxVeinsCount)
                .map(candidate -> new MapResult(candidate.typeName, candidate.blockDistance,
                        candidate.nearestBlock.x(), candidate.nearestBlock.y(), candidate.nearestBlock.z()))
                .toList();
    }

    private int compareBlockCoordinates(CustomVein.VeinBlock left, CustomVein.VeinBlock right) {
        if (right == null) return -1;
        int comparison = Integer.compare(left.x(), right.x());
        if (comparison != 0) return comparison;
        comparison = Integer.compare(left.y(), right.y());
        return comparison != 0 ? comparison : Integer.compare(left.z(), right.z());
    }

    public VeinGenerationEngine.VeinPlan getGenerationPlan(World world, int chunkX, int chunkZ) {
        return VeinGenerationEngine.plan(world.getSeed(), chunkX, chunkZ);
    }

    public void saveFatigueToFile() {
        File file = new File(plugin.getDataFolder(), "veins_fatigue.txt");
        long cutoff = System.currentTimeMillis() - FATIGUE_RETENTION_MILLIS;
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Location, Integer> entry : veinFatigueHistory.entrySet()) {
            Location location = entry.getKey();
            if (veinFatigueLastTouched.getOrDefault(location, 0L) < cutoff
                    && !coolingDownVeins.containsKey(location)) continue;
            lines.add(location.getWorld().getName() + "," + location.getBlockX() + ","
                    + location.getBlockY() + "," + location.getBlockZ() + ";" + entry.getValue());
        }
        plugin.getDataWriter().write(file.toPath(), java.nio.charset.Charset.defaultCharset(),
                () -> String.join(System.lineSeparator(), lines)
                        + (lines.isEmpty() ? "" : System.lineSeparator()));
    }

    public void loadFatigueFromFile() {
        File file = new File(plugin.getDataFolder(), "veins_fatigue.txt");
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] root = line.split(";");
                String[] locParts = root[0].split(",");
                World world = Bukkit.getWorld(locParts[0]);
                if (world == null) continue;
                Location location = new Location(world, Integer.parseInt(locParts[1]),
                        Integer.parseInt(locParts[2]), Integer.parseInt(locParts[3]));
                int fatigue = Math.min(MAX_EFFECTIVE_FATIGUE, Integer.parseInt(root[1]));
                if (veinFatigueHistory.size() < MAX_FATIGUE_HISTORY_SIZE) {
                    veinFatigueHistory.put(location, fatigue);
                    veinFatigueLastTouched.put(location, System.currentTimeMillis());
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().severe("[CivAsunder] Error loading fatigue: " + exception.getMessage());
        }
    }

    public void saveTimersToFile() {
        File file = new File(plugin.getDataFolder(), "veins_timers.txt");
        List<String> lines = new ArrayList<>(coolingDownVeins.size());
        for (Map.Entry<Location, VeinBlockData> entry : coolingDownVeins.entrySet()) {
            Location location = entry.getKey();
            VeinBlockData data = entry.getValue();
            lines.add(location.getWorld().getName() + "," + location.getBlockX() + ","
                    + location.getBlockY() + "," + location.getBlockZ() + ";"
                    + data.oreMaterial.name() + ";" + data.respawnTimestamp + ";" + data.fatigueCount);
        }
        plugin.getDataWriter().write(file.toPath(), java.nio.charset.Charset.defaultCharset(),
                () -> String.join(System.lineSeparator(), lines)
                        + (lines.isEmpty() ? "" : System.lineSeparator()));
    }

    public void loadTimersFromFile() {
        File file = new File(plugin.getDataFolder(), "veins_timers.txt");
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] root = line.split(";");
                String[] locParts = root[0].split(",");
                World world = Bukkit.getWorld(locParts[0]);
                if (world == null) continue;
                Location location = new Location(world, Integer.parseInt(locParts[1]),
                        Integer.parseInt(locParts[2]), Integer.parseInt(locParts[3]));
                Material material = Material.valueOf(root[1]);
                long timestamp = Long.parseLong(root[2]);
                int fatigue = Integer.parseInt(root[3]);
                int baseRegen = VeinType.fromMaterial(material) == null
                        ? 60 : VeinType.fromMaterial(material).baseRegenSeconds;
                registry.registerLegacyBlock(location, material, baseRegen);
                registerCooldown(location, new VeinBlockData(material, timestamp, fatigue));
            }
        } catch (Exception exception) {
            plugin.getLogger().severe("[CivAsunder] Error loading timers: " + exception.getMessage());
        }
    }

    public void cleanUp() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) registry.save(chunk);
        }
        saveTimersToFile();
        saveFatigueToFile();
        coolingDownVeins.clear();
        coolingVeinsByChunk.clear();
        respawnQueue.clear();
    }

    /** Compatibility view for integrations; recognition does not use this map. */
    public Map<Location, VeinBlockData> getCoolingDownVeins() {
        return coolingDownVeins;
    }

    private record RespawnEntry(Location location, long timestamp) implements Comparable<RespawnEntry> {
        @Override
        public int compareTo(RespawnEntry other) {
            return Long.compare(timestamp, other.timestamp);
        }
    }

    private record ChunkKey(UUID world, int x, int z) {
        private static ChunkKey of(Location location) {
            return new ChunkKey(location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }

        private static ChunkKey of(Chunk chunk) {
            return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }

    public static class MapResult {
        public String typeName;
        public double distance;
        public int geoX;
        public int geoY;
        public int geoZ;

        public MapResult(String typeName, double distance, int geoX, int geoY, int geoZ) {
            this.typeName = typeName;
            this.distance = distance;
            this.geoX = geoX;
            this.geoY = geoY;
            this.geoZ = geoZ;
        }
    }

    private record SearchCandidate(UUID id, String typeName, double selectionDistance, int centerX, int centerY,
                                   CustomVein.VeinBlock nearestBlock, double blockDistance) {}
}
