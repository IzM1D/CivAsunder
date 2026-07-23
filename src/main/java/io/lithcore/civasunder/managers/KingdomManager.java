package io.lithcore.civasunder.managers;

import io.lithcore.civasunder.CivAsunder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import java.io.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;

public class KingdomManager {

    private final CivAsunder plugin;
    private final Map<String, KingdomData> kingdoms = new ConcurrentHashMap<>();
    private final Map<Location, String> blockLocations = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeInvites = new ConcurrentHashMap<>();
    private final Map<UUID, KingdomData> kingdomByKing = new ConcurrentHashMap<>();
    private final Map<UUID, KingdomData> kingdomByMember = new ConcurrentHashMap<>();
    private boolean saveScheduled;

    public static class KingdomData {
        public String name;
        public UUID kingUUID;
        public final Map<UUID, Integer> memberRanks = new ConcurrentHashMap<>();

        public KingdomData(String name, UUID kingUUID) {
            this.name = name;
            this.kingUUID = kingUUID;
        }
    }

    public KingdomManager(CivAsunder plugin) {
        this.plugin = plugin;
        loadKingdomsFromFile();
    }

    public void registerKingdomRecipe() {
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.FLETCHING_TABLE);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(org.bukkit.ChatColor.GOLD + "Блок Сердца Государства");
            java.util.List<String> lore = new java.util.ArrayList<>();
            lore.add(org.bukkit.ChatColor.GRAY + "Поставьте этот block, чтобы основать своё государство!");
            meta.setLore(lore);
            item.setItemMeta(meta);
        }

        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, "kingdom_core_block");
        org.bukkit.inventory.ShapedRecipe recipe = new org.bukkit.inventory.ShapedRecipe(key, item);
        recipe.shape("GGG", "GTG", "GGG");
        recipe.setIngredient('G', org.bukkit.Material.GOLD_INGOT);
        recipe.setIngredient('T', org.bukkit.Material.FLETCHING_TABLE);

        Bukkit.addRecipe(recipe);
    }

    public boolean createKingdom(String name, org.bukkit.entity.Player king, Location blockLoc) {
        String lowerName = name.toLowerCase();
        if (kingdoms.containsKey(lowerName)) {
            return false;
        }

        KingdomData data = new KingdomData(name, king.getUniqueId());
        data.memberRanks.put(king.getUniqueId(), 100);

        kingdoms.put(lowerName, data);
        blockLocations.put(blockLoc, lowerName);
        kingdomByKing.put(data.kingUUID, data);
        kingdomByMember.put(data.kingUUID, data);
        
        saveKingdomsToFile();
        return true;
    }

    public int getPlayerRank(KingdomData k, UUID uuid) {
        if (k.kingUUID.equals(uuid)) return 100;
        return k.memberRanks.getOrDefault(uuid, 0);
    }

    public boolean canManage(KingdomData k, UUID staff, UUID target) {
        if (k.kingUUID.equals(staff)) return true;
        if (k.kingUUID.equals(target)) return false;
        
        int staffRank = k.memberRanks.getOrDefault(staff, 0);
        int targetRank = k.memberRanks.getOrDefault(target, 0);
        
        return staffRank > targetRank;
    }

    public KingdomData getKingdomByStaff(UUID uuid) {
        KingdomData kingdom = kingdomByMember.get(uuid);
        return kingdom != null && (kingdom.kingUUID.equals(uuid)
                || kingdom.memberRanks.getOrDefault(uuid, 0) > 1) ? kingdom : null;
    }

    public KingdomData getKingdomByKing(UUID kingUUID) {
        return kingdomByKing.get(kingUUID);
    }

    public KingdomData getKingdomByMember(UUID memberUUID) {
        return kingdomByMember.get(memberUUID);
    }

    public void saveKingdomsToFile() {
        rebuildLookupIndexes();
        if (saveScheduled || !plugin.isEnabled()) return;
        saveScheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            saveScheduled = false;
            saveKingdomsNow();
        }, 20L);
    }

    private void saveKingdomsNow() {
        File file = new File(plugin.getDataFolder(), "kingdoms_data.txt");
        rebuildLookupIndexes();
        java.util.List<String> blockLines = new java.util.ArrayList<>(blockLocations.size());
        for (Map.Entry<Location, String> entry : blockLocations.entrySet()) {
            Location location = entry.getKey();
            blockLines.add("BLOCK:" + location.getWorld().getName() + "," + location.getBlockX() + ","
                    + location.getBlockY() + "," + location.getBlockZ() + ";" + entry.getValue());
        }
        java.util.List<KingdomSnapshot> kingdomSnapshots = new java.util.ArrayList<>(kingdoms.size());
        for (KingdomData kingdom : kingdoms.values()) {
            kingdomSnapshots.add(new KingdomSnapshot(kingdom.name, kingdom.kingUUID,
                    new HashMap<>(kingdom.memberRanks)));
        }

        plugin.getDataWriter().write(file.toPath(), java.nio.charset.Charset.defaultCharset(), () -> {
            String lineSeparator = System.lineSeparator();
            StringBuilder output = new StringBuilder();
            for (String line : blockLines) output.append(line).append(lineSeparator);
            for (KingdomSnapshot kingdom : kingdomSnapshots) {
                output.append("DATA:").append(kingdom.name).append(';').append(kingdom.kingUUID).append(';');
                for (Map.Entry<UUID, Integer> member : kingdom.members.entrySet()) {
                    output.append(member.getKey()).append('=').append(member.getValue()).append(',');
                }
                output.append(lineSeparator);
            }
            return output.toString();
        });
    }

    public void loadKingdomsFromFile() {
        File file = new File(plugin.getDataFolder(), "kingdoms_data.txt");
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                
                if (line.startsWith("BLOCK:")) {
                    String[] parts = line.replace("BLOCK:", "").split(";");
                    String[] locParts = parts[0].split(",");
                    World world = Bukkit.getWorld(locParts[0]);
                    if (world == null) continue;
                    Location loc = new Location(world, Integer.parseInt(locParts[1]), Integer.parseInt(locParts[2]), Integer.parseInt(locParts[3]));
                    blockLocations.put(loc, parts[1].toLowerCase());
                } 
                else if (line.startsWith("DATA:")) {
                    String[] parts = line.replace("DATA:", "").split(";");
                    String name = parts[0];
                    UUID king = UUID.fromString(parts[1]);
                    
                    KingdomData data = new KingdomData(name, king);
                    
                    if (parts.length > 2 && !parts[2].trim().isEmpty()) {
                        for (String s : parts[2].split(",")) {
                            if (!s.contains("=")) continue;
                            String[] pair = s.split("=");
                            data.memberRanks.put(UUID.fromString(pair[0]), Integer.parseInt(pair[1]));
                        }
                    }
                    kingdoms.put(name.toLowerCase(), data);
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().severe("[CivAsunder] Error loading kingdoms: " + e.getMessage());
        }
        rebuildLookupIndexes();
    }

    private void rebuildLookupIndexes() {
        kingdomByKing.clear();
        kingdomByMember.clear();
        for (KingdomData kingdom : kingdoms.values()) {
            kingdomByKing.put(kingdom.kingUUID, kingdom);
            for (UUID member : kingdom.memberRanks.keySet()) kingdomByMember.putIfAbsent(member, kingdom);
        }
    }

    private static final class KingdomSnapshot {
        private final String name;
        private final UUID kingUUID;
        private final Map<UUID, Integer> members;

        private KingdomSnapshot(String name, UUID kingUUID, Map<UUID, Integer> members) {
            this.name = name;
            this.kingUUID = kingUUID;
            this.members = members;
        }
    }

    public void cleanUp() {
        saveScheduled = false;
        saveKingdomsNow();
        kingdoms.clear();
        blockLocations.clear();
        kingdomByKing.clear();
        kingdomByMember.clear();
    }

    public Map<String, KingdomData> getKingdoms() {
        return kingdoms;
    }

    public Map<Location, String> getBlockLocations() {
        return blockLocations;
    }

    public Map<UUID, String> getActiveInvites() {
        return activeInvites;
    }
}
