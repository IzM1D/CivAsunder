package io.lithcore.civasunder.commands;

import io.lithcore.civasunder.listeners.KingdomBlockListener;
import io.lithcore.civasunder.managers.KingdomManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;

public class KingdomCommand implements CommandExecutor, TabCompleter {

    private final KingdomManager kingdomManager;
    private final KingdomBlockListener blockListener;

    public KingdomCommand(KingdomManager kingdomManager, KingdomBlockListener blockListener) {
        this.kingdomManager = kingdomManager;
        this.blockListener = blockListener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Bukkit.getLogger().info("[CivAsunder] Only players can use this command!");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendRawMessage(player, "{\"text\":\"Помощь: /k [create|invite|accept|kick|rank|list|info|transfer]\",\"color\":\"yellow\"}");
            return true;
        }
        String sub = args[0].toLowerCase();
        UUID pUUID = player.getUniqueId();

        if (sub.equals("create")) {
            if (args.length < 2) {
                sendRawMessage(player, "{\"text\":\"Использование: /k create [название]\",\"color\":\"red\"}");
                return true;
            }
            if (!blockListener.getSetupSessions().containsKey(pUUID)) {
                sendRawMessage(player, "{\"text\":\"[Государство] Сначала установите Блок Сердца Государства на землю!\",\"color\":\"red\"}");
                return true;
            }
            String kName = args[1].replaceAll("[^a-zA-Z0-9а-яА-Я_]", "");
            Location loc = blockListener.getSetupSessions().remove(pUUID);
            if (kingdomManager.createKingdom(kName, player, loc)) {
                sendRawMessage(player, "[{\"text\":\"[CivAsunder] \",\"color\":\"gold\"},{\"text\":\"Государство \",\"color\":\"green\"},{\"text\":\""+kName+"\",\"color\":\"yellow\"},{\"text\":\" успешно создано! Вы новый Король!\",\"color\":\"green\"}]");
            } else {
                sendRawMessage(player, "{\"text\":\"[Государство] Страна с таким названием уже существует!\",\"color\":\"red\"}");
            }
            return true;
        }

        if (sub.equals("invite")) {
            if (args.length < 2) {
                sendRawMessage(player, "{\"text\":\"Использование: /k invite [ник]\",\"color\":\"red\"}");
                return true;
            }
            KingdomManager.KingdomData k = kingdomManager.getKingdomByStaff(pUUID);
            if (k == null) {
                sendRawMessage(player, "{\"text\":\"[Государство] Ваш уровень близости слишком мал для инвайтов!\",\"color\":\"red\"}");
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null || !target.isOnline()) {
                sendRawMessage(player, "{\"text\":\"[Государство] Игрок не найден или оффлайн!\",\"color\":\"red\"}");
                return true;
            }
            kingdomManager.getActiveInvites().put(target.getUniqueId(), k.name.toLowerCase());
            sendRawMessage(player, "{\"text\":\"[Государство] Вы успешно отправили приглашение игроку " + target.getName() + "\",\"color\":\"green\"}");
            sendRawMessage(target, "[{\"text\":\"[Государство] \",\"color\":\"gold\"},{\"text\":\"Вас приглашают в государство \",\"color\":\"green\"},{\"text\":\""+k.name+"\",\"color\":\"yellow\"},{\"text\":\". Принять: \",\"color\":\"green\"},{\"text\":\"/k accept\",\"color\":\"aqua\"}]");
            return true;
        }

        if (sub.equals("accept")) {
            if (!kingdomManager.getActiveInvites().containsKey(pUUID)) {
                sendRawMessage(player, "{\"text\":\"[Государство] У вас нет активных приглашений!\",\"color\":\"red\"}");
                return true;
            }
            KingdomManager.KingdomData k = kingdomManager.getKingdoms().get(kingdomManager.getActiveInvites().remove(pUUID));
            if (k != null) { 
                k.memberRanks.put(pUUID, 1); 
                kingdomManager.saveKingdomsToFile(); 
                sendRawMessage(player, "[{\"text\":\"[CivAsunder] \",\"color\":\"gold\"},{\"text\":\"Вы успешно вступили в государство \",\"color\":\"green\"},{\"text\":\""+k.name+"\",\"color\":\"yellow\"},{\"text\":\"! Ваш уровень близости: 1.\",\"color\":\"green\"}]");
            } else {
                sendRawMessage(player, "{\"text\":\"[Государство] Это государство больше не существует.\",\"color\":\"red\"}");
            }
            return true;
        }

