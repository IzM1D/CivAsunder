package io.lithcore.civasunder.veins;

import io.lithcore.civasunder.managers.VeinManager;
import org.bukkit.Material;
import org.bukkit.RegionAccessor;
import org.bukkit.util.noise.SimplexNoiseGenerator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Pure deterministic generation rules shared by population and all locators. */
public final class VeinGenerationEngine {

    private VeinGenerationEngine() {}

    public static VeinPlan plan(long worldSeed, int chunkX, int chunkZ) {
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        SimplexNoiseGenerator noise = new SimplexNoiseGenerator(worldSeed);

        double regionX = noise.noise((double) blockX * 0.002, (double) blockZ * 0.002);
        double regionZ = noise.noise((double) blockX * 0.002 + 5000, (double) blockZ * 0.002 + 5000);

        VeinManager.VeinType type = null;
        if (regionX > 0.3 && regionZ > 0.3) type = VeinManager.VeinType.DIAMOND;
        else if (regionX > 0.1 && regionZ < -0.1) type = VeinManager.VeinType.GOLD;
        else if (regionX < -0.2 && regionZ > 0.1) type = VeinManager.VeinType.IRON;
        else if (regionX < -0.1 && regionZ < -0.2) type = VeinManager.VeinType.COAL;
        if (type == null) return null;

        double detail = noise.noise((double) blockX * 0.05, (double) blockZ * 0.05);
        if (detail <= type.noiseThreshold) return null;

        Random random = chunkRandom(worldSeed, chunkX, chunkZ);
        int centerX = blockX + 4 + random.nextInt(8);
        int centerZ = blockZ + 4 + random.nextInt(8);
        int centerY = type.minY + random.nextInt(Math.abs(type.maxY - type.minY) + 1);
        return new VeinPlan(type, centerX, centerY, centerZ);
    }

    public static CustomVein generateNatural(RegionAccessor region, UUID worldId, long worldSeed,
                                             int minHeight, int maxHeight, int chunkX, int chunkZ) {
        VeinPlan plan = plan(worldSeed, chunkX, chunkZ);
        if (plan == null) return null;
        UUID id = UUID.nameUUIDFromBytes(("natural:" + worldId + ':' + chunkX + ':' + chunkZ)
                .getBytes(StandardCharsets.UTF_8));
        Random random = chunkRandom(worldSeed, chunkX, chunkZ);
        // Preserve the exact PRNG position after the old center calculations.
        random.nextInt(8);
        random.nextInt(8);
        random.nextInt(Math.abs(plan.type.maxY - plan.type.minY) + 1);
        return generate(region, id, random, plan.type,
                plan.centerX, plan.centerY, plan.centerZ, minHeight, maxHeight);
    }

    public static CustomVein generateForced(RegionAccessor region, UUID id, Random random,
                                            VeinManager.VeinType type, int minHeight, int maxHeight,
                                            int chunkX, int chunkZ) {
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        int centerX = blockX + 4 + random.nextInt(8);
        int centerZ = blockZ + 4 + random.nextInt(8);
        int centerY = type.minY + random.nextInt(Math.abs(type.maxY - type.minY) + 1);
        return generate(region, id, random, type, centerX, centerY, centerZ, minHeight, maxHeight);
    }

    private static CustomVein generate(RegionAccessor region, UUID id, Random random,
                                       VeinManager.VeinType type, int centerX, int centerY, int centerZ,
                                       int minHeight, int maxHeight) {
        int clusterSize = 20 + random.nextInt(31);
        int blocksPlaced = 0;
        int currentX = centerX;
        int currentY = centerY;
        int currentZ = centerZ;
        int maxRadius = 4;
        List<CustomVein.VeinBlock> blocks = new ArrayList<>(clusterSize);

        for (int i = 0; i < clusterSize * 2 && blocksPlaced < clusterSize; i++) {
            if (currentY >= minHeight && currentY < maxHeight) {
                Material current = region.getType(currentX, currentY, currentZ);
                if (current == Material.STONE || current == Material.DEEPSLATE) {
                    Material ore = currentY < 0 ? type.deepslate : type.normal;
                    region.setType(currentX, currentY, currentZ, ore);
                    blocks.add(new CustomVein.VeinBlock(currentX, currentY, currentZ, ore));
                    blocksPlaced++;
                }
            }

            currentX += random.nextInt(3) - 1;
            currentY += random.nextInt(3) - 1;
            currentZ += random.nextInt(3) - 1;
            if (Math.abs(currentX - centerX) > maxRadius) currentX = centerX + random.nextInt(3) - 1;
            if (Math.abs(currentY - centerY) > maxRadius) currentY = centerY + random.nextInt(3) - 1;
            if (Math.abs(currentZ - centerZ) > maxRadius) currentZ = centerZ + random.nextInt(3) - 1;
        }

        return new CustomVein(id, type, centerX, centerY, centerZ,
                type.baseRegenSeconds, blocks);
    }

    public static Random chunkRandom(long worldSeed, int chunkX, int chunkZ) {
        return new Random(worldSeed + ((long) chunkX * 31) + chunkZ);
    }

    public record VeinPlan(VeinManager.VeinType type, int centerX, int centerY, int centerZ) {}
}
