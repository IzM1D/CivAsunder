package io.lithcore.civasunder.managers;

import io.lithcore.civasunder.CivAsunder;
import org.bukkit.Location;
import org.bukkit.block.Block;
import java.io.*;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class TownHallManager {

    private final CivAsunder plugin;
    private final Map<Location, TownHallData> townHalls = new ConcurrentHashMap<>();
    private final Map<Location, Integer> reinforcedBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, ConcurrentNavigableMap<Integer, Set<TownHallData>>> townHallsByWorldAndX = new ConcurrentHashMap<>();
    private volatile int maximumProtectionRadius = 16;

    public static class TownHallData {
        public Location loc;
        public UUID mayorUUID;
        public int energyLevel; // 1 единица энергии = +1 к радиусу

        public TownHallData(Location loc, UUID mayorUUID, int energyLevel) {
            this.loc = loc;
            this.mayorUUID = mayorUUID;
            this.energyLevel = energyLevel;
        }
    }

    public TownHallManager(CivAsunder plugin) {
        this.plugin = plugin;
    }

    public org.bukkit.inventory.ItemStack createTownHallItem() {
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.CARTOGRAPHY_TABLE);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(org.bukkit.ChatColor.GOLD + "Блок Ратуши");
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(org.bukkit.ChatColor.GRAY + "Поставьте этот блок, чтобы основать Ратушу вашего города!");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    public void registerTownHallRecipe() {
        org.bukkit.inventory.ItemStack item = createTownHallItem();
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, "town_hall_core_block");
        org.bukkit.inventory.ShapedRecipe recipe = new org.bukkit.inventory.ShapedRecipe(key, item);
        recipe.shape("GGG", "GTE", "GGG");
        recipe.setIngredient('G', org.bukkit.Material.GOLD_INGOT);
        // [ИСПРАВЛЕНО]: Меняем CARTOGRAPHY_TABLE на FLETCHING_TABLE, чтобы крафт со скриншота сработал!
        recipe.setIngredient('T', org.bukkit.Material.FLETCHING_TABLE);
        recipe.setIngredient('E', org.bukkit.Material.AMETHYST_SHARD);
        org.bukkit.Bukkit.addRecipe(recipe);
    }



    // Находит самую первую и самую старую Ратушу, которая защищает эту точку на карте
    public TownHallData getProtectingTownHall(Location target) {
        TownHallData priorityHall = null;
        double closestDistanceSquared = Double.MAX_VALUE;

        ConcurrentNavigableMap<Integer, Set<TownHallData>> worldIndex =
                townHallsByWorldAndX.get(target.getWorld().getUID());
        if (worldIndex == null || worldIndex.isEmpty()) return null;

        int targetX = target.getBlockX();
        int targetZ = target.getBlockZ();
        int maximumRadius = maximumProtectionRadius;
        for (Set<TownHallData> hallsAtX : worldIndex.subMap(
                targetX - maximumRadius, true, targetX + maximumRadius, true).values()) {
            for (TownHallData th : hallsAtX) {
                if (Math.abs(th.loc.getBlockZ() - targetZ) > maximumRadius) continue;
                double distanceSquared = th.loc.distanceSquared(target);
                int radius = 16 + th.energyLevel;

                if (distanceSquared <= (double) radius * radius
                        && distanceSquared < closestDistanceSquared) {
                    closestDistanceSquared = distanceSquared;
                    priorityHall = th;
                }
            }
        }
        return priorityHall;
    }

    public void addTownHall(Location location, TownHallData data) {
        townHalls.put(location, data);
        townHallsByWorldAndX
                .computeIfAbsent(location.getWorld().getUID(), ignored -> new ConcurrentSkipListMap<>())
                .computeIfAbsent(location.getBlockX(), ignored -> ConcurrentHashMap.newKeySet())
                .add(data);
        maximumProtectionRadius = Math.max(maximumProtectionRadius, 16 + data.energyLevel);
    }

    public TownHallData removeTownHall(Location location) {
        TownHallData removed = townHalls.remove(location);
        if (removed == null) return null;
        ConcurrentNavigableMap<Integer, Set<TownHallData>> worldIndex =
                townHallsByWorldAndX.get(location.getWorld().getUID());
        if (worldIndex != null) {
            Set<TownHallData> atX = worldIndex.get(location.getBlockX());
            if (atX != null && atX.remove(removed) && atX.isEmpty()) worldIndex.remove(location.getBlockX(), atX);
            if (worldIndex.isEmpty()) townHallsByWorldAndX.remove(location.getWorld().getUID(), worldIndex);
        }
        return removed;
    }

    public void increaseEnergy(TownHallData townHall) {
        townHall.energyLevel++;
        maximumProtectionRadius = Math.max(maximumProtectionRadius, 16 + townHall.energyLevel);
    }

    // Проверяет, является ли игрок гражданином того же государства, что и Мэр Ратуши
    public boolean isCitizen(UUID playerUUID, TownHallData th, KingdomManager km) {
        if (th.mayorUUID.equals(playerUUID)) return true; // Мэр всегда гражданин
        
        KingdomManager.KingdomData kd = km.getKingdomByMember(th.mayorUUID);
        return kd != null && kd.memberRanks.containsKey(playerUUID);
    }

    public Map<Location, TownHallData> getTownHalls() { return townHalls; }
    public Map<Location, Integer> getReinforcedBlocks() { return reinforcedBlocks; }
