package io.lithcore.civasunder.listeners;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import io.lithcore.civasunder.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Запрещает игрокам использовать фейерверки для ускорения при полёте на элитрах.
 * Разрешено только «парение» (планирование) без разгона.
 *
 * Событие {@link PlayerElytraBoostEvent} вызывается Paper ровно в тот момент,
 * когда игрок нажимает ПКМ по firework_rocket, находясь в состоянии isGliding().
 * Отмена события гасит буст, а {@code setShouldConsume(false)} возвращает
 * фейерверк в руку — предмет не тратится напрасно.
 */
public class ElytraBoostListener implements Listener {

    private static final String DENY_JSON =
            "{\"text\":\"На элитрах можно только парить — фейерверки отключены.\",\"color\":\"red\"}";

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        event.setCancelled(true);
        event.setShouldConsume(false);

        Player player = event.getPlayer();
        MessageUtil.sendJson(player, DENY_JSON);
        // Принудительно обновляем инвентарь, чтобы клиент не «съел» ракету визуально.
        player.updateInventory();
    }
}
