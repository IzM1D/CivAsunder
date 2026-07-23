package io.lithcore.civasunder.listeners;

import io.lithcore.civasunder.CivAsunder;
import io.lithcore.civasunder.managers.KingdomManager;
import io.lithcore.civasunder.managers.TownHallManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class TownHallListener implements Listener {

    private final CivAsunder plugin;
    private final TownHallManager townHallManager;
    private final KingdomManager kingdomManager;

    public TownHallListener(CivAsunder plugin, TownHallManager townHallManager, KingdomManager kingdomManager) {
        this.plugin = plugin;
        this.townHallManager = townHallManager;
        this.kingdomManager = kingdomManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTownHallPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();

        if (item.getType() != Material.CARTOGRAPHY_TABLE && item.getType() != Material.FLETCHING_TABLE) {
            return;
        }
        if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName() || !item.getItemMeta().getDisplayName().contains("Блок Ратуши")) {
            return;
        }

        Location loc = event.getBlockPlaced().getLocation();
        // Ратуша создается с 0 уровнем влитой энергии (базовый радиус 16)
        TownHallManager.TownHallData data = new TownHallManager.TownHallData(loc, player.getUniqueId(), 0);
        townHallManager.addTownHall(loc, data);

        // Изначально блок Ратуши получает 10 единиц базовой прочности, как и любой блок вокруг ратуши
        townHallManager.getReinforcedBlocks().put(loc, 10);

        String json = "{\"text\":\"[Ратуша] Вы успешно основали Ратушу! Базовый радиус: 16 блоков. Прочность ядра: 10.\",\"color\":\"gold\"}";
        io.lithcore.civasunder.util.MessageUtil.sendJson(player, json);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTownHallInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        boolean holdsEnergy = false;
        
        if (item != null && item.getType() == Material.AMETHYST_SHARD && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            NamespacedKey key = new NamespacedKey(plugin, "is_matter_currency");
            if (meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                holdsEnergy = true;
            }
        }

        Location bLoc = block.getLocation();
        Material bType = block.getType();

        // СЦЕНАРИЙ А: Клик по самому блоку Ратуши (может быть как Столом Картографа, так и Столом Лучника)
        if ((bType == Material.CARTOGRAPHY_TABLE || bType == Material.FLETCHING_TABLE) && townHallManager.getTownHalls().containsKey(bLoc)) {
            event.setCancelled(true);
            TownHallManager.TownHallData th = townHallManager.getTownHalls().get(bLoc);

            if (!th.mayorUUID.equals(player.getUniqueId()) && !townHallManager.isCitizen(player.getUniqueId(), th, kingdomManager)) {
                sendRaw(player, "{\"text\":\"[Ратуша] Только граждане этого города могут улучшать Ратушу!\",\"color\":\"red\"}");
                return;
            }

            if (holdsEnergy) {
                // Проверяем, хватает ли у игрока ровно 10 единиц Энергии в руке
                if (item.getAmount() < 10) {
                    sendRaw(player, "{\"text\":\"[Ратуша] Недостаточно ресурсов! Для прокачки Ратуши требуется ровно 10 единиц Энергии!\",\"color\":\"red\"}");
                    return;
                }
                
                item.setAmount(item.getAmount() - 10);
                townHallManager.increaseEnergy(th); // Радиус увеличивается на +1 блок
                
                // Прочность самого блока Ратуши увеличивается ровно на +10 очков
                int currentDurability = townHallManager.getReinforcedBlocks().getOrDefault(bLoc, 10);
                townHallManager.getReinforcedBlocks().put(bLoc, currentDurability + 10);
                
                int currentRadius = 16 + th.energyLevel;
                sendRaw(player, "{\"text\":\"[Ратуша] 10 Энергии поглощено! Радиус расширен до " + currentRadius + " бл. Прочность ядра увеличилась на +10!\",\"color\":\"green\"}");
            } else {
                int radius = 16 + th.energyLevel;
                int dur = townHallManager.getReinforcedBlocks().getOrDefault(bLoc, 10);
                sendRaw(player, "{\"text\":\"[Ратуша] Радиус: " + radius + " бл. Прочность ядра: " + dur + ". Зажмите 10 Энергий в руке и кликните ПКМ для улучшения!\",\"color\":\"yellow\"}");
            }
            return;
        }

        // СЦЕНАРИЙ Б: Укрепление обычных блоков (1 Энергия = +1 к прочности)
        if (holdsEnergy) {
            // [ИСПРАВЛЕНО]: Внедряем проверку реестра запретов
            if (townHallManager.isBlockBlacklisted(block)) {
                sendRaw(player, "{\"text\":\"[Укрепление] Этот тип блоков (обсидиан/руда) находится в черном списке! Его нельзя укреплять!\",\"color\":\"red\"}");
                return;
            }

            TownHallManager.TownHallData protectingTh = townHallManager.getProtectingTownHall(bLoc);
            if (protectingTh != null && townHallManager.isCitizen(player.getUniqueId(), protectingTh, kingdomManager)) {
                event.setCancelled(true);
                item.setAmount(item.getAmount() - 1);
                
                // 1 Энергия = ровно +1 к прочности блока в памяти
                int currentReinforce = townHallManager.getReinforcedBlocks().getOrDefault(bLoc, 0);
                townHallManager.getReinforcedBlocks().put(bLoc, currentReinforce + 1);
                
                sendRaw(player, "{\"text\":\"[Укрепление] Блок укреплен! Добавлена +1 прочность. Текущая прочность: " + (currentReinforce + 1) + "\",\"color\":\"green\"}");
            }
        }
    }

    private void sendRaw(Player p, String json) {
        io.lithcore.civasunder.util.MessageUtil.sendJson(p, json);
    }
}
