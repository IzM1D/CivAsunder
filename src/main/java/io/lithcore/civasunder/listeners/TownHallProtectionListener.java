package io.lithcore.civasunder.listeners;

import io.lithcore.civasunder.CivAsunder;
import io.lithcore.civasunder.managers.KingdomManager;
import io.lithcore.civasunder.managers.TownHallManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.Iterator;

public class TownHallProtectionListener implements Listener {

    private final CivAsunder plugin;
    private final TownHallManager townHallManager;
    private final KingdomManager kingdomManager;

    public TownHallProtectionListener(CivAsunder plugin, TownHallManager townHallManager, KingdomManager kingdomManager) {
        this.plugin = plugin;
        this.townHallManager = townHallManager;
        this.kingdomManager = kingdomManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onContainerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        Material type = block.getType();
        if (!type.name().contains("CHEST") && !type.name().contains("FURNACE") && 
            type != Material.BARREL && !type.name().contains("SHULKER_BOX") && type != Material.HOPPER) {
            return;
        }

        Location loc = block.getLocation();
        TownHallManager.TownHallData th = townHallManager.getProtectingTownHall(loc);
        if (th == null) return;

        Player player = event.getPlayer();
        if (!townHallManager.isCitizen(player.getUniqueId(), th, kingdomManager)) {
            event.setCancelled(true);
            sendRaw(player, "{\"text\":\"[Город] Взаимодействие с контейнерами на чужой территории заблокировано!\",\"color\":\"red\"}");
        }
    }

    
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location loc = block.getLocation();

        TownHallManager.TownHallData th = townHallManager.getProtectingTownHall(loc);
        if (th == null) return;

        boolean isCitizen = townHallManager.isCitizen(player.getUniqueId(), th, kingdomManager);

        if (isCitizen) {
            if (townHallManager.getReinforcedBlocks().containsKey(loc)) {
                int durability = townHallManager.getReinforcedBlocks().remove(loc);
                int refundAmount = (int) Math.floor(durability * 0.7);
                if (durability > 0 && refundAmount == 0) refundAmount = 1;

                boolean isCore = (block.getType() == Material.CARTOGRAPHY_TABLE || block.getType() == Material.FLETCHING_TABLE) 
                        && townHallManager.getTownHalls().containsKey(loc);
                
                if (refundAmount > 0 && !isCore) {
                    ItemStack energyShard = new ItemStack(Material.AMETHYST_SHARD, refundAmount);
                    ItemMeta meta = energyShard.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName("§eЭссенция Материи");
                        meta.getPersistentDataContainer().set(
                            new org.bukkit.NamespacedKey(plugin, "is_matter_currency"), 
                            org.bukkit.persistence.PersistentDataType.BYTE, 
                            (byte) 1
                        );
                        energyShard.setItemMeta(meta);
                    }
                    loc.getWorld().dropItemNaturally(loc, energyShard);
                }
            }

            if ((block.getType() == Material.CARTOGRAPHY_TABLE || block.getType() == Material.FLETCHING_TABLE) 
                    && townHallManager.getTownHalls().containsKey(loc)) {
                if (event.isDropItems()) {
                    event.setDropItems(false);
                    loc.getWorld().dropItemNaturally(loc, townHallManager.createTownHallItem());
                }
                townHallManager.removeTownHall(loc);
                sendRaw(player, "{\"text\":\"[Ратуша] Вы добровольно демонтировали Ратушу вашего города!\",\"color\":\"yellow\"}");
            }
            return;
        }

        event.setCancelled(true);
        int currentDurability = townHallManager.getReinforcedBlocks().getOrDefault(loc, 0);
        boolean isCoreBlock = (block.getType() == Material.CARTOGRAPHY_TABLE || block.getType() == Material.FLETCHING_TABLE) 
                && townHallManager.getTownHalls().containsKey(loc);

        if (currentDurability > 0) {
            int newDurability = currentDurability - 1;
            applyToolDamage(player);

            if (newDurability <= 0) {
                townHallManager.getReinforcedBlocks().remove(loc);
                if (isCoreBlock) {
                    townHallManager.removeTownHall(loc);
                    block.setType(Material.AIR);
                    Bukkit.broadcastMessage("§4§l[ОСАДА] Ратуша игрока " + Bukkit.getOfflinePlayer(th.mayorUUID).getName() + " была полностью УНИЧТОЖЕНА штурмом врагов!");
                } else {
                    block.breakNaturally();
                }
            } else {
                townHallManager.getReinforcedBlocks().put(loc, newDurability);
                if (isCoreBlock) {
                    sendRaw(player, "{\"text\":\"[ШТУРМ] Вы бьете по Ядру Ратуши! Оставшаяся прочность ядра: " + newDurability + "\",\"color\":\"dark_red\"}");
                } else {
                    sendRaw(player, "{\"text\":\"[ОСАДА] Блок защищен! Оставшаяся прочность блока: " + newDurability + "\",\"color\":\"red\"}");
                }
            }
        } else {
            if (isCoreBlock) {
                townHallManager.removeTownHall(loc);
                block.setType(Material.AIR);
                Bukkit.broadcastMessage("§4§l[ОСАДА] Ратуша города была полностью РАЗРУШЕНА врагами!");
            } else {
                applyToolDamage(player);
                block.breakNaturally();
            }
        }
    }

    private void applyToolDamage(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) return;

        int maxDamage = item.getType().getMaxDurability();
        if (maxDamage <= 0) return;

        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable) {
            Damageable damageable = (Damageable) meta;
            int currentDamage = damageable.getDamage();
            int newDamage = currentDamage + 1;
            
            if (newDamage >= maxDamage) {
                player.getInventory().setItemInMainHand(null);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            } else {
                damageable.setDamage(newDamage);
                item.setItemMeta(meta);
            }
        }
    }

    
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosionBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosionBlocks(event.blockList());
    }

    private void handleExplosionBlocks(java.util.List<Block> blockList) {
        Iterator<Block> iterator = blockList.iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();
            Location loc = block.getLocation();
            
            TownHallManager.TownHallData th = townHallManager.getProtectingTownHall(loc);
            if (th == null) continue;

            int currentDurability = townHallManager.getReinforcedBlocks().getOrDefault(loc, 0);
            boolean isCoreBlock = (block.getType() == Material.CARTOGRAPHY_TABLE || block.getType() == Material.FLETCHING_TABLE) 
                    && townHallManager.getTownHalls().containsKey(loc);

            if (currentDurability > 0 || isCoreBlock) {
                iterator.remove(); // Защищаем блок от физического уничтожения взрывом

                int damage = 3; // Снимаем 3 единицы прочности за взрыв
                int newDurability = Math.max(0, currentDurability - damage);

                if (newDurability <= 0 && !isCoreBlock) {
                    townHallManager.getReinforcedBlocks().remove(loc);
                    Bukkit.getScheduler().runTask(plugin, () -> block.setType(Material.AIR));
                } else if (isCoreBlock && currentDurability - damage <= 0) {
                    townHallManager.getReinforcedBlocks().remove(loc);
                    townHallManager.removeTownHall(loc);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        block.setType(Material.AIR);
                        Bukkit.broadcastMessage("§4§l[ОСАДА] Ядро Ратуши пало в результате разрушительного ВЗРЫВА!");
                    });
                } else {
                    townHallManager.getReinforcedBlocks().put(loc, newDurability);
                }
            }
        }
    }

    private void sendRaw(Player p, String json) {
        io.lithcore.civasunder.util.MessageUtil.sendJson(p, json);
    }
}
