package io.lithcore.civasunder.commands;

import io.lithcore.civasunder.managers.AuthManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class LogoutCommand implements CommandExecutor, TabCompleter {

    private final AuthManager authManager;

    public LogoutCommand(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Bukkit.getLogger().warning("[CivAsunder] Эта команда доступна только игрокам!");
            return true;
        }

        Player player = (Player) sender;

        // Если игрок еще не авторизован — не даем разлогиниться повторно
        if (authManager.getUnauthenticatedPlayers().contains(player.getUniqueId())) {
            sendRaw(player, "{\"text\":\"[Ошибка] Вы еще не вошли в аккаунт!\",\"color\":\"red\"}");
            return true;
        }

        // Добавляем в список заморозки
        authManager.getUnauthenticatedPlayers().add(player.getUniqueId());
        
        // Кэшируем инвентарь и отправляем в мир civ_souls
        org.bukkit.World soulWorld = Bukkit.getWorld("civ_souls");
        if (soulWorld != null) {
            Location authLoc = new Location(soulWorld, 0.5, 61.0, 0.5);
            player.setGameMode(org.bukkit.GameMode.ADVENTURE);
            
            authManager.cacheAndClearInventory(player);
            player.teleport(authLoc);
        }

        sendRaw(player, "{\"text\":\"[Авторизация] Вы успешно вышли из аккаунта! Введите /login [пароль] для возврата.\",\"color\":\"green\"}");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return new ArrayList<>();
    }

    private void sendRaw(Player p, String json) {
        io.lithcore.civasunder.util.MessageUtil.sendJson(p, json);
    }
}
