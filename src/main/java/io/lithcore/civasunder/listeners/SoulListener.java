package io.lithcore.civasunder.listeners;

import io.lithcore.civasunder.CivAsunder;
import io.lithcore.civasunder.managers.SoulManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.UUID;

public class SoulListener implements Listener {

    private final CivAsunder plugin;
    private final SoulManager soulManager;

    public SoulListener(CivAsunder plugin, SoulManager soulManager) {
        this.plugin = plugin;
        this.soulManager = soulManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSoulTotemResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        EquipmentSlot hand = event.getHand();
        if (hand == null) return;

        ItemStack usedTotem = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (usedTotem.getType() != Material.TOTEM_OF_UNDYING || !usedTotem.hasItemMeta()) return;

        NamespacedKey ownerKey = new NamespacedKey(plugin, "soul_owner_uuid");
        if (usedTotem.getItemMeta().getPersistentDataContainer()
                .has(ownerKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (victim.getWorld().getName().equals("civ_souls")) {
            return;
        }

        UUID vUUID = victim.getUniqueId();
        ItemStack soulItem = soulManager.createSoulItem(vUUID, victim.getName());
        event.getDrops().add(soulItem);

        long expireTime = System.currentTimeMillis() + (soulManager.getBaseSoulDuration() * 1000L);
        soulManager.getImprisonedPlayers().put(vUUID, expireTime);

        sendRaw(victim, "{\"text\":\"[Души] Вы погибли! Ваша душа низвергнута в шерстяное Чистилище!\",\"color\":\"dark_red\"}");
    }


    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSoulRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID pUUID = player.getUniqueId();
        
        // Железное правило: если игрок сидит в тюрьме душ ИЛИ физически возрождается в мире civ_souls,
        // он БЕЗУСЛОВНО остается в Чистилище. Никаких побегов через экран смерти и сундуки!
        boolean isImprisoned = soulManager.getImprisonedPlayers().containsKey(pUUID);
        boolean isInSoulWorld = player.getWorld().getName().equals("civ_souls");
        
        if ((isImprisoned || isInSoulWorld) && soulManager.getSoulWorld() != null) {
            Location soulSpawn = new Location(soulManager.getSoulWorld(), 0.5, 61.0, 0.5);
            event.setRespawnLocation(soulSpawn);

            Bukkit.getScheduler().runTask(plugin, () -> {
                player.setGameMode(org.bukkit.GameMode.SURVIVAL);
                player.getInventory().clear();
                
                // Выдаем радужную палитру шерсти для бесконечного творчества
                player.getInventory().addItem(new ItemStack(Material.WHITE_WOOL, 64));
                player.getInventory().addItem(new ItemStack(Material.BLACK_WOOL, 64));
                player.getInventory().addItem(new ItemStack(Material.RED_WOOL, 64));
                player.getInventory().addItem(new ItemStack(Material.BLUE_WOOL, 64));
                player.getInventory().addItem(new ItemStack(Material.GREEN_WOOL, 64));
                player.getInventory().addItem(new ItemStack(Material.YELLOW_WOOL, 64));
                player.getInventory().addItem(new ItemStack(Material.PURPLE_WOOL, 64));
                
                sendRaw(player, "{\"text\":\"[Чистилище] Добро пожаловать на креативную каторгу! Вам выданы блоки. Творите!\",\"color\":\"light_purple\"}");
            });
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (player.getWorld().getName().equals("civ_souls")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (player.getWorld().getName().equals("civ_souls")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSoulMoveRestriction(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (soulManager.getImprisonedPlayers().containsKey(player.getUniqueId())) {
            if (!player.getWorld().getName().equals("civ_souls")) {
                Location soulSpawn = new Location(soulManager.getSoulWorld(), 0, 61, 0);
                player.teleport(soulSpawn);
            }
        }
    }

    @EventHandler
    public void onSoulReleaseClick(PlayerInteractEvent event) {
        if (!event.getAction().name().contains("RIGHT_CLICK")) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        NamespacedKey ownerKey = new NamespacedKey(plugin, "soul_owner_uuid");
        
        if (meta.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
            
            String targetUUIDStr = meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            UUID targetUUID = UUID.fromString(targetUUIDStr);

            item.setAmount(item.getAmount() - 1);
            soulManager.getImprisonedPlayers().remove(targetUUID);

            player.playSound(player.getLocation(), org.bukkit.Sound.ITEM_TOTEM_USE, 1.0f, 1.5f);
            sendRaw(player, "{\"text\":\"[Души] Вы успешно разбили тотем и освободили душу игрока!\",\"color\":\"green\"}");

            Player targetPlayer = Bukkit.getPlayer(targetUUID);
            if (targetPlayer != null && targetPlayer.isOnline()) {
                targetPlayer.getInventory().clear();
                targetPlayer.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                targetPlayer.setGameMode(org.bukkit.GameMode.SURVIVAL);
                org.bukkit.Location bedLoc = targetPlayer.getRespawnLocation();
                if (bedLoc != null) { targetPlayer.teleport(bedLoc); }
                else { targetPlayer.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()); }
                targetPlayer.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                targetPlayer.setFallDistance(0.0f);
                sendRaw(targetPlayer, "{\"text\":\"[Души] Ваша душа была освобождена! Вы воскрешены на главном спавне.\",\"color\":\"green\"}");
            }
        }
    }

    
    /**
     * ТЗ: Проверка при входе. Если игрока освободили в офлайне, но он остался в мире душ — эвакуируем его!
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSoulPlayerJoinCheck(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID pUUID = player.getUniqueId();
        
        // Сценарий А: Игрок зашел, находится в Чистилище, но его УЖЕ НЕТ в списке заключенных (его выкупили/время вышло)
        if (player.getWorld().getName().equals("civ_souls") && !soulManager.getImprisonedPlayers().containsKey(pUUID)) {
            player.getInventory().clear();
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            
            // Безопасно перемещаем на спавн основного мира в следующем тике
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
                sendRaw(player, "{\"text\":\"[Души] Пока вы были вне сети, ваша душа освободилась! Вы возвращены в реальный мир.\",\"color\":\"green\"}");
            });
            return;
        }

        // Сценарий Б: Игрок зашел, его душа ВСЁ ЕЩЁ в списке заключенных, но он почему-то очутился в обычном мире (после рестарта)
        if (!player.getWorld().getName().equals("civ_souls") && soulManager.getImprisonedPlayers().containsKey(pUUID)) {
            if (soulManager.getSoulWorld() != null) {
                Location soulSpawn = new Location(soulManager.getSoulWorld(), 0, 61, 0);
                player.getInventory().clear();
                player.setGameMode(org.bukkit.GameMode.SURVIVAL);
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.teleport(soulSpawn);
                    sendRaw(player, "{\"text\":\"[Чистилище] Ваша душа всё ещё скована. Возвращайтесь на каторгу!\",\"color\":\"red\"}");
                });
            }
        }
    }

    
    /**
     * Слежка: Если предмет души деспавнится (пролежал 5 минут на земле) — освобождаем игрока
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulItemDespawn(org.bukkit.event.entity.ItemDespawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (stack.getType() == Material.TOTEM_OF_UNDYING && stack.hasItemMeta()) {
            NamespacedKey ownerKey = new NamespacedKey(plugin, "soul_owner_uuid");
            String uuidStr = stack.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (uuidStr != null) {
                processItemDestruction(UUID.fromString(uuidStr), "[Души] Предмет вашей души деспавнился в реальном мире! Вы свободны.");
            }
        }
    }

    /**
     * Слежка: Если предмет души уничтожен взрывом или кактусом на земле (кроме лавы/огня, у них свой таймер)
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulItemDamageDestroy(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Item)) return;
        
        org.bukkit.entity.Item itemEntity = (org.bukkit.entity.Item) event.getEntity();
        ItemStack stack = itemEntity.getItemStack();
        
        if (stack.getType() != Material.TOTEM_OF_UNDYING || !stack.hasItemMeta()) return;
        
        NamespacedKey ownerKey = new NamespacedKey(plugin, "soul_owner_uuid");
        String uuidStr = stack.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (uuidStr == null) return;

        // Если урон смертельный для предмета (ХП дропа на земле обычно = 5)
        if (itemEntity.getHealth() - event.getFinalDamage() <= 0) {
            EntityDamageEvent.DamageCause cause = event.getCause();
            // Игнорируем огонь и лаву, так как для них выше работает наш кастомный 10-секундный таймер
            if (cause != EntityDamageEvent.DamageCause.LAVA && cause != EntityDamageEvent.DamageCause.FIRE && cause != EntityDamageEvent.DamageCause.FIRE_TICK) {
                processItemDestruction(UUID.fromString(uuidStr), "[Души] Предмет вашей души был уничтожен в реальном мире! Вы свободны.");
            }
        }
    }

    /**
     * Вспомогательный метод: Безопасное мгновенное освобождение при уничтожении тотема
     */
    private void processItemDestruction(UUID targetUUID, String message) {
        if (soulManager.getImprisonedPlayers().containsKey(targetUUID)) {
            soulManager.getImprisonedPlayers().remove(targetUUID);
            
            Player p = Bukkit.getPlayer(targetUUID);
            if (p != null && p.isOnline()) {
                p.getInventory().clear();
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                p.setGameMode(org.bukkit.GameMode.SURVIVAL);
                
                Location bedLoc = p.getRespawnLocation();
                if (bedLoc != null) { p.teleport(bedLoc); }
                else { p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation()); }
                
                sendRaw(p, "{\"text\":\"" + message + "\",\"color\":\"green\"}");
            }
        }
    }

    private void sendRaw(Player p, String json) {
        io.lithcore.civasunder.util.MessageUtil.sendJson(p, json);
    }

    /**
     * ТЗ: Предмет души не сгорает в лаве мгновенно, а горит ровно 10 секунд, давая шанс его спасти
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSoulItemBurn(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Item)) return;
        
        org.bukkit.entity.Item itemEntity = (org.bukkit.entity.Item) event.getEntity();
        ItemStack stack = itemEntity.getItemStack();
        
        if (stack.getType() != Material.TOTEM_OF_UNDYING || !stack.hasItemMeta()) return;
        
        NamespacedKey ownerKey = new NamespacedKey(plugin, "soul_owner_uuid");
        if (!stack.getItemMeta().getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) return;
        
        // Проверяем типы урона: огонь, лава, поджог
        org.bukkit.event.entity.EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE || 
            cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.LAVA || 
            cause == org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE_TICK) {
            
            event.setCancelled(true); // Отменяем мгновенное ванильное уничтожение в лаве
            
            // Если для этого предмета еще не запущен таймер уничтожения, запускаем его на 10 секунд (200 тиков)
            if (!itemEntity.hasMetadata("soul_burning_task")) {
                itemEntity.setMetadata("soul_burning_task", new org.bukkit.metadata.FixedMetadataValue(plugin, true));
                
                // Делаем предмет визуально горящим
                itemEntity.setFireTicks(200);
                
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (itemEntity.isValid()) {
                        String targetUUIDStr = stack.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                        if (targetUUIDStr != null) {
                            UUID targetUUID = UUID.fromString(targetUUIDStr);
                            // Эффект рассеивания дымом
                            itemEntity.getWorld().spawnParticle(org.bukkit.Particle.LARGE_SMOKE, itemEntity.getLocation(), 20, 0.2, 0.2, 0.2, 0.0);
itemEntity.remove();
                            processItemDestruction(targetUUID, "[Души] Предмет вашей души окончательно сгорел в лаве! Вы свободны.");
                            
                            // Тикер SoulManager на следующем круге увидит, что предмета больше нет в мире, и освободит игрока!
                        }
                    }
                }, 200L); // 10 секунд задержки
            }
        }
    }
}
