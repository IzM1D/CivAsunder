package io.lithcore.civasunder.managers;

import io.lithcore.civasunder.CivAsunder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    private final CivAsunder plugin;
    private final Set<String> whitelist = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, String> passwordHashes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastQuitTimes = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastIPs = new ConcurrentHashMap<>();
    private final Set<UUID> unauthenticatedPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private boolean authSaveScheduled;
    private boolean whitelistSaveScheduled;

    public AuthManager(CivAsunder plugin) {
        this.plugin = plugin;
        loadWhitelist();
        loadAuthDatabase();
    }

    public Set<String> getWhitelist() { return whitelist; }
    public Set<UUID> getUnauthenticatedPlayers() { return unauthenticatedPlayers; }
    public boolean isRegistered(String name) { return passwordHashes.containsKey(name.toLowerCase()); }

    public boolean isSessionValid(UUID uuid, String currentIP) {
        if (!lastQuitTimes.containsKey(uuid) || !lastIPs.containsKey(uuid)) return false;
        String savedIP = lastIPs.get(uuid);
        if (!savedIP.equals(currentIP)) return false;
        long lastQuit = lastQuitTimes.get(uuid);
        long diff = System.currentTimeMillis() - lastQuit;
        return diff < (36 * 60 * 60 * 1000L);
    }

    public void registerPlayer(String name, String password) {
        passwordHashes.put(name.toLowerCase(), hashPassword(password));
        saveAuthDatabase();
    }

    public boolean checkPassword(String name, String password) {
        String hash = passwordHashes.get(name.toLowerCase());
        return hash != null && hash.equals(hashPassword(password));
    }

    public void updateSession(UUID uuid, String ip) {
        lastQuitTimes.put(uuid, System.currentTimeMillis());
        lastIPs.put(uuid, ip);
        saveAuthDatabase();
    }

    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return password;
        }
    }

    public boolean addPlayerToWhitelist(String name) {
        String lowerName = name.toLowerCase();
        if (whitelist.contains(lowerName)) return false;
        whitelist.add(lowerName);
        saveWhitelistToFile();
        return true;
    }

    public boolean removePlayerFromWhitelist(String name) {
        String lowerName = name.toLowerCase();
        if (!whitelist.contains(lowerName)) return false;
        whitelist.remove(lowerName);
        saveWhitelistToFile();
        return true;
    }

    public void loadWhitelist() {
        File file = new File(plugin.getDataFolder(), "whitelist.yml");
        if (!file.exists()) {
            whitelist.clear();
            whitelist.add("player1");
            saveWhitelistToFile();
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        whitelist.clear();
        if (config.contains("whitelist")) {
            for (String name : config.getStringList("whitelist")) { whitelist.add(name.toLowerCase()); }
        }
    }

    private void saveWhitelistToFile() {
        if (whitelistSaveScheduled || !plugin.isEnabled()) return;
        whitelistSaveScheduled = true;
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            whitelistSaveScheduled = false;
            saveWhitelistNow();
        }, 20L);
    }

    private void saveWhitelistNow() {
        File file = new File(plugin.getDataFolder(), "whitelist.yml");
        List<String> list = new ArrayList<>(whitelist);
        plugin.getDataWriter().write(file.toPath(), () -> {
            YamlConfiguration config = new YamlConfiguration();
            config.set("whitelist", list);
            return config.saveToString();
        });
    }

    private void loadAuthDatabase() {
        File file = new File(plugin.getDataFolder(), "auth_database.yml");
        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        passwordHashes.clear();
        if (config.contains("passwords")) {
            for (String key : config.getConfigurationSection("passwords").getKeys(false)) {
                passwordHashes.put(key.toLowerCase(), config.getString("passwords." + key));
            }
        }
        lastQuitTimes.clear(); lastIPs.clear();
        if (config.contains("sessions")) {
            for (String key : config.getConfigurationSection("sessions").getKeys(false)) {
                UUID uuid = UUID.fromString(key);
                lastQuitTimes.put(uuid, config.getLong("sessions." + key + ".last_quit"));
                lastIPs.put(uuid, config.getString("sessions." + key + ".ip"));
            }
        }
    }

    public void saveAuthDatabase() {
        if (authSaveScheduled || !plugin.isEnabled()) return;
        authSaveScheduled = true;
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            authSaveScheduled = false;
            saveAuthDatabaseNow();
        }, 20L);
    }

    private void saveAuthDatabaseNow() {
        File file = new File(plugin.getDataFolder(), "auth_database.yml");
        Map<String, String> passwordSnapshot = new HashMap<>(passwordHashes);
        Map<UUID, Long> quitSnapshot = new HashMap<>(lastQuitTimes);
        Map<UUID, String> ipSnapshot = new HashMap<>(lastIPs);
        plugin.getDataWriter().write(file.toPath(), () -> {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<String, String> entry : passwordSnapshot.entrySet()) {
                config.set("passwords." + entry.getKey(), entry.getValue());
            }
            for (Map.Entry<UUID, Long> entry : quitSnapshot.entrySet()) {
                UUID uuid = entry.getKey();
                config.set("sessions." + uuid + ".last_quit", entry.getValue());
                config.set("sessions." + uuid + ".ip", ipSnapshot.get(uuid));
            }
            return config.saveToString();
        });
    }

    public void flushSaves() {
        authSaveScheduled = false;
        whitelistSaveScheduled = false;
        saveAuthDatabaseNow();
        saveWhitelistNow();
    }

    // ТЗ: Бесшовный кэш инвентарей и брони на время авторизации в лобби
    private final Map<UUID, ItemStack[]> inventoryCache = new ConcurrentHashMap<>();
    private final Map<UUID, ItemStack[]> armorCache = new ConcurrentHashMap<>();

    // Кэш для сохранения точных координат и характеристик (UUID -> Данные)
    private final Map<UUID, org.bukkit.Location> locationCache = new ConcurrentHashMap<>();
    private final Map<UUID, Double> healthCache = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> foodCache = new ConcurrentHashMap<>();
    private final Map<UUID, Float> expCache = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> levelCache = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.Collection<org.bukkit.potion.PotionEffect>> effectsCache = new ConcurrentHashMap<>();

    public void cacheAndClearInventory(Player player) {
        UUID uuid = player.getUniqueId();
        inventoryCache.put(uuid, player.getInventory().getContents().clone());
        armorCache.put(uuid, player.getInventory().getArmorContents().clone());
        
        // ТЗ: Запоминаем точную статистику, координаты и характеристики
        locationCache.put(uuid, player.getLocation().clone());
        healthCache.put(uuid, player.getHealth());
        foodCache.put(uuid, player.getFoodLevel());
        expCache.put(uuid, player.getExp());
        levelCache.put(uuid, player.getLevel());
        effectsCache.put(uuid, new ArrayList<>(player.getActivePotionEffects()));

        player.getInventory().clear();
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
    }

    public void restoreInventory(Player player) {
        UUID uuid = player.getUniqueId();
        if (inventoryCache.containsKey(uuid)) { player.getInventory().setContents(inventoryCache.remove(uuid)); }
        if (armorCache.containsKey(uuid)) { player.getInventory().setArmorContents(armorCache.remove(uuid)); }
        
        // ТЗ: Возвращаем человека ровно туда, где он находился, со всей статистикой.
        // Если сохранённая точка оказалась в блоке (мир изменился / regenerate / загрузка чанков) — поднимаем на первый свободный Y.
        if (locationCache.containsKey(uuid)) {
            org.bukkit.Location cached = locationCache.remove(uuid);
            SoulManager sm = plugin.getSoulManager();
            player.teleport(sm != null ? sm.findSafeLocation(cached) : cached);
        }
        if (healthCache.containsKey(uuid)) { player.setHealth(healthCache.remove(uuid)); }
        if (foodCache.containsKey(uuid)) { player.setFoodLevel(foodCache.remove(uuid)); }
        if (expCache.containsKey(uuid)) { player.setExp(expCache.remove(uuid)); }
        if (levelCache.containsKey(uuid)) { player.setLevel(levelCache.remove(uuid)); }
        if (effectsCache.containsKey(uuid)) {
            for (org.bukkit.potion.PotionEffect effect : effectsCache.remove(uuid)) {
                player.addPotionEffect(effect);
            }
        }
    }
}
