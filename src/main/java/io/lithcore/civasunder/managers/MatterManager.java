package io.lithcore.civasunder.managers;

import io.lithcore.civasunder.CivAsunder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MatterManager {

    private final CivAsunder plugin;
    private final Map<Material, Double> valueTable = new ConcurrentHashMap<>();
    private final Set<Location> converters = ConcurrentHashMap.newKeySet();

    public MatterManager(CivAsunder plugin) {
        this.plugin = plugin;
        setupDefaultValues();
        loadData();
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

    public ItemStack createMatterItem(int amount) {
        ItemStack matterItem = new ItemStack(Material.AMETHYST_SHARD, amount);
        ItemMeta meta = matterItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Эссенция Материи");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Универсальная концентрированная валюта.");
            lore.add(ChatColor.DARK_PURPLE + "Извлечено из утилизированных ресурсов.");
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "is_matter_currency"),
                    PersistentDataType.BYTE, (byte) 1);
            matterItem.setItemMeta(meta);
        }
        return matterItem;
    }

    public Map<Material, Double> getValueTable() {
        return valueTable;
    }

    public Set<Location> getConverters() {
        return converters;
    }

    public void saveData() {
        File file = new File(plugin.getDataFolder(), "matter.yml");
        List<ConverterSnapshot> snapshots = new ArrayList<>(converters.size());
        for (Location loc : converters) {
            if (loc.getWorld() == null) continue;
            snapshots.add(new ConverterSnapshot(loc.getWorld().getName(), loc.getBlockX(),
                    loc.getBlockY(), loc.getBlockZ()));
        }

        plugin.getDataWriter().write(file.toPath(), () -> {
            YamlConfiguration config = new YamlConfiguration();
            for (int i = 0; i < snapshots.size(); i++) {
                ConverterSnapshot snapshot = snapshots.get(i);
                String path = "converters." + i;
                config.set(path + ".world", snapshot.world);
                config.set(path + ".x", snapshot.x);
                config.set(path + ".y", snapshot.y);
                config.set(path + ".z", snapshot.z);
            }
            return config.saveToString();
        });
    }

    private void loadData() {
        File file = new File(plugin.getDataFolder(), "matter.yml");
        if (!file.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        converters.clear();
        if (!config.contains("converters")) return;

        for (String key : config.getConfigurationSection("converters").getKeys(false)) {
            String path = "converters." + key;
            org.bukkit.World world = Bukkit.getWorld(config.getString(path + ".world"));
            if (world == null) continue;
            converters.add(new Location(world, config.getInt(path + ".x"),
                    config.getInt(path + ".y"), config.getInt(path + ".z")));
        }
    }

    private record ConverterSnapshot(String world, int x, int y, int z) {}
}
