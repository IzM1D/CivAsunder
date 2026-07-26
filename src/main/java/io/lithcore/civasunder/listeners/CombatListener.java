package io.lithcore.civasunder.listeners;

import io.lithcore.civasunder.managers.CombatManager;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public class CombatListener implements Listener {

    private final CombatManager combatManager;

    public CombatListener(CombatManager combatManager) {
        this.combatManager = combatManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        combatManager.activatePersistedState(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockRightClick(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Player player = event.getPlayer();
            ItemStack usedItem = event.getItem();
            if (!isBlockingItem(usedItem)) return;

            // ЖЕСТКИЙ ФИКС: Если щит залочен, полностью аннулируем любое прожатие ПКМ, очищая статус блока
            if (combatManager.isShieldLocked(player) || combatManager.getFatigue(player) >= 100.0) {
                event.setCancelled(true);
                combatManager.setBlocking(player, false);
                return;
            }

            combatManager.setBlocking(player, true);
        }
    }

    @EventHandler
    public void onItemChange(PlayerItemHeldEvent event) {
        combatManager.setBlocking(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShieldParryAttack(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = null;
        if (event.getDamager() instanceof Player) {
            attacker = (Player) event.getDamager();
        } else if (event.getDamager() instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player) {
                attacker = (Player) projectile.getShooter();
            }
        }

        if (attacker == null || victim == attacker) {
            return;
        }

        // A combat block and a melee attack must be mutually exclusive. Use
        // the short-lived input state here, rather than fatigue, so releasing
        // the block allows attacking without resetting the accumulated value.
        if (event.getDamager() instanceof Player
                && combatManager.isCombatBlocking(attacker)
                && hasBlockingItem(attacker)) {
            event.setCancelled(true);
            return;
        }

        // Окно суперкрита по пробитому игроку
        if (combatManager.isShieldLocked(victim) && combatManager.isCriticalStrikesLocked(victim)) {
            event.setDamage(event.getDamage() * 1.75);
            
            org.bukkit.Location hitLoc = victim.getLocation().add(0, 1.2, 0).add(attacker.getLocation().getDirection().multiply(0.3));
            Vector attackerDir = attacker.getLocation().getDirection().setY(0).normalize();
            Vector leftInertia = new Vector(attackerDir.getZ(), 0.1, -attackerDir.getX()).normalize().multiply(0.5);

            victim.getWorld().spawnParticle(Particle.LAVA, hitLoc, 0, leftInertia.getX(), leftInertia.getY(), leftInertia.getZ(), 1.0);
            victim.getWorld().spawnParticle(Particle.ENCHANTED_HIT, hitLoc, 15, 0.2, 0.2, 0.2, 0.2);
            victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 0.6f);
            return;
        }

        // Блокирование удара оружием
        if (combatManager.isCombatBlocking(victim) && !combatManager.isShieldLocked(victim)) {
            if (hasBlockingItem(victim) && combatManager.isFacingAttacker(victim, attacker)) {
                event.setDamage(event.getDamage() * 0.5);

                double currentFatigue = combatManager.getFatigue(victim);
                double nextFatigue = currentFatigue + 35.0;
                combatManager.setFatigue(victim, nextFatigue);

                if (nextFatigue >= 100.0) {
                    combatManager.lockShield(victim, 5000L);
                    combatManager.lockCriticalStrikes(victim, 1000L);
                    combatManager.setBlocking(victim, false);
                    
                    victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.3f, 0.6f);
                    victim.getWorld().spawnParticle(Particle.SMOKE, victim.getLocation().add(0, 1, 0), 30, 0.3, 0.5, 0.3, 0.05);
                } else {
                    combatManager.lockCriticalStrikes(attacker, 3000L);
                    combatManager.grantCounterAttack(victim, 1500L);
                    victim.getWorld().playSound(victim.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.3f);
                }

                org.bukkit.Location hitLoc = victim.getLocation().add(0, 1.2, 0).add(attacker.getLocation().getDirection().multiply(0.3));
                Vector attackerDir = attacker.getLocation().getDirection().setY(0).normalize();
                Vector leftInertia = new Vector(attackerDir.getZ(), 0.1, -attackerDir.getX()).normalize().multiply(0.4);

                victim.getWorld().spawnParticle(Particle.LAVA, hitLoc, 0, leftInertia.getX(), leftInertia.getY(), leftInertia.getZ(), 0.8);
                victim.getWorld().spawnParticle(Particle.CRIT, hitLoc, 20, 0.2, 0.2, 0.2, 0.2);
                return; 
            }
        }

        boolean isCritAttempt = attacker.getFallDistance() > 0.0F && !attacker.isOnGround() && !attacker.isClimbing();
        
        if (isCritAttempt) {
            if (combatManager.isCriticalStrikesLocked(attacker)) {
                event.setDamage(event.getDamage() / 1.5);
                attacker.getWorld().spawnParticle(Particle.SMOKE, attacker.getLocation().add(0, 1, 0), 12, 0.2, 0.2, 0.2, 0.01);
                attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.5f, 1.5f);
            }
        }

        if (combatManager.hasCounterAttack(attacker)) {
            event.setDamage(event.getDamage() * 1.75);
            combatManager.consumeCounterAttack(attacker);
            
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 0.7f);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 2.0f);
            attacker.getWorld().spawnParticle(Particle.ENCHANTED_HIT, attacker.getLocation().add(0, 1, 0), 25, 0.4, 0.4, 0.4, 0.5);
        }
    }

    private boolean isWeapon(ItemStack item) {
        if (item == null) return false;
        Material type = item.getType();
        String name = type.name();
        return name.contains("_SWORD") || name.contains("_AXE");
    }

    private boolean isBlockingItem(ItemStack item) {
        return isWeapon(item) || (item != null && item.getType() == Material.SHIELD);
    }

    private boolean hasBlockingItem(Player player) {
        return isWeapon(player.getInventory().getItemInMainHand())
                || player.getInventory().getItemInMainHand().getType() == Material.SHIELD
                || player.getInventory().getItemInOffHand().getType() == Material.SHIELD;
    }
}
