package io.lithcore.civasunder.listeners;

import io.lithcore.civasunder.managers.KingdomManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KingdomBlockListener implements Listener {

    private final KingdomManager kingdomManager;
    // Карта для связи: UUID игрока -> Локация только что поставленного им незарегистрированного Сердца
    private final Map<UUID, Location> setupSessions = new ConcurrentHashMap<>();

    public KingdomBlockListener(KingdomManager kingdomManager) {
        this.kingdomManager = kingdomManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKingdomBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();

        if (item.getType() != Material.FLETCHING_TABLE || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) {
            return;
        }

        if (!item.getItemMeta().getDisplayName().contains("Блок Сердца Государства")) {
            return;
        }

        // Базовая проверка: Король не может поставить второе Сердце
        KingdomManager.KingdomData existingKingdom = kingdomManager.getKingdomByKing(player.getUniqueId());
        if (existingKingdom != null) {
                String errorJson = "{\"text\":\"[Государство] Вы уже являетесь Королем государства " + existingKingdom.name + "! Нельзя основать второе.\",\"color\":\"red\"}";
                io.lithcore.civasunder.util.MessageUtil.sendJson(player, errorJson);
                event.setCancelled(true);
                return;
        }

        Location blockLoc = event.getBlockPlaced().getLocation();
        setupSessions.put(player.getUniqueId(), blockLoc);

        // Отправляем JSON tellraw с инструкцией по команде
        String json = "{\"text\":\"[Государство] Блок Сердца установлен! Чтобы основать страну, введите команду: /k create [название]\",\"color\":\"gold\"}";
        io.lithcore.civasunder.util.MessageUtil.sendJson(player, json);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKingdomBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.FLETCHING_TABLE) {
            return;
        }

        Location loc = block.getLocation();
        // Если блок Сердца уничтожен (кем угодно по вашему ТЗ) — государство полностью ликвидируется
        if (kingdomManager.getBlockLocations().containsKey(loc)) {
            String kingdomKey = kingdomManager.getBlockLocations().remove(loc);
            KingdomManager.KingdomData data = kingdomManager.getKingdoms().remove(kingdomKey);
            kingdomManager.saveKingdomsToFile();

            if (data != null) {
                String broadcastJson = "[{\"text\":\"[CivAsunder] \",\"color\":\"gold\"},"
                    + "{\"text\":\"Блок Сердца уничтожен! Государство \",\"color\":\"dark_red\"},"
                    + "{\"text\":\"" + data.name + "\",\"color\":\"yellow\"},"
                    + "{\"text\":\" было полностью стерто с лица земли!\",\"color\":\"dark_red\"}]";
                    
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    io.lithcore.civasunder.util.MessageUtil.sendJson(onlinePlayer, broadcastJson);
                }
            }
        }
    }

    public Map<UUID, Location> getSetupSessions() {
        return setupSessions;
    }
}
