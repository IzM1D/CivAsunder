package io.lithcore.civasunder.managers;

import io.lithcore.civasunder.CivAsunder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public class CombatManager {

    private static final long BLOCK_INPUT_TIMEOUT_MS = 350L;
    private static final long FATIGUE_RECOVERY_DELAY_MS = 1000L;

    private final CivAsunder plugin;
    private final ConcurrentHashMap<UUID, Long> critLockTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> counterAttackTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> blockingPlayers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> fatigueRecoveryDelays = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> fatigueValues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> shieldBreakTimers = new ConcurrentHashMap<>();
    private final Set<UUID> activeCombatPlayers = ConcurrentHashMap.newKeySet();

    public CombatManager(CivAsunder plugin) {
        this.plugin = plugin;
        this.startCombatTicker();
    }

    public void setBlocking(Player player, boolean isBlocking) {
        UUID uuid = player.getUniqueId();
        if (isBlocking) {
            blockingPlayers.put(uuid, System.currentTimeMillis());
            fatigueRecoveryDelays.remove(uuid);
            activeCombatPlayers.add(uuid);
        } else {
            if (blockingPlayers.remove(uuid) != null) {
                fatigueRecoveryDelays.put(uuid, System.currentTimeMillis() + FATIGUE_RECOVERY_DELAY_MS);
            }
        }
    }

    public boolean isCombatBlocking(Player player) {
        // Shields send only one interaction when use starts, so their native
        // blocking state has to refresh the custom combat block continuously.
        if (player.isBlocking() && !isShieldLocked(player)) {
            setBlocking(player, true);
            return true;
        }

        Long lastBlockClick = blockingPlayers.get(player.getUniqueId());
        if (lastBlockClick == null) return false;
        
        if (System.currentTimeMillis() - lastBlockClick > BLOCK_INPUT_TIMEOUT_MS) {
            UUID uuid = player.getUniqueId();
            if (blockingPlayers.remove(uuid, lastBlockClick)) {
                fatigueRecoveryDelays.put(uuid, System.currentTimeMillis() + FATIGUE_RECOVERY_DELAY_MS);
            }
            return false;
        }
        return true;
    }

    private boolean isFatigueRecoveryDelayed(UUID uuid) {
        Long expireTime = fatigueRecoveryDelays.get(uuid);
        if (expireTime == null) return false;
        if (System.currentTimeMillis() > expireTime) {
            fatigueRecoveryDelays.remove(uuid, expireTime);
            return false;
        }
        return true;
    }

    public boolean isFacingAttacker(Player defender, Player attacker) {
        Vector defenderLook = defender.getLocation().getDirection().setY(0).normalize();
        Vector toAttacker = attacker.getLocation().toVector().subtract(defender.getLocation().toVector()).setY(0).normalize();
        return defenderLook.dot(toAttacker) > 0.5;
    }

    public double getFatigue(Player player) {
        return fatigueValues.getOrDefault(player.getUniqueId(), 0.0);
    }

    public void setFatigue(Player player, double value) {
        UUID uuid = player.getUniqueId();
        if (value <= 0.0) {
            fatigueValues.remove(uuid);
        } else {
            fatigueValues.put(uuid, Math.min(100.0, value));
            activeCombatPlayers.add(uuid);
        }
    }

    public void lockShield(Player player, long durationMs) {
        shieldBreakTimers.put(player.getUniqueId(), System.currentTimeMillis() + durationMs);
        int cooldownTicks = Math.max(1, (int) Math.ceil(durationMs / 50.0));
        player.setCooldown(Material.SHIELD, Math.max(player.getCooldown(Material.SHIELD), cooldownTicks));
        if (player.isBlocking()) player.clearActiveItem();
        activeCombatPlayers.add(player.getUniqueId());
    }

    public boolean isShieldLocked(Player player) {
        Long expireTime = shieldBreakTimers.get(player.getUniqueId());
        if (expireTime == null) return false;
        if (System.currentTimeMillis() > expireTime) {
            shieldBreakTimers.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public void lockCriticalStrikes(Player player, long durationMs) {
        critLockTimers.put(player.getUniqueId(), System.currentTimeMillis() + durationMs);
        activeCombatPlayers.add(player.getUniqueId());
    }

    public boolean isCriticalStrikesLocked(Player player) {
        Long expireTime = critLockTimers.get(player.getUniqueId());
        if (expireTime == null) return false;
        if (System.currentTimeMillis() > expireTime) {
            critLockTimers.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public void grantCounterAttack(Player player, long durationMs) {
        counterAttackTimers.put(player.getUniqueId(), System.currentTimeMillis() + durationMs);
        activeCombatPlayers.add(player.getUniqueId());
    }

    public boolean hasCounterAttack(Player player) {
        Long expireTime = counterAttackTimers.get(player.getUniqueId());
        if (expireTime == null) return false;
        if (System.currentTimeMillis() > expireTime) {
            counterAttackTimers.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public void consumeCounterAttack(Player player) {
        counterAttackTimers.remove(player.getUniqueId());
    }

    public void startCombatTicker() {
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : activeCombatPlayers) {
                    Player player = org.bukkit.Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) {
                        updateOfflineState(uuid, System.currentTimeMillis());
                        continue;
                    }
                    double fatigue = getFatigue(player);
                    boolean isBlocking = isCombatBlocking(player);
                    boolean shieldLocked = isShieldLocked(player);

                    if (!isBlocking && fatigue > 0.0 && !isFatigueRecoveryDelayed(uuid)) {
                        setFatigue(player, fatigue - 1.5);
                    } else if (isBlocking && !shieldLocked) {
                        double nextFatigue = fatigue + 1.0;
                        setFatigue(player, nextFatigue);
                        
                        // ФИКС: Если игрок пассивно удерживал блок слишком долго и устал на 100%
                        if (nextFatigue >= 100.0) {
                            lockShield(player, 5000L);          // Запрет блока на 5 секунд
                            lockCriticalStrikes(player, 1000L);  // Окно уязвимости для суперкритов на 1 секунду
                            setBlocking(player, false);
                            
                            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BREAK, 1.3f, 0.6f);
                            player.getWorld().spawnParticle(org.bukkit.Particle.SMOKE_NORMAL, player.getLocation().add(0, 1, 0), 30, 0.3, 0.5, 0.3, 0.05);
                            continue;
                        }
                    }

                    if (fatigue > 0.0 || isBlocking || shieldLocked) {
                        StringBuilder sb = new StringBuilder("§7Руки: [");
                        int bars = (int) (fatigue / 10.0);
                        for (int i = 0; i < 10; i++) {
                            if (i < bars) sb.append(shieldLocked ? "§c█" : "§e█");
                            else sb.append("§8░");
                        }
                        sb.append("§7] §f").append((int) fatigue).append("%");
                        if (shieldLocked) sb.append(" §c§l(ПРОБИТ)");
                        
                        sendSafeActionBar(player, sb.toString());
                    }

                    if (isBlocking && fatigue < 100.0 && !shieldLocked) {
                        player.getWorld().spawnParticle(org.bukkit.Particle.CRIT, player.getLocation().add(0, 1.8, 0), 1, 0.1, 0.1, 0.1, 0.01);
                    }

                    boolean criticalLocked = isCriticalStrikesLocked(player);
                    Long counterExpire = counterAttackTimers.get(uuid);
                    if (counterExpire != null && System.currentTimeMillis() > counterExpire) {
                        counterAttackTimers.remove(uuid, counterExpire);
                    }
                    if (criticalLocked || shieldLocked) {
                        player.getWorld().spawnParticle(org.bukkit.Particle.TOWN_AURA, player.getLocation().add(0, 1.0, 0), 2, 0.2, 0.4, 0.2, 0.01);
                    }
                    if (getFatigue(player) <= 0.0 && !blockingPlayers.containsKey(uuid)
                            && !shieldBreakTimers.containsKey(uuid) && !critLockTimers.containsKey(uuid)) {
                        fatigueRecoveryDelays.remove(uuid);
                        if (!counterAttackTimers.containsKey(uuid)) activeCombatPlayers.remove(uuid);
                    }
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private void sendSafeActionBar(Player player, String text) {
        player.sendActionBar(text);
    }

    private void updateOfflineState(UUID uuid, long now) {
        blockingPlayers.remove(uuid);
        fatigueRecoveryDelays.remove(uuid);
        removeExpired(critLockTimers, uuid, now);
        removeExpired(counterAttackTimers, uuid, now);
        removeExpired(shieldBreakTimers, uuid, now);
        if (!critLockTimers.containsKey(uuid) && !counterAttackTimers.containsKey(uuid)
                && !shieldBreakTimers.containsKey(uuid)) activeCombatPlayers.remove(uuid);
    }

    private void removeExpired(ConcurrentHashMap<UUID, Long> timers, UUID uuid, long now) {
        Long expiry = timers.get(uuid);
        if (expiry != null && now > expiry) timers.remove(uuid, expiry);
    }

    public void activatePersistedState(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        removeExpired(critLockTimers, uuid, now);
        removeExpired(counterAttackTimers, uuid, now);
        removeExpired(shieldBreakTimers, uuid, now);
        if (fatigueValues.getOrDefault(uuid, 0.0) > 0.0 || critLockTimers.containsKey(uuid)
                || counterAttackTimers.containsKey(uuid) || shieldBreakTimers.containsKey(uuid)) {
            activeCombatPlayers.add(uuid);
        }
    }
}
