package io.lithcore.civasunder.listeners;

import io.lithcore.civasunder.CivAsunder;
import io.lithcore.civasunder.managers.VeinManager;
import io.lithcore.civasunder.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CompassRadarListener implements Listener {

    private static final int SCAN_RADIUS_CHUNKS = VeinManager.SEARCH_RADIUS_CHUNKS;
    private static final int COOLDOWN_SECONDS = 10;

    private final VeinManager veinManager;
    private final NamespacedKey oreCompassKey;
    private final NamespacedKey matterCurrencyKey;
    private final NamespacedKey recipeKey;
    private final Map<UUID, Long> radarCooldowns = new ConcurrentHashMap<>();
    private long nextCooldownCleanup;

    public CompassRadarListener(CivAsunder plugin, VeinManager veinManager) {
        this.veinManager = veinManager;
        this.oreCompassKey = new NamespacedKey(plugin, "is_ore_search_compass");
        this.matterCurrencyKey = new NamespacedKey(plugin, "is_matter_currency");
        this.recipeKey = new NamespacedKey(plugin, "ore_search_compass_recipe");
    }

    public void registerRecipe() {
        if (Bukkit.getRecipe(recipeKey) != null) return;

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createOreSearchCompass());
        recipe.shape("PSP", "SES", "PSP");
        recipe.setIngredient('P', Material.STONE_PICKAXE);
        recipe.setIngredient('S', Material.STONE);
        // The PDC check in onCraftPrepare makes this the custom matter essence only.
        recipe.setIngredient('E', Material.AMETHYST_SHARD);
        Bukkit.addRecipe(recipe);
    }

    public ItemStack createOreSearchCompass() {
        ItemStack compass = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) compass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Компас поиска руд");
            meta.getPersistentDataContainer().set(oreCompassKey, PersistentDataType.BYTE, (byte) 1);
            compass.setItemMeta(meta);
        }
        return compass;
    }

    private boolean isOreSearchCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(oreCompassKey, PersistentDataType.BYTE);
    }

    private boolean isMatterEssence(ItemStack item) {
        if (item == null || item.getType() != Material.AMETHYST_SHARD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(matterCurrencyKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onCraftPrepare(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof Keyed keyed) || !recipeKey.equals(keyed.getKey())) return;

        CraftingInventory inventory = event.getInventory();
        ItemStack[] matrix = inventory.getMatrix();
        if (matrix.length < 5 || !isMatterEssence(matrix[4])) {
            inventory.setResult(null);
        }
    }

    @EventHandler
    public void onCompassInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack compass = event.getItem();
        if (compass == null || compass.getType() != Material.COMPASS) return;

        Player player = event.getPlayer();
        if (!isOreSearchCompass(compass)) {
            // The player-level target is kept at the vanilla world spawn. Ore targets are
            // stored in the custom item's CompassMeta and therefore do not affect this.
            player.setCompassTarget(player.getWorld().getSpawnLocation());
            return;
        }

        UUID uuid = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        if (currentTime >= nextCooldownCleanup) {
            radarCooldowns.entrySet().removeIf(entry -> entry.getValue() <= currentTime);
            nextCooldownCleanup = currentTime + 60_000L;
        }

        Long nextAvailableTime = radarCooldowns.get(uuid);
        if (nextAvailableTime != null && currentTime < nextAvailableTime) {
            long timeLeftSeconds = (nextAvailableTime - currentTime + 999) / 1000;
            MessageUtil.sendJson(player, "{\"text\":\"[Компас] Радар перезагружается! Подождите "
                    + timeLeftSeconds + " сек.\",\"color\":\"red\"}");
            event.setCancelled(true);
            return;
        }

        radarCooldowns.put(uuid, currentTime + COOLDOWN_SECONDS * 1000L);
        List<VeinManager.MapResult> results = veinManager.findNearestVeins(
                player, SCAN_RADIUS_CHUNKS, 1);

        if (!results.isEmpty()) {
            VeinManager.MapResult nearest = results.get(0);
            setItemTarget(compass, new Location(player.getWorld(),
                    nearest.geoX, nearest.geoY, nearest.geoZ));
        } else {
            clearItemTarget(compass);
            MessageUtil.sendJson(player, "{\"text\":\"[Компас] Сигналов природных рудных жил в радиусе "
                    + (SCAN_RADIUS_CHUNKS * 16) + " блоков не обнаружено.\",\"color\":\"red\"}");
        }

        event.setCancelled(true);
    }

    private void setItemTarget(ItemStack compass, Location target) {
        ItemMeta itemMeta = compass.getItemMeta();
        if (!(itemMeta instanceof CompassMeta meta)) return;
        meta.setLodestone(target);
        meta.setLodestoneTracked(false);
        compass.setItemMeta(meta);
    }

    private void clearItemTarget(ItemStack compass) {
        ItemMeta itemMeta = compass.getItemMeta();
        if (!(itemMeta instanceof CompassMeta meta)) return;
        meta.setLodestone(null);
        compass.setItemMeta(meta);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        resetVanillaCompassTarget(event.getPlayer());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        resetVanillaCompassTarget(event.getPlayer());
    }

    private void resetVanillaCompassTarget(Player player) {
        player.setCompassTarget(player.getWorld().getSpawnLocation());
    }
}
