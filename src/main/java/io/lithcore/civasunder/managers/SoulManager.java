package io.lithcore.civasunder.managers;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;

import io.lithcore.civasunder.CivAsunder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SoulManager implements Listener {

    private final CivAsunder plugin;
    private final Map<UUID, Long> imprisonedPlayers = new ConcurrentHashMap<>();
    private final Set<Location> altars = ConcurrentHashMap.newKeySet();
    private final Set<UUID> trackedItemEntities = ConcurrentHashMap.newKeySet();
    private final Set<UUID> trackedPlayerInventories = ConcurrentHashMap.newKeySet();
    private final Set<Inventory> openSoulInventories = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<UUID, SoulScoreboard> soulScoreboards = new HashMap<>();
    private final NamespacedKey ownerKey;
    private final NamespacedKey timeKey;
    private final NamespacedKey extensionKey;
    private World soulWorld;

    public SoulManager(CivAsunder plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "soul_owner_uuid");
        this.timeKey = new NamespacedKey(plugin, "soul_expire_time");
        this.extensionKey = new NamespacedKey(plugin, "soul_extensions");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        createSoulWorld();
        startSoulTicker();
    }

    private void createSoulWorld() {
        WorldCreator creator = new WorldCreator("civ_souls");
        creator.type(WorldType.FLAT);
        // ТЗ: Мир из Светло-серой шерсти для удобного пиксель-арт творчества игроков
        creator.generatorSettings("{\"biome\":\"minecraft:the_void\",\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:light_gray_wool\",\"height\":60}],\"structures\":{\"structures\":{}}}");
        creator.generateStructures(false);
        
        this.soulWorld = Bukkit.createWorld(creator);
        if (this.soulWorld != null) {
            this.soulWorld.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
            this.soulWorld.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
            this.soulWorld.setTime(18000); // Атмосферная ночь
            plugin.getLogger().info("[CivAsunder] Мир Творческого Чистилища успешно загружен!");
        }
    }

    public World getSoulWorld() { return soulWorld; }
    public int getBaseSoulDuration() { return 300; }
    public int getExtensionCost(int currentExtensions) { return (int) Math.pow(2, currentExtensions); }
    public long getExtensionDuration(int currentExtensions) {
        return getBaseSoulDuration() * (1L << currentExtensions);
    }
    public Map<UUID, Long> getImprisonedPlayers() { return imprisonedPlayers; }
    public Set<Location> getAltars() { return altars; }

    public ItemStack createSoulItem(UUID playerUUID, String playerName) {
        ItemStack soul = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = soul.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§bДуша игрока " + playerName);
            long expireTime = System.currentTimeMillis() + (getBaseSoulDuration() * 1000L);
            List<String> lore = new ArrayList<>();
            lore.add("§7Статус: §cЗаточена");
            lore.add("§7Осталось времени: §e5 мин. 0 сек.");
            lore.add("§7Продлений в алтаре: §a0");
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, playerUUID.toString());
            meta.getPersistentDataContainer().set(timeKey, PersistentDataType.LONG, expireTime);
            meta.getPersistentDataContainer().set(extensionKey, PersistentDataType.INTEGER, 0);
            soul.setItemMeta(meta);
        }
        return soul;
    }

    private void startSoulTicker() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (imprisonedPlayers.isEmpty()) {
                soulScoreboards.clear();
                return;
            }
            soulScoreboards.keySet().removeIf(uuid -> !imprisonedPlayers.containsKey(uuid));

            updateTrackedSoulLore();
            updateSoulScoreboards();
            
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Long> entry : imprisonedPlayers.entrySet()) {
                UUID victimUUID = entry.getKey();
                long expireTime = entry.getValue();

                if (now > expireTime && imprisonedPlayers.remove(victimUUID, expireTime)) {
                    
                    Player p = Bukkit.getPlayer(victimUUID);
                    if (p != null && p.isOnline()) {
                        p.getInventory().clear();
                        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                        p.setGameMode(org.bukkit.GameMode.SURVIVAL);
                        
                        org.bukkit.Location bedLoc = p.getRespawnLocation();
                        if (bedLoc != null) { p.teleport(bedLoc); }
                        else { p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()); }
                        
                        io.lithcore.civasunder.util.MessageUtil.sendJson(p, "{\"text\":\"[Души] Время заключения истекло! Вы возвращены на свою точку сохранения.\",\"color\":\"green\"}");
                    }
                    soulScoreboards.remove(victimUUID);
                }
            }
        }, 100L, 20L);
    }

    private void updateSoulScoreboards() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : imprisonedPlayers.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null || !p.isOnline() || !p.getWorld().equals(soulWorld)) continue;
            long expire = entry.getValue();

            long diff = expire - now; if (diff < 0) diff = 0;
            long totalSeconds = diff / 1000L; long minutes = totalSeconds / 60L; long seconds = totalSeconds % 60L;

            SoulScoreboard scoreboard = soulScoreboards.computeIfAbsent(p.getUniqueId(), ignored -> createSoulScoreboard());
            scoreboard.timer.setPrefix("§e" + minutes + " мин. " + seconds + " сек.");
            if (p.getScoreboard() != scoreboard.scoreboard) p.setScoreboard(scoreboard.scoreboard);
        }
    }

    private SoulScoreboard createSoulScoreboard() {
        Scoreboard scoreboard = Objects.requireNonNull(Bukkit.getScoreboardManager()).getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("soul_timer", "dummy", "§5§l« ЧИСТИЛИЩЕ »");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.getScore("§1").setScore(6);
        objective.getScore("§7Творческая катока душ").setScore(5);
        objective.getScore("§2").setScore(4);
        objective.getScore("§fОсталось сидеть:").setScore(3);
        objective.getScore("§e").setScore(2);
        objective.getScore("§3").setScore(1);
        objective.getScore("§dСтройте шедевры!").setScore(0);
        Team timer = scoreboard.registerNewTeam("soul_time");
        timer.addEntry("§e");
        return new SoulScoreboard(scoreboard, timer);
    }

    private void updateTrackedSoulLore() {
        long now = System.currentTimeMillis();

        Iterator<UUID> itemIterator = trackedItemEntities.iterator();
        while (itemIterator.hasNext()) {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(itemIterator.next());
            if (!(entity instanceof Item item) || !item.isValid()) {
                itemIterator.remove();
                continue;
            }
            ItemStack stack = item.getItemStack();
            updateItemLore(stack, now);
            item.setItemStack(stack);
        }

        Iterator<UUID> playerIterator = trackedPlayerInventories.iterator();
        while (playerIterator.hasNext()) {
            Player player = Bukkit.getPlayer(playerIterator.next());
            if (player == null || !player.isOnline() || !refreshInventory(player.getInventory(), now)) {
                playerIterator.remove();
            }
        }

        Iterator<Inventory> inventoryIterator = openSoulInventories.iterator();
        while (inventoryIterator.hasNext()) {
            if (!refreshInventory(inventoryIterator.next(), now)) {
                inventoryIterator.remove();
            }
        }
    }

    private boolean refreshInventory(Inventory inventory, long now) {
        boolean containsSoul = false;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isSoulItem(stack)) {
                String owner = stack.getItemMeta().getPersistentDataContainer()
                        .get(ownerKey, PersistentDataType.STRING);
                if (owner != null) {
                    try {
                        if (!imprisonedPlayers.containsKey(UUID.fromString(owner))) {
                            inventory.setItem(slot, null);
                            continue;
                        }
                    } catch (IllegalArgumentException ignored) {
                        // A malformed custom item is left untouched instead of deleting an unrelated item.
                    }
                }
                updateItemLore(stack, now);
                containsSoul = true;
            }
        }
        return containsSoul;
    }

    private boolean isSoulItem(ItemStack stack) {
        return stack != null && stack.getType() == Material.TOTEM_OF_UNDYING && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(timeKey, PersistentDataType.LONG);
    }

    public void refreshItemLore(ItemStack stack) {
        updateItemLore(stack, System.currentTimeMillis());
    }

    /**
     * Removes every loaded copy of a player's soul item. Besides player inventories this covers
     * ender chests, currently open inventories, loaded block/entity containers and dropped items.
     */
    public int removeSoulItems(UUID playerUUID) {
        Set<Inventory> inventories = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Player player : Bukkit.getOnlinePlayers()) {
            inventories.add(player.getInventory());
            inventories.add(player.getEnderChest());
            inventories.add(player.getOpenInventory().getTopInventory());
        }
        inventories.addAll(openSoulInventories);

        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Container container) inventories.add(container.getInventory());
                }
                for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
                    if (entity instanceof Item item && isSoulItemFor(item.getItemStack(), playerUUID)) {
                        removed += item.getItemStack().getAmount();
                        trackedItemEntities.remove(item.getUniqueId());
                        item.remove();
                    } else if (entity instanceof InventoryHolder holder) {
                        inventories.add(holder.getInventory());
                    }
                }
            }
        }

        for (Inventory inventory : inventories) removed += removeSoulItems(inventory, playerUUID);

        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack cursor = player.getItemOnCursor();
            if (isSoulItemFor(cursor, playerUUID)) {
                removed += cursor.getAmount();
                player.setItemOnCursor(null);
            }
        }
        return removed;
    }

    private int removeSoulItems(Inventory inventory, UUID playerUUID) {
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isSoulItemFor(stack, playerUUID)) {
                removed += stack.getAmount();
                inventory.setItem(slot, null);
            }
        }
        return removed;
    }

    private boolean isSoulItemFor(ItemStack stack, UUID playerUUID) {
        if (stack == null || stack.getType() != Material.TOTEM_OF_UNDYING || !stack.hasItemMeta()) return false;
        String owner = stack.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return playerUUID.toString().equals(owner);
    }

    private void updateItemLore(ItemStack stack, long now) {
        if (stack == null || stack.getType() != Material.TOTEM_OF_UNDYING || !stack.hasItemMeta()) return;
        ItemMeta meta = stack.getItemMeta();
        if (meta.getPersistentDataContainer().has(timeKey, PersistentDataType.LONG)) {
            long expire = meta.getPersistentDataContainer().get(timeKey, PersistentDataType.LONG);
            int extensions = meta.getPersistentDataContainer().getOrDefault(extensionKey, PersistentDataType.INTEGER, 0);
            long diff = expire - now; if (diff < 0) diff = 0;
            long totalSeconds = diff / 1000L; long minutes = totalSeconds / 60L; long seconds = totalSeconds % 60L;
            List<String> lore = new ArrayList<>();
            lore.add("§7Статус: §cЗаточена");
            lore.add("§7Осталось времени: §e" + minutes + " мин. " + seconds + " сек.");
            lore.add("§7Продлений в алтаре: §a" + extensions);
            lore.add("§7Следующее продление: §d" + getExtensionCost(extensions) + " Энергии");
            meta.setLore(lore); stack.setItemMeta(meta);
        }
    }

    private void trackItem(Item item) {
        if (isSoulItem(item.getItemStack())) {
            ItemStack stack = item.getItemStack();
            updateItemLore(stack, System.currentTimeMillis());
            item.setItemStack(stack);
            trackedItemEntities.add(item.getUniqueId());
        }
    }

    private void refreshPlayerAndOpenInventory(Player player, Inventory topInventory) {
        long now = System.currentTimeMillis();
        if (refreshInventory(player.getInventory(), now)) trackedPlayerInventories.add(player.getUniqueId());
        else trackedPlayerInventories.remove(player.getUniqueId());
        if (topInventory != null && refreshInventory(topInventory, now)) openSoulInventories.add(topInventory);
        else if (topInventory != null) openSoulInventories.remove(topInventory);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        trackItem(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (org.bukkit.entity.Entity entity : event.getEntities()) {
            if (entity instanceof Item item) trackItem(item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (org.bukkit.entity.Entity entity : event.getEntities()) {
            if (entity instanceof Item) trackedItemEntities.remove(entity.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack stack = event.getItem().getItemStack();
        refreshItemLore(stack);
        event.getItem().setItemStack(stack);
        trackedItemEntities.remove(event.getItem().getUniqueId());
        Bukkit.getScheduler().runTask(plugin,
                () -> refreshPlayerAndOpenInventory(player, player.getOpenInventory().getTopInventory()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        trackItem(event.getItemDrop());
        Bukkit.getScheduler().runTask(plugin,
                () -> refreshPlayerAndOpenInventory(event.getPlayer(), event.getPlayer().getOpenInventory().getTopInventory()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            refreshPlayerAndOpenInventory(player, event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        openSoulInventories.remove(event.getInventory());
        if (event.getPlayer() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> refreshPlayerAndOpenInventory(player, null));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        Bukkit.getScheduler().runTask(plugin, () -> refreshPlayerAndOpenInventory(player, top));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        Bukkit.getScheduler().runTask(plugin, () -> refreshPlayerAndOpenInventory(player, top));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> refreshPlayerAndOpenInventory(event.getPlayer(), null));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        trackedPlayerInventories.remove(event.getPlayer().getUniqueId());
        soulScoreboards.remove(event.getPlayer().getUniqueId());
    }

    public void saveData() {
        File file = new File(plugin.getDataFolder(), "souls.yml");
        Map<UUID, Long> snapshot = new HashMap<>(imprisonedPlayers);
        plugin.getDataWriter().write(file.toPath(), () -> {
            YamlConfiguration config = new YamlConfiguration();
            int i = 0;
            for (Map.Entry<UUID, Long> entry : snapshot.entrySet()) {
                String path = "imprisoned." + i++;
                config.set(path + ".uuid", entry.getKey().toString());
                config.set(path + ".expire", entry.getValue());
            }
            return config.saveToString();
        });
    }

    public void loadData() {
        File file = new File(plugin.getDataFolder(), "souls.yml"); if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file); imprisonedPlayers.clear();
        if (config.contains("imprisoned")) {
            for (String key : config.getConfigurationSection("imprisoned").getKeys(false)) {
                String path = "imprisoned." + key; UUID pUUID = UUID.fromString(config.getString(path + ".uuid")); long expire = config.getLong(path + ".expire");
                if (expire > System.currentTimeMillis()) { imprisonedPlayers.put(pUUID, expire); }
            }
        }
    }

    private record SoulScoreboard(Scoreboard scoreboard, Team timer) {}
}
