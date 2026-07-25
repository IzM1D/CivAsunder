package io.lithcore.civasunder.listeners;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

public class IronGolemSpawnListener implements Listener {

    private static final int REQUIRED_SCARED_VILLAGERS = 13;
    private static final double VILLAGER_SEARCH_RADIUS = 32.0;
    private static final double THREAT_VISIBILITY_RADIUS = 16.0;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIronGolemSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.IRON_GOLEM
                || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE) {
            return;
        }

        int scaredVillagers = countScaredVillagers(event.getLocation());
        if (scaredVillagers > 0 && scaredVillagers < REQUIRED_SCARED_VILLAGERS) {
            event.setCancelled(true);
        }
    }

    private int countScaredVillagers(Location spawnLocation) {
        int scaredVillagers = 0;
        for (Entity entity : spawnLocation.getWorld().getNearbyEntities(
                spawnLocation, VILLAGER_SEARCH_RADIUS, VILLAGER_SEARCH_RADIUS, VILLAGER_SEARCH_RADIUS)) {
            if (!(entity instanceof Villager villager) || !villager.isAdult()) {
                continue;
            }

            if (canSeeThreat(villager) && ++scaredVillagers >= REQUIRED_SCARED_VILLAGERS) {
                return scaredVillagers;
            }
        }
        return scaredVillagers;
    }

    private boolean canSeeThreat(Villager villager) {
        for (Entity nearby : villager.getNearbyEntities(
                THREAT_VISIBILITY_RADIUS, THREAT_VISIBILITY_RADIUS, THREAT_VISIBILITY_RADIUS)) {
            if (isPanicThreat(nearby.getType()) && villager.hasLineOfSight(nearby)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPanicThreat(EntityType type) {
        return type == EntityType.ZOMBIE || type == EntityType.PILLAGER;
    }
}
