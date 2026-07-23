package io.lithcore.civasunder.managers;

import io.lithcore.civasunder.CivAsunder;
import org.bukkit.Material;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MatterManager {

    private final CivAsunder plugin;
    private final Map<Material, Double> valueTable = new ConcurrentHashMap<>();

    public MatterManager(CivAsunder plugin) {
        this.plugin = plugin;
        setupDefaultValues();
    }

    private void setupDefaultValues() {
        double tVal = 1.0 / 64.0;
        Material[] trash = {
            Material.DIRT, Material.COBBLESTONE, Material.STONE, Material.GRAVEL, 
            Material.SAND, Material.RED_SAND, Material.DIORITE, Material.ANDESITE, 
            Material.GRANITE, Material.NETHERRACK, Material.SOUL_SAND, Material.SOUL_SOIL,
            Material.TUFF, Material.DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.CALCITE,
            Material.ROTTEN_FLESH, Material.BONE, Material.STRING, Material.SPIDER_EYE,
            Material.ARROW, Material.GUNPOWDER, Material.STICK, Material.FEATHER,
            Material.OAK_LEAVES, Material.SPRUCE_LEAVES, Material.BIRCH_LEAVES, Material.JUNGLE_LEAVES,
            Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES, Material.OAK_LOG, Material.OAK_PLANKS,
            Material.WHEAT_SEEDS, Material.PUMPKIN_SEEDS, Material.MELON_SEEDS, Material.BEETROOT_SEEDS,
            Material.WHEAT, Material.POTATO, Material.POISONOUS_POTATO, Material.CARROT, 
            Material.BEETROOT, Material.WHITE_WOOL, Material.BASALT, Material.POLISHED_BASALT, 
            Material.BLACKSTONE, Material.GLOWSTONE_DUST, Material.MAGMA_BLOCK, Material.NETHER_QUARTZ_ORE,
            Material.TERRACOTTA, Material.LADDER, Material.SCAFFOLDING, Material.COAL_ORE, Material.COPPER_ORE,
            Material.IRON_ORE, org.bukkit.Material.GOLD_ORE, org.bukkit.Material.REDSTONE_ORE
        };
        for (Material m : trash) {
            if (m != null) valueTable.put(m, tVal);
        }

        valueTable.put(Material.IRON_INGOT, 0.25);
        valueTable.put(Material.COPPER_INGOT, 0.1);
        valueTable.put(Material.LAPIS_LAZULI, 0.2);
        valueTable.put(Material.AMETHYST_SHARD, 0.2);
        valueTable.put(Material.REDSTONE, 0.1);
        valueTable.put(Material.GOLD_INGOT, 0.5);
        valueTable.put(Material.DIAMOND, 2.0);
        valueTable.put(Material.EMERALD, 1.5);
        valueTable.put(Material.NETHERITE_INGOT, 10.0);
    }

    public int getRequiredAmountForOne(Material type) {
        double singleValue = valueTable.getOrDefault(type, 0.0);
        if (singleValue <= 0.0) return 0;
        return (int) Math.ceil(1.0 / singleValue);
    }

    public int[] calculateConversion(Material type, int amount) {
        double singleValue = valueTable.getOrDefault(type, 0.0);
        if (singleValue <= 0.0) return new int[]{-1, 0};

        double totalValue = singleValue * amount;
        int pureMatterToGive = (int) Math.floor(totalValue);
        if (pureMatterToGive < 1) return new int[]{-2, 0};

        int itemsToConsume = (int) Math.ceil((double) pureMatterToGive / singleValue);
        if (itemsToConsume > amount) itemsToConsume = amount;

        return new int[]{pureMatterToGive, itemsToConsume};
    }

    public Map<Material, Double> getValueTable() {
        return valueTable;
    }
}