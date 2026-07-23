package io.lithcore.civasunder.commands;

import io.lithcore.civasunder.managers.VeinManager;
import io.lithcore.civasunder.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Random;

public class VeinCommand implements CommandExecutor {

    private final VeinManager veinManager;

    public VeinCommand(VeinManager veinManager) {
        this.veinManager = veinManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("civ_asunder.admin")) {
            if (sender instanceof Player player) {
                sendRawMessage(player, "{\"text\":\"У вас нет прав на использование этой команды!\",\"color\":\"red\"}");
            } else {
                Bukkit.getLogger().info("[CivAsunder] У вас нет прав на использование этой команды!");
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("populate")) {
            if (!(sender instanceof Player player)) {
                Bukkit.getLogger().info("[CivAsunder] Эта команда может быть выполнена только игроком!");
                return true;
            }
            Chunk chunk = player.getLocation().getChunk();
            VeinManager.VeinType[] types = VeinManager.VeinType.values();
            VeinManager.VeinType randomType = types[new Random().nextInt(types.length)];
            int blocksPlaced = veinManager.generateSingleCluster(chunk, randomType);
            String message = "[{\"text\":\"[CivAsunder] \",\"color\":\"gold\"},"
                    + "{\"text\":\"В текущем чанке успешно сгенерирована жила типа \",\"color\":\"green\"},"
                    + "{\"text\":\"" + randomType.name() + "\",\"color\":\"yellow\"},"
                    + "{\"text\":\" (" + blocksPlaced + " блоков добавлено).\",\"color\":\"green\"}]";
            sendRawMessage(player, message);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("locate")) {
            if (!(sender instanceof Player player)) {
                Bukkit.getLogger().info("[CivAsunder] Эта команда может быть выполнена только игроком!");
                return true;
            }
            List<VeinManager.MapResult> results = veinManager.findNearestVeins(player,
                    VeinManager.SEARCH_RADIUS_CHUNKS, VeinManager.MAX_SEARCH_RESULTS);
            if (results.isEmpty()) {
                sendRawMessage(player, "{\"text\":\"[Радар] В радиусе "
                        + VeinManager.SEARCH_RADIUS_CHUNKS
                        + " чанков природных жил не обнаружено. Летите дальше!\",\"color\":\"red\"}");
                return true;
            }

            VeinManager.MapResult result = results.get(0);
            String message = "[{\"text\":\"[Радар] \",\"color\":\"light_purple\"},"
                    + "{\"text\":\"Ближайшая жила типа \",\"color\":\"green\"},"
                    + "{\"text\":\"" + result.typeName + "\",\"color\":\"yellow\"},"
                    + "{\"text\":\" находится на координатах: \",\"color\":\"green\"},"
                    + "{\"text\":\"X: " + result.geoX + ", Y: " + result.geoY + ", Z: " + result.geoZ
                    + "\",\"color\":\"aqua\"},"
                    + "{\"text\":\" (Дистанция: " + (int) result.distance
                    + " блоков).\",\"color\":\"gray\"}]";
            sendRawMessage(player, message);
            return true;
        }

        if (sender instanceof Player player) {
            sendRawMessage(player, "{\"text\":\"Использование: /vein populate или /vein locate\",\"color\":\"yellow\"}");
        } else {
            Bukkit.getLogger().info("[CivAsunder] Использование: /vein populate или /vein locate");
        }
        return true;
    }

    private void sendRawMessage(Player player, String json) {
        MessageUtil.sendJson(player, json);
    }
}