        if (sub.equals("kick")) {
            if (args.length < 2) {
                sendRawMessage(player, "{\"text\":\"Использование: /k kick [ник]\",\"color\":\"red\"}");
                return true;
            }
            KingdomManager.KingdomData k = kingdomManager.getKingdomByStaff(pUUID);
            if (k == null) {
                sendRawMessage(player, "{\"text\":\"[Государство] Вы не управляете государством!\",\"color\":\"red\"}");
                return true;
            }
            UUID tUUID = null;
            String tName = args[1];
            Player oTarget = Bukkit.getPlayer(tName);
            if (oTarget != null) {
                tUUID = oTarget.getUniqueId();
                tName = oTarget.getName();
            } else {
                for (UUID id : k.memberRanks.keySet()) {
                    if (Bukkit.getOfflinePlayer(id).getName() != null && Bukkit.getOfflinePlayer(id).getName().equalsIgnoreCase(tName)) {
                        tUUID = id;
                        break;
                    }
                }
            }
            if (tUUID == null || !k.memberRanks.containsKey(tUUID)) {
                sendRawMessage(player, "{\"text\":\"[Государство] Этот игрок не состоит в вашем государстве!\",\"color\":\"red\"}");
                return true;
            }
            if (!kingdomManager.canManage(k, pUUID, tUUID)) {
                sendRawMessage(player, "{\"text\":\"[Государство] Ваш уровень близости должен быть СТРОГО ВЫШЕ уровня этого игрока!\",\"color\":\"red\"}");
                return true;
            }
            k.memberRanks.remove(tUUID);
            kingdomManager.saveKingdomsToFile();
            sendRawMessage(player, "{\"text\":\"[Государство] Игрок " + tName + " успешно исключен из вашей страны.\",\"color\":\"green\"}");
            if (oTarget != null) {
                sendRawMessage(oTarget, "{\"text\":\"[Государство] Вы были исключены из государства " + k.name + "\",\"color\":\"red\"}");
            }
            return true;
        }

        if (sub.equals("rank")) {
            if (args.length < 3) {
                sendRawMessage(player, "{\"text\":\"Использование: /k rank [ник] [уровень_1-100]\",\"color\":\"red\"}");
                return true;
            }
            KingdomManager.KingdomData k = kingdomManager.getKingdomByStaff(pUUID);
            if (k == null) {
                sendRawMessage(player, "{\"text\":\"[Государство] Вы не управляете государством!\",\"color\":\"red\"}");
                return true;
            }
            String tName = args[1];
            UUID tUUID = null;
            Player oTarget = Bukkit.getPlayer(tName);
            if (oTarget != null) {
                tUUID = oTarget.getUniqueId();
                tName = oTarget.getName();
            } else {
                for (UUID id : k.memberRanks.keySet()) {
                    if (Bukkit.getOfflinePlayer(id).getName() != null && Bukkit.getOfflinePlayer(id).getName().equalsIgnoreCase(tName)) {
                        tUUID = id;
                        break;
                    }
                }
            }
            if (tUUID == null || !k.memberRanks.containsKey(tUUID)) {
                sendRawMessage(player, "{\"text\":\"[Государство] Этот игрок не состоит в вашей стране!\",\"color\":\"red\"}");
                return true;
            }
            int newRank;
            try {
                newRank = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sendRawMessage(player, "{\"text\":\"Уровень должен быть числом!\",\"color\":\"red\"}");
                return true;
            }
            if (newRank < 1 || newRank > 100) {
                sendRawMessage(player, "{\"text\":\"Уровень должен быть от 1 до 100!\",\"color\":\"red\"}");
                return true;
            }
            int myRank = kingdomManager.getPlayerRank(k, pUUID);
            if (!kingdomManager.canManage(k, pUUID, tUUID) || newRank >= myRank) {
                sendRawMessage(player, "{\"text\":\"[Государство] Вы не можете выставить уровень равный или выше вашего собственного (" + myRank + ")!\",\"color\":\"red\"}");
                return true;
            }
            k.memberRanks.put(tUUID, newRank);
            kingdomManager.saveKingdomsToFile();
            sendRawMessage(player, "{\"text\":\"[Государство] Игроку " + tName + " успешно выставлен уровень близости " + newRank + ".\",\"color\":\"green\"}");
            if (oTarget != null) {
                sendRawMessage(oTarget, "{\"text\":\"[Государство] Ваш уровень близости в " + k.name + " изменен на " + newRank + "!\",\"color\":\"gold\"}");
            }
            return true;
        }

