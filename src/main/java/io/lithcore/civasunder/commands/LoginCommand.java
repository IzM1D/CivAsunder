package io.lithcore.civasunder.commands;

import io.lithcore.civasunder.CivAsunder;
import io.lithcore.civasunder.managers.AuthManager;
import io.lithcore.civasunder.managers.SoulManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;

public class LoginCommand implements CommandExecutor, TabCompleter {

    private final AuthManager authManager;

    public LoginCommand(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Bukkit.getLogger().warning("[CivAsunder] Эта команда доступна только игрокам!");
            return true;
        }

        Player player = (Player) sender;

        if (!authManager.isRegistered(player.getName())) {
            sendRaw(player, "{\"text\":\"[Ошибка] Вы еще не зарегистрированы! Используйте команду: /register [пароль] [пароль]\",\"color\":\"red\"}");
            return true;
        }

        if (args.length < 1) {
            sendRaw(player, "{\"text\":\"[Ошибка] Введите пароль: /login [пароль]\",\"color\":\"red\"}");
            return true;
        }

        String pass = args[0];

        if (authManager.checkPassword(player.getName(), pass)) {
            authManager.getUnauthenticatedPlayers().remove(player.getUniqueId());
            
            // ТЗ: Возвращаем оригинальные вещи из кэша
            authManager.restoreInventory(player);
            
            SoulManager sm = CivAsunder.getInstance().getSoulManager();
            if (sm != null && sm.getImprisonedPlayers().containsKey(player.getUniqueId())) {
                player.setGameMode(org.bukkit.GameMode.SURVIVAL);
                sendRaw(player, "{\"text\":\"[Авторизация] С возвращением! Вы оставлены на каторге Чистилища.\",\"color\":\"yellow\"}");
            } else {
                player.setGameMode(org.bukkit.GameMode.SURVIVAL);
                // Координаты восстановит менеджер
                sendRaw(player, "{\"text\":\"[Авторизация] Успешный вход! Ваши вещи восстановлены.\",\"color\":\"green\"}");
            }
        } else {
            sendRaw(player, "{\"text\":\"[Ошибка] Введен неверный пароль!\",\"color\":\"red\"}");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) list.add("[пароль]");
        return list;
    }

    private void sendRaw(Player p, String json) {
        io.lithcore.civasunder.util.MessageUtil.sendJson(p, json);
    }
}
