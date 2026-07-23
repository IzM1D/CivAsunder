package io.lithcore.civasunder.commands;

import io.lithcore.civasunder.managers.AuthManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;

public class CivWlCommand implements CommandExecutor, TabCompleter {

    private final AuthManager authManager;

    public CivWlCommand(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player && !sender.isOp()) {
            sendDirectMessage(sender, "[CivAsunder] У вас нет прав на управление белым списком!", "red");
            return true;
        }
        if (args.length < 2) {
            sendDirectMessage(sender, "[Использование] /civwl add [ник] или /civwl remove [ник]", "red");
            return true;
        }

        String action = args[0].toLowerCase();
        String targetPlayer = args[1];

        if (action.equals("add")) {
            if (authManager.addPlayerToWhitelist(targetPlayer)) {
                sendDirectMessage(sender, "[CivAsunder] Игрок " + targetPlayer + " успешно ДОБАВЛЕН в белый список!", "green");
            } else {
                sendDirectMessage(sender, "[CivAsunder] Игрок " + targetPlayer + " уже находится в белом списке.", "yellow");
            }
            return true;
        }

        if (action.equals("remove")) {
            if (authManager.removePlayerFromWhitelist(targetPlayer)) {
                sendDirectMessage(sender, "[CivAsunder] Игрок " + targetPlayer + " успешно УДАЛЕН из белого списка!", "green");
            } else {
                sendDirectMessage(sender, "[CivAsunder] Игрок " + targetPlayer + " не найден в белом списке.", "red");
            }
            return true;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (!sender.isOp()) return list;

        if (args.length == 1) {
            String current = args[0].toLowerCase();
            if ("add".startsWith(current)) list.add("add");
            if ("remove".startsWith(current)) list.add("remove");
        } else if (args.length == 2) {
            String current = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(current)) {
                    list.add(p.getName());
                }
            }
        }
        return list;
    }

    private void sendDirectMessage(CommandSender sender, String message, String color) {
        if (sender instanceof Player) {
            io.lithcore.civasunder.util.MessageUtil.sendJson(sender,
                    "{\"text\":\"" + message + "\",\"color\":\"" + color + "\"}");
        } else {
            Bukkit.getLogger().info("[CivAsunder WL] " + message);
        }
    }
}
