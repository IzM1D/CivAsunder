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

public class RegisterCommand implements CommandExecutor, TabCompleter {

    private final AuthManager authManager;

    public RegisterCommand(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Bukkit.getLogger().warning("[CivAsunder] Эта команда доступна только игрокам!");
            return true;
        }

        Player player = (Player) sender;

        if (authManager.isRegistered(player.getName())) {
            sendRaw(player, "{\"text\":\"[Ошибка] Вы уже зарегистрированы! Используйте команду: /login [пароль]\",\"color\":\"red\"}");
            return true;
        }

        if (args.length < 2) {
            sendRaw(player, "{\"text\":\"[Ошибка] Введите: /register [пароль] [повтор]\",\"color\":\"red\"}");
            return true;
        }

        String pass = args[0];
        String confirm = args[1];

        if (!pass.equals(confirm)) {
            sendRaw(player, "{\"text\":\"[Ошибка] Введенные пароли не совпадают!\",\"color\":\"red\"}");
            return true;
        }
        if (pass.length() < 3) {
            sendRaw(player, "{\"text\":\"[Ошибка] Пароль слишком короткий! Минимум 3 символа.\",\"color\":\"red\"}");
            return true;
        }

        authManager.registerPlayer(player.getName(), pass);
        authManager.getUnauthenticatedPlayers().remove(player.getUniqueId());
        
        // ТЗ: Возвращаем оригинальные вещи из кэша
        authManager.restoreInventory(player);
        
        SoulManager sm = CivAsunder.getInstance().getSoulManager();
        if (sm != null && sm.getImprisonedPlayers().containsKey(player.getUniqueId())) {
            // Если он заключенный — контекст сменился на каторгу, оставляем в Чистилище
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            sendRaw(player, "{\"text\":\"[Регистрация] Успешно! Но ваша душа находится в Алтаре Заточения. Вы оставлены в Чистилище!\",\"color\":\"yellow\"}");
        } else {
            // Если он свободный — возвращаем в реальный мир на спавн (или на его прошлые координаты)
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            // Координаты восстановит менеджер
            sendRaw(player, "{\"text\":\"[Регистрация] Вы успешно вошли! Ваши вещи и позиция восстановлены.\",\"color\":\"green\"}");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) list.add("[пароль]");
        else if (args.length == 2) list.add("[повтор_пароля]");
        return list;
    }

    private void sendRaw(Player p, String json) {
        io.lithcore.civasunder.util.MessageUtil.sendJson(p, json);
    }
}
