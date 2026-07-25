package io.lithcore.civasunder.listeners;

import io.lithcore.civasunder.CivAsunder;
import io.lithcore.civasunder.managers.SoulManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SoulAltarListener implements Listener {

    private final CivAsunder plugin;
    private final SoulManager soulManager;

    private final int SOUL_SLOT = 10;
    private final int TRIGGER_SLOT = 16;
    private final int[] ENERGY_SLOTS = {12, 13, 14, 15};

    public SoulAltarListener(CivAsunder plugin, SoulManager soulManager) {
        this.plugin = plugin;
        this.soulManager = soulManager;
    }

    private boolean isEnergySlot(int slot) {
        for (int s : ENERGY_SLOTS) { if (s == slot) return true; }
        return false;
    }

    public ItemStack createAltarItem() {
        ItemStack altar = new ItemStack(Material.CRYING_OBSIDIAN);
        ItemMeta meta = altar.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§5§lАлтарь Заточения Душ");
            List<String> lore = new ArrayList<>();
            lore.add("§7Поставьте этот блок, чтобы");
            lore.add("§7проводить ритуалы продления");
            lore.add("§7заключения чужих душ.");
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "is_soul_altar"), PersistentDataType.BYTE, (byte) 1);
            altar.setItemMeta(meta);
        }
        return altar;
    }

    public void registerAltarRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "soul_altar_recipe");
        if (Bukkit.getRecipe(key) != null) return;
        ShapedRecipe recipe = new ShapedRecipe(key, createAltarItem());
        recipe.shape("EEE", "EOE", "EEE");
        recipe.setIngredient('O', Material.OBSIDIAN);
        recipe.setIngredient('E', Material.AMETHYST_SHARD);
        Bukkit.addRecipe(recipe);
    }

    @EventHandler
    public void onAltarCraftPrepare(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null || event.getRecipe().getResult() == null) return;
        ItemStack result = event.getRecipe().getResult();
        if (result.getType() == Material.CRYING_OBSIDIAN && result.hasItemMeta() && 
            result.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin, "is_soul_altar"), PersistentDataType.BYTE)) {
            CraftingInventory inv = event.getInventory();
            ItemStack[] matrix = inv.getMatrix();
            NamespacedKey currencyKey = new NamespacedKey(plugin, "is_matter_currency");
            for (int i = 0; i < matrix.length; i++) {
                if (i == 4) continue;
                ItemStack item = matrix[i];
                if (item == null || item.getType() != Material.AMETHYST_SHARD || !item.hasItemMeta() ||
                    !item.getItemMeta().getPersistentDataContainer().has(currencyKey, PersistentDataType.BYTE) || item.getAmount() < 4) {
                    inv.setResult(null);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onAltarCraft(CraftItemEvent event) {
        ItemStack result = event.getRecipe().getResult();
        if (result.getType() != Material.CRYING_OBSIDIAN || !result.hasItemMeta()) return;
        if (!result.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin, "is_soul_altar"), PersistentDataType.BYTE)) return;

        // Shift-click создаёт цикл крафтов с непредсказуемой матрицей — запрещаем, чтобы не потерять ресурсы игрока.
        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player p) {
                sendRaw(p, "{\"text\":\"[Алтарь] Крафт по одному: Shift-крафт запрещён для Алтаря Заточения.\",\"color\":\"red\"}");
            }
            return;
        }

        // Vanilla снимет по 1 осколку из каждой позиции. Досписываем ещё по 3, чтобы всего вышло 32 (4 × 8 углов).
        CraftingInventory inv = event.getInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack[] matrix = inv.getMatrix();
            for (int i = 0; i < matrix.length; i++) {
                if (i == 4) continue;
                ItemStack item = matrix[i];
                if (item == null || item.getType() != Material.AMETHYST_SHARD) continue;
                int amt = item.getAmount();
                if (amt <= 3) { matrix[i] = null; }
                else { item.setAmount(amt - 3); }
            }
            inv.setMatrix(matrix);
        });
    }

    @EventHandler
    public void onAltarInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.CRYING_OBSIDIAN) return;
        if (!soulManager.getAltars().contains(block.getLocation())) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        Inventory altarGui = Bukkit.createInventory(null, 27, "§5Алтарь Заточения Душ");
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta pMeta = pane.getItemMeta();
        if (pMeta != null) { pMeta.setDisplayName(" "); pane.setItemMeta(pMeta); }
        
        for (int i = 0; i < 27; i++) {
            if (i != SOUL_SLOT && !isEnergySlot(i) && i != TRIGGER_SLOT) { altarGui.setItem(i, pane); }
        }

        ItemStack trigger = new ItemStack(Material.NETHER_STAR);
        ItemMeta tMeta = trigger.getItemMeta();
        if (tMeta != null) {
            tMeta.setDisplayName("§d§lЗапустить Ритуал Продления");
            List<String> lore = new ArrayList<>();
            lore.add("§7Положите Душу в слот 11 [Левый]");
            lore.add("§7Разложите Энергию в 4 правых слота");
            lore.add("§7(Суммарно до 256 единиц энергии!)");
            lore.add("§7И нажмите на эту Звезду Незера");
            tMeta.setLore(lore); trigger.setItemMeta(tMeta);
        }
        altarGui.setItem(TRIGGER_SLOT, trigger);
        player.openInventory(altarGui);
    }

    @EventHandler
    public void onAltarGuiClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals("§5Алтарь Заточения Душ")) return;
        int slot = event.getRawSlot();
        if (slot < 27 && slot != SOUL_SLOT && !isEnergySlot(slot) && slot != TRIGGER_SLOT) {
            event.setCancelled(true); return;
        }

        Inventory inv = event.getInventory();
        if (slot == TRIGGER_SLOT) {
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            ItemStack soul = inv.getItem(SOUL_SLOT);
            if (soul == null || soul.getType() != Material.TOTEM_OF_UNDYING || !soul.hasItemMeta()) {
                sendRaw(player, "{\"text\":\"[Алтарь] Поместите Душу в центральный левый слот!\",\"color\":\"red\"}"); return;
            }

            ItemMeta sMeta = soul.getItemMeta();
            NamespacedKey ownerKey = new NamespacedKey(plugin, "soul_owner_uuid");
            NamespacedKey timeKey = new NamespacedKey(plugin, "soul_expire_time");
            NamespacedKey extKey = new NamespacedKey(plugin, "soul_extensions");
            if (!sMeta.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) return;

            UUID victimUUID = UUID.fromString(sMeta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING));
            long currentExpire = sMeta.getPersistentDataContainer().get(timeKey, PersistentDataType.LONG);
            int extensions = sMeta.getPersistentDataContainer().getOrDefault(extKey, PersistentDataType.INTEGER, 0);
            int cost = soulManager.getExtensionCost(extensions);

            int totalEnergyAvailable = 0;
            NamespacedKey currencyKey = new NamespacedKey(plugin, "is_matter_currency");
            for (int eSlot : ENERGY_SLOTS) {
                ItemStack item = inv.getItem(eSlot);
                if (item != null && item.getType() == Material.AMETHYST_SHARD && item.hasItemMeta()) {
                    if (item.getItemMeta().getPersistentDataContainer().has(currencyKey, PersistentDataType.BYTE)) { totalEnergyAvailable += item.getAmount(); }
                }
            }

            if (totalEnergyAvailable < cost) {
                sendRaw(player, "{\"text\":\"[Алтарь] Недостаточно Энергии! Требуется: " + cost + " шт. В алтаре: " + totalEnergyAvailable + " шт.\",\"color\":\"red\"}"); return;
            }

            int remainingToDeduct = cost;
            for (int eSlot : ENERGY_SLOTS) {
                if (remainingToDeduct <= 0) break;
                ItemStack item = inv.getItem(eSlot);
                if (item != null && item.getType() == Material.AMETHYST_SHARD && item.hasItemMeta()) {
                    if (item.getItemMeta().getPersistentDataContainer().has(currencyKey, PersistentDataType.BYTE)) {
                        int amount = item.getAmount();
                        if (amount <= remainingToDeduct) { remainingToDeduct -= amount; inv.setItem(eSlot, null); }
                        else { item.setAmount(amount - remainingToDeduct); remainingToDeduct = 0; }
                    }
                }
            }

            long extensionSeconds = soulManager.getExtensionDuration(extensions);
            long extensionMinutes = extensionSeconds / 60L;
            long newExpire = currentExpire + (extensionSeconds * 1000L); int newExtensions = extensions + 1;
            sMeta.getPersistentDataContainer().set(timeKey, PersistentDataType.LONG, newExpire);
            sMeta.getPersistentDataContainer().set(extKey, PersistentDataType.INTEGER, newExtensions);

            long secondsLeft = (newExpire - System.currentTimeMillis()) / 1000L; long minutes = secondsLeft / 60L; long secs = secondsLeft % 60L;
            List<String> lore = new ArrayList<>();
            lore.add("§7Статус: §cЗаточена (Продлено)"); lore.add("§7Осталось времени: §e" + minutes + " мин. " + secs + " сек.");
            lore.add("§7Продлений в алтаре: §a" + newExtensions); lore.add("§7Следующее продление: §d" + soulManager.getExtensionCost(newExtensions) + " Энергии");
            soul.setItemMeta(sMeta); soulManager.getImprisonedPlayers().put(victimUUID, newExpire);

            Player victimPlayer = Bukkit.getPlayer(victimUUID);
            if (victimPlayer != null && victimPlayer.isOnline()) {
                sendRaw(victimPlayer, "{\"text\":\"[Чистилище] Тьма сгущается... Кто-то внес Энергию в Алтарь. Ваше заточение ПРОДЛЕНО на +" + extensionMinutes + " минут!\",\"color\":\"dark_purple\"}");
            }
            player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_END_PORTAL_SPAWN, 1.0f, 1.0f);
            sendRaw(player, "{\"text\":\"[Алтарь] Ритуал завершен! Списано: " + cost + " Энергии.\",\"color\":\"dark_purple\"}");
        }
    }

    @EventHandler
    public void onAltarClose(InventoryCloseEvent event) {
        if (!event.getView().getTitle().equals("§5Алтарь Заточения Душ")) return;
        Inventory inv = event.getInventory(); Player player = (Player) event.getPlayer();
        ItemStack soul = inv.getItem(SOUL_SLOT);
        if (soul != null && soul.getType() != Material.AIR) { player.getWorld().dropItemNaturally(player.getLocation(), soul); inv.setItem(SOUL_SLOT, null); }
        for (int eSlot : ENERGY_SLOTS) {
            ItemStack energy = inv.getItem(eSlot);
            if (energy != null && energy.getType() != Material.AIR) { player.getWorld().dropItemNaturally(player.getLocation(), energy); inv.setItem(eSlot, null); }
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAltarPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getType() == Material.CRYING_OBSIDIAN && item.hasItemMeta() &&
            item.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin, "is_soul_altar"), PersistentDataType.BYTE)) {
            soulManager.getAltars().add(event.getBlockPlaced().getLocation());
            sendRaw(event.getPlayer(), "{\"text\":\"[CivAsunder] Вы успешно установили Алтарь Заточения Душ!\",\"color\":\"green\"}");
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAltarBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.CRYING_OBSIDIAN && soulManager.getAltars().contains(block.getLocation())) {
            event.setDropItems(false); soulManager.getAltars().remove(block.getLocation());
            block.getWorld().dropItemNaturally(block.getLocation(), createAltarItem());
            sendRaw(event.getPlayer(), "{\"text\":\"[CivAsunder] Алтарь Заточения Душ успешно демонтирован.\",\"color\":\"yellow\"}");
        }
    }

    private void sendRaw(Player p, String json) {
        io.lithcore.civasunder.util.MessageUtil.sendJson(p, json);
    }
}
