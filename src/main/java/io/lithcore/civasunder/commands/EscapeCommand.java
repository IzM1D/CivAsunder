package io.lithcore.civasunder.commands;

import io.lithcore.civasunder.managers.SoulManager;
import io.lithcore.civasunder.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;

public class EscapeCommand implements CommandExecutor {

    private final SoulManager soulManager;

    public EscapeCommand(SoulManager soulManager) {
        this.soulManager = soulManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Bukkit.getLogger().info("[CivAsunder] Команда /escape доступна только игрокам.");
            return true;
        }

        if (!player.isOp()) {
            send(player, "[Чистилище] Эта команда доступна только операторам.", "red");
            return true;
        }

        if (args.length != 0) {
            send(player, "[Использование] /escape", "red");
            return true;
        }

        World soulWorld = soulManager.getSoulWorld();
        if (soulWorld == null || !player.getWorld().equals(soulWorld)) {
            send(player, "[Чистилище] Эту команду можно использовать только в Чистилище.", "red");
            return true;
        }

        UUID playerUUID = player.getUniqueId();
        if (soulManager.getImprisonedPlayers().remove(playerUUID) == null) {
            send(player, "[Чистилище] Ваша душа не находится в заточении.", "yellow");
            return true;
        }

        int removedTotems = soulManager.removeSoulItems(playerUUID);
        soulManager.saveData();

        player.getInventory().clear();
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        player.setGameMode(GameMode.SURVIVAL);
        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0.0f);

        Location destination = player.getRespawnLocation();
        if (destination == null || destination.getWorld() == null || destination.getWorld().equals(soulWorld)) {
            destination = Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        player.teleport(destination);

        send(player,
                "[Чистилище] Вы досрочно покинули Чистилище. Удалено тотемов души: " + removedTotems + ".",
                "green");
        return true;
    }

    private void send(Player player, String message, String color) {
        MessageUtil.sendJson(player, "{\"text\":\"" + message + "\",\"color\":\"" + color + "\"}");
    }
}
