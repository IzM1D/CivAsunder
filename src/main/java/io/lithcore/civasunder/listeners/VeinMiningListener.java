package io.lithcore.civasunder.listeners;

import io.lithcore.civasunder.managers.VeinManager;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public class VeinMiningListener implements Listener {

    private final VeinManager veinManager;

    public VeinMiningListener(VeinManager veinManager) {
        this.veinManager = veinManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block block = event.getBlock();
        boolean wasVein = veinManager.handleBlockBreak(block);
        if (wasVein) {
            event.setCancelled(true);
        }
    }

    // [ЗАМОРОЗКА КЛИКОВ]: Полностью игнорируем любые попытки взаимодействия с пустой точкой жилы
    @EventHandler(priority = EventPriority.NORMAL)
    public void onAirInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null) return;

        // Если точка в КД, просто отменяем любые встроенные триггеры Bukkit,
        // но НЕ увеличиваем усталость и НЕ сдвигаем время регенерации
        if (veinManager.isCoolingDown(block.getLocation())) {
            event.setCancelled(true);
        }
    }

    // [АНТИ-ЗАСТРОЙКА]: Запрещаем игрокам ставить блоки в пустые точки восстанавливающихся жил
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block blockPlaced = event.getBlockPlaced();
        
        // Если игрок пытается поставить блок туда, где сейчас регенерируется руда — блокируем
        if (veinManager.isCoolingDown(blockPlaced.getLocation())) {
            event.setCancelled(true);
        }
    }
}
