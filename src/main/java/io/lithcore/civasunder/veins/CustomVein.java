package io.lithcore.civasunder.veins;

import io.lithcore.civasunder.managers.VeinManager;
import org.bukkit.Material;

import java.util.List;
import java.util.UUID;

/**
 * A plugin-owned vein aggregate. Its blocks are the only blocks which may use
 * mining, fatigue and regeneration mechanics.
 */
public record CustomVein(
        UUID id,
        VeinManager.VeinType type,
        int centerX,
        int centerY,
        int centerZ,
        int baseRegenSeconds,
        List<VeinBlock> blocks
) {
    public CustomVein {
        blocks = List.copyOf(blocks);
    }

    public record VeinBlock(int x, int y, int z, Material material) {}
}