        if (sub.equals("list")) {
            for (KingdomManager.KingdomData k : kingdomManager.getKingdoms().values()) {
                sendRawMessage(player, "{\"text\":\" - " + k.name + "\",\"color\":\"yellow\"}");
            }
            return true;
        }
        if (sub.equals("info")) {
            if (args.length < 2) return true;
            KingdomManager.KingdomData k = kingdomManager.getKingdoms().get(args[1].toLowerCase());
            if (k == null) return true;
            sendRawMessage(player, "{\"text\":\"Страна: " + k.name + "\",\"color\":\"gold\"}");
            for (Map.Entry<UUID, Integer> entry : k.memberRanks.entrySet()) {
                String mName = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                if (mName == null) mName = "Unknown";
                sendRawMessage(player, "{\"text\":\" - " + mName + " (Ранг: " + entry.getValue() + ")\",\"color\":\"yellow\"}");
            }
            return true;
        }
        if (sub.equals("leave")) {
            if (args.length < 2) {
                sendRawMessage(player, "{\"text\":\"Использование: /k leave [название_государства]\",\"color\":\"red\"}");
                return true;
            }
            String targetKingdomName = args[1].toLowerCase();
            KingdomManager.KingdomData k = kingdomManager.getKingdoms().get(targetKingdomName);
            
            if (k == null) {
                sendRawMessage(player, "{\"text\":\"[Государство] Государство с таким названием не найдено!\",\"color\":\"red\"}");
                return true;
            }
            if (!k.memberRanks.containsKey(pUUID)) {
                sendRawMessage(player, "{\"text\":\"[Государство] Вы не являетесь участником государства " + k.name + "!\",\"color\":\"red\"}");
                return true;
            }
            if (k.kingUUID.equals(pUUID)) {
                sendRawMessage(player, "{\"text\":\"[Государство] Король не может покинуть страну! Сначала передайте корону через /k transfer [ник] или сломайте Блок Сердца.\",\"color\":\"red\"}");
                return true;
            }

            // Удаляем игрока из списков участников страны
            k.memberRanks.remove(pUUID);
            kingdomManager.saveKingdomsToFile();
            
            sendRawMessage(player, "[{\"text\":\"[CivAsunder] \",\"color\":\"gold\"},{\"text\":\"Вы добровольно покинули государство \",\"color\":\"green\"},{\"text\":\"" + k.name + "\",\"color\":\"yellow\"},{\"text\":\"!\",\"color\":\"green\"}]");
            return true;
        }

        if (sub.equals("transfer")) {
            if (args.length < 2) {
                sendRawMessage(player, "{\"text\":\"Использование: /k transfer [ник]\",\"color\":\"red\"}");
                return true;
            }
            KingdomManager.KingdomData k = kingdomManager.getKingdomByKing(pUUID);
            if (k == null) {
                sendRawMessage(player, "{\"text\":\"[Государство] Только действующий Король может передать корону!\",\"color\":\"red\"}");
                return true;
            }
            Player oTarget = Bukkit.getPlayer(args[1]);
            if (oTarget == null || !k.memberRanks.containsKey(oTarget.getUniqueId())) {
                sendRawMessage(player, "{\"text\":\"[Государство] Новый Король должен быть в сети и состоять в вашей стране!\",\"color\":\"red\"}");
                return true;
            }
            k.kingUUID = oTarget.getUniqueId();
            k.memberRanks.put(pUUID, 99);
            kingdomManager.saveKingdomsToFile();
            sendRawMessage(player, "{\"text\":\"[Государство] Вы успешно передали корону игроку " + oTarget.getName() + ". Теперь ваш уровень близости: 99.\",\"color\":\"green\"}");
            sendRawMessage(oTarget, "{\"text\":\"[Государство] Тяжёлый груз власти! Вы стали новым КОРОЛЕМ государства " + k.name + "!\",\"color\":\"gold\"}");
            return true;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String currentInput = args[0].toLowerCase();
            String[] subCommands = {"create", "invite", "accept", "kick", "rank", "list", "info", "transfer", "leave", "help"};
            for (String sub : subCommands) {
                if (sub.startsWith(currentInput)) completions.add(sub);
            }
        }
        return completions;
    }

    private void sendRawMessage(Player player, String json) {
        io.lithcore.civasunder.util.MessageUtil.sendJson(player, json);
    }
}
