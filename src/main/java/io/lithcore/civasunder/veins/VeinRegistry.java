package io.lithcore.civasunder.veins;

import io.lithcore.civasunder.CivAsunder;
import io.lithcore.civasunder.managers.VeinManager;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Authoritative index and persistent ownership marker for custom vein blocks. */
public final class VeinRegistry {

    private static final int FORMAT_MAGIC = 0x43564E31; // CVN1
    private final CivAsunder plugin;
    private final NamespacedKey dataKey;
    private final Map<BlockKey, RegisteredBlock> blocks = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Set<BlockKey>> blocksByChunk = new ConcurrentHashMap<>();

    public VeinRegistry(CivAsunder plugin) {
        this.plugin = plugin;
        this.dataKey = new NamespacedKey(plugin, "vein_registry_v1");
    }

    public void register(UUID worldId, CustomVein vein) {
        for (CustomVein.VeinBlock block : vein.blocks()) {
            BlockKey key = new BlockKey(worldId, block.x(), block.y(), block.z());
            RegisteredBlock registered = new RegisteredBlock(vein, block.material());
            blocks.put(key, registered);
            blocksByChunk.computeIfAbsent(ChunkKey.of(key), ignored -> ConcurrentHashMap.newKeySet()).add(key);
        }
    }

    public RegisteredBlock find(Block block) {
        return blocks.get(BlockKey.of(block.getLocation()));
    }

    public RegisteredBlock find(Location location) {
        return blocks.get(BlockKey.of(location));
    }

    public boolean isRegistered(Location location) {
        return find(location) != null;
    }

    public boolean isRegistered(UUID worldId, int x, int y, int z) {
        return blocks.containsKey(new BlockKey(worldId, x, y, z));
    }

    public Set<Material> registeredMaterials(Chunk chunk) {
        Set<BlockKey> keys = blocksByChunk.get(ChunkKey.of(chunk));
        if (keys == null) return Set.of();
        Set<Material> materials = new HashSet<>();
        for (BlockKey key : keys) {
            RegisteredBlock registered = blocks.get(key);
            if (registered != null) materials.add(registered.material);
        }
        return materials;
    }