// [РЕЕСТР ЗАПРЕЩЕННЫХ БЛОКОВ]: Проверяет, можно ли укреплять этот блок
    public boolean isBlockBlacklisted(org.bukkit.block.Block block) {
        org.bukkit.Material type = block.getType();
        
        // Запрещаем обсидиан, плачущий обсидиан и бедрок
        if (type == org.bukkit.Material.OBSIDIAN || type == org.bukkit.Material.CRYING_OBSIDIAN || type == org.bukkit.Material.BEDROCK) {
            return true;
        }
        
        // Запрещаем укреплять абсолютно любые рудные жилы CivAsunder (все виды стратегических руд)
        String name = type.name();
        if (name.contains("ORE") || name.contains("RAW") || type == org.bukkit.Material.AMETHYST_CLUSTER) {
            return true;
        }
        
        return false;
    }

    // [СОХРАНЕНИЕ ДАННЫХ]: Запись всех ратуш и прочности блоков в файл townhalls.yml
    public void saveData() {
        File file = new File(plugin.getDataFolder(), "townhalls.yml");
        List<TownHallSnapshot> townHallSnapshots = new ArrayList<>(townHalls.size());
        for (Map.Entry<Location, TownHallData> entry : townHalls.entrySet()) {
            Location loc = entry.getKey();
            TownHallData data = entry.getValue();
            townHallSnapshots.add(new TownHallSnapshot(loc.getWorld().getName(), loc.getBlockX(),
                    loc.getBlockY(), loc.getBlockZ(), data.mayorUUID, data.energyLevel));
        }
        List<ReinforcedSnapshot> reinforcedSnapshots = new ArrayList<>(reinforcedBlocks.size());
        for (Map.Entry<Location, Integer> entry : reinforcedBlocks.entrySet()) {
            Location loc = entry.getKey();
            reinforcedSnapshots.add(new ReinforcedSnapshot(loc.getWorld().getName(), loc.getBlockX(),
                    loc.getBlockY(), loc.getBlockZ(), entry.getValue()));
        }

        plugin.getDataWriter().write(file.toPath(), () -> {
            org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
            for (int i = 0; i < townHallSnapshots.size(); i++) {
                TownHallSnapshot data = townHallSnapshots.get(i);
                String path = "townhalls." + i;
                config.set(path + ".world", data.world);
                config.set(path + ".x", data.x);
                config.set(path + ".y", data.y);
                config.set(path + ".z", data.z);
                config.set(path + ".mayor", data.mayor.toString());
                config.set(path + ".energy", data.energy);
            }
            for (int i = 0; i < reinforcedSnapshots.size(); i++) {
                ReinforcedSnapshot data = reinforcedSnapshots.get(i);
                String path = "reinforced." + i;
                config.set(path + ".world", data.world);
                config.set(path + ".x", data.x);
                config.set(path + ".y", data.y);
                config.set(path + ".z", data.z);
                config.set(path + ".durability", data.durability);
            }
            return config.saveToString();
        });
    }

    // [ЗАГРУЗКА ДАННЫХ]: Чтение данных из файла townhalls.yml при старте сервера
    public void loadData() {
        File file = new File(plugin.getDataFolder(), "townhalls.yml");
        if (!file.exists()) return;

        org.bukkit.configuration.file.YamlConfiguration config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        townHalls.clear();
        reinforcedBlocks.clear();
        townHallsByWorldAndX.clear();
        maximumProtectionRadius = 16;

        // 1. Читаем Ратуши
        if (config.contains("townhalls")) {
            for (String key : config.getConfigurationSection("townhalls").getKeys(false)) {
                String path = "townhalls." + key;
                String worldName = config.getString(path + ".world");
                org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
                if (world == null) continue;

                int x = config.getInt(path + ".x");
                int y = config.getInt(path + ".y");
                int z = config.getInt(path + ".z");
                Location loc = new Location(world, x, y, z);

                UUID mayorUUID = UUID.fromString(config.getString(path + ".mayor"));
                int energy = config.getInt(path + ".energy");

                addTownHall(loc, new TownHallData(loc, mayorUUID, energy));
            }
        }

        // 2. Читаем укрепленные блоки
        if (config.contains("reinforced")) {
            for (String key : config.getConfigurationSection("reinforced").getKeys(false)) {
                String path = "reinforced." + key;
                String worldName = config.getString(path + ".world");
                org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
                if (world == null) continue;

                int x = config.getInt(path + ".x");
                int y = config.getInt(path + ".y");
                int z = config.getInt(path + ".z");
                Location loc = new Location(world, x, y, z);

                int durability = config.getInt(path + ".durability");
                reinforcedBlocks.put(loc, durability);
            }
        }
        plugin.getLogger().info("[CivAsunder] Данные Ратуш (" + townHalls.size() + " шт.) и блоков (" + reinforcedBlocks.size() + " шт.) успешно загружены!");
    }

    private record TownHallSnapshot(String world, int x, int y, int z, UUID mayor, int energy) {}
    private record ReinforcedSnapshot(String world, int x, int y, int z, int durability) {}
}
