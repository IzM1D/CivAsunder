package io.lithcore.civasunder.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Raider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;

/**
 * Ребаланс дропа с рейдеров: тотемы бессмертия с эвокеров не падают вообще,
 * изумруды делятся на 3, редстоун — на 4.
 *
 * Ранее фильтр стоял на {@code Raider#getRaid() != null}, но Paper удаляет ссылку
 * на рейд у моба ещё до {@link EntityDeathEvent}, поэтому обработчик пропускал
 * даже честные рейд-смерти. Правило теперь применяется к любому Raider — тотем
 * дропает только эвокер, а редстоун/изумруды с патрулей и особняков тоже подлежат
 * тому же ребалансу, что не расходится с задачей.
 */
public class RaidLootListener implements Listener {

    private static final int EMERALD_DIVISOR = 3;
    private static final int REDSTONE_DIVISOR = 4;

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onRaiderDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Raider)) {
            return;
        }

        Iterator<ItemStack> iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack drop = iterator.next();
            if (drop == null || drop.getType() == Material.AIR) {
                continue;
            }
            Material type = drop.getType();
            if (type == Material.TOTEM_OF_UNDYING) {
                iterator.remove();
                continue;
            }
            if (type == Material.EMERALD) {
                int reduced = drop.getAmount() / EMERALD_DIVISOR;
                if (reduced <= 0) {
                    iterator.remove();
                } else {
                    drop.setAmount(reduced);
                }
                continue;
            }
            if (type == Material.REDSTONE) {
                int reduced = drop.getAmount() / REDSTONE_DIVISOR;
                if (reduced <= 0) {
                    iterator.remove();
                } else {
                    drop.setAmount(reduced);
                }
            }
        }
    }
}