    /** Returns only currently loaded and physically present ore blocks. */
    public List<ActiveVein> findActiveVeins(World world, int minChunkX, int maxChunkX,
                                            int minChunkZ, int maxChunkZ) {
        Map<UUID, ActiveVeinBuilder> grouped = new HashMap<>();
        UUID worldId = world.getUID();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
                Set<BlockKey> keys = blocksByChunk.get(new ChunkKey(worldId, chunkX, chunkZ));
                if (keys == null) continue;
                for (BlockKey key : keys) {
                    RegisteredBlock registered = blocks.get(key);
                    if (registered == null) continue;
                    Block block = world.getBlockAt(key.x, key.y, key.z);
                    if (block.getType() != registered.material) continue;
                    grouped.computeIfAbsent(registered.vein.id(),
                                    ignored -> new ActiveVeinBuilder(registered.vein))
                            .blocks.add(registered.veinBlock(key));
                }
            }
        }
        return grouped.values().stream()
                .filter(builder -> !builder.blocks.isEmpty())
                .map(builder -> new ActiveVein(builder.vein, List.copyOf(builder.blocks)))
                .toList();
    }

    public void registerLegacyBlock(Location location, Material material, int baseRegenSeconds) {
        VeinManager.VeinType type = VeinManager.VeinType.fromMaterial(material);
        UUID id = UUID.nameUUIDFromBytes(("legacy:" + location.getWorld().getUID() + ':'
                + location.getBlockX() + ':' + location.getBlockY() + ':' + location.getBlockZ())
                .getBytes(StandardCharsets.UTF_8));
        CustomVein vein = new CustomVein(id, type, location.getBlockX(), location.getBlockY(),
                location.getBlockZ(), baseRegenSeconds,
                List.of(new CustomVein.VeinBlock(location.getBlockX(), location.getBlockY(),
                        location.getBlockZ(), material)));
        register(location.getWorld().getUID(), vein);
    }

    public boolean load(Chunk chunk) {
        byte[] encoded = chunk.getPersistentDataContainer().get(dataKey, PersistentDataType.BYTE_ARRAY);
        if (encoded == null) return false;
        evict(chunk);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != FORMAT_MAGIC) throw new IOException("unsupported registry format");
            int veinCount = input.readInt();
            if (veinCount < 0 || veinCount > 4096) throw new IOException("invalid vein count: " + veinCount);
            for (int i = 0; i < veinCount; i++) {
                UUID id = new UUID(input.readLong(), input.readLong());
                String typeName = input.readUTF();
                VeinManager.VeinType type = typeName.isEmpty() ? null : VeinManager.VeinType.valueOf(typeName);
                int centerX = input.readInt();
                int centerY = input.readInt();
                int centerZ = input.readInt();
                int baseRegen = input.readInt();
                int blockCount = input.readInt();
                if (blockCount < 0 || blockCount > 65536) throw new IOException("invalid block count: " + blockCount);
                List<CustomVein.VeinBlock> veinBlocks = new ArrayList<>(blockCount);
                for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
                    int x = input.readInt();
                    int y = input.readInt();
                    int z = input.readInt();
                    Material material = Material.valueOf(input.readUTF());
                    veinBlocks.add(new CustomVein.VeinBlock(x, y, z, material));
                }
                register(chunk.getWorld().getUID(), new CustomVein(id, type, centerX, centerY, centerZ,
                        baseRegen, veinBlocks));
            }
            return true;
        } catch (Exception exception) {
            evict(chunk);
            plugin.getLogger().severe("[CivAsunder] Cannot load vein registry for chunk "
                    + chunk.getX() + ',' + chunk.getZ() + ": " + exception.getMessage());
            return false;
        }
    }

    public void save(Chunk chunk) {
        Collection<CustomVein> veins = veinsIn(chunk);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(FORMAT_MAGIC);
            output.writeInt(veins.size());
            for (CustomVein vein : veins) {
                output.writeLong(vein.id().getMostSignificantBits());
                output.writeLong(vein.id().getLeastSignificantBits());
                output.writeUTF(vein.type() == null ? "" : vein.type().name());
                output.writeInt(vein.centerX());
                output.writeInt(vein.centerY());
                output.writeInt(vein.centerZ());
                output.writeInt(vein.baseRegenSeconds());
                List<CustomVein.VeinBlock> ownedBlocks = vein.blocks().stream()
                        .filter(block -> (block.x() >> 4) == chunk.getX() && (block.z() >> 4) == chunk.getZ())
                        .toList();
                output.writeInt(ownedBlocks.size());
                for (CustomVein.VeinBlock block : ownedBlocks) {
                    output.writeInt(block.x());
                    output.writeInt(block.y());
                    output.writeInt(block.z());
                    output.writeUTF(block.material().name());
                }
            }
            chunk.getPersistentDataContainer().set(dataKey, PersistentDataType.BYTE_ARRAY, bytes.toByteArray());
        } catch (IOException exception) {
            plugin.getLogger().severe("[CivAsunder] Cannot save vein registry for chunk "
                    + chunk.getX() + ',' + chunk.getZ() + ": " + exception.getMessage());
        }
    }

    public void evict(Chunk chunk) {
        Set<BlockKey> keys = blocksByChunk.remove(ChunkKey.of(chunk));
        if (keys != null) keys.forEach(blocks::remove);
    }

    private Collection<CustomVein> veinsIn(Chunk chunk) {
        Set<BlockKey> keys = blocksByChunk.get(ChunkKey.of(chunk));
        if (keys == null) return List.of();
        Set<UUID> seen = new HashSet<>();
        List<CustomVein> result = new ArrayList<>();
        for (BlockKey key : keys) {
            RegisteredBlock registered = blocks.get(key);
            if (registered != null && seen.add(registered.vein.id())) result.add(registered.vein);
        }
        return result;
    }

    public record RegisteredBlock(CustomVein vein, Material material) {
        private CustomVein.VeinBlock veinBlock(BlockKey key) {
            return new CustomVein.VeinBlock(key.x, key.y, key.z, material);
        }
    }

    public record ActiveVein(CustomVein vein, List<CustomVein.VeinBlock> activeBlocks) {}

    private static final class ActiveVeinBuilder {
        private final CustomVein vein;
        private final List<CustomVein.VeinBlock> blocks = new ArrayList<>();

        private ActiveVeinBuilder(CustomVein vein) {
            this.vein = vein;
        }
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        private static BlockKey of(Location location) {
            World world = location.getWorld();
            if (world == null) throw new IllegalArgumentException("location has no world");
            return new BlockKey(world.getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    private record ChunkKey(UUID worldId, int x, int z) {
        private static ChunkKey of(BlockKey key) {
            return new ChunkKey(key.worldId, key.x >> 4, key.z >> 4);
        }

        private static ChunkKey of(Chunk chunk) {
            return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }
}
