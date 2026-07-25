package io.lithcore.civasunder.commands;

import io.lithcore.civasunder.CivAsunder;
import io.lithcore.civasunder.managers.MatterManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class ConvertCommand implements CommandExecutor {

    private final MatterManager matterManager;
    private final CivAsunder plugin;

    public ConvertCommand(CivAsunder plugin, MatterManager matterManager) {
        this.plugin = plugin;
        this.matterManager = matterManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Bukkit.getLogger().info("[CivAsunder] Only players can use this command!");
            return true;
        }

        Player player = (Player) sender;
        if (!player.isOp()) {
            sendRawMessage(player, "{\"text\":\"[Конвертер] Команда /convert доступна только операторам сервера.\",\"color\":\"red\"}");
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        
        if (item == null || item.getType() == Material.AIR) {
            sendRawMessage(player, "{\"text\":\"[Конвертер] Возьмите предмет для утилизации в основную руку!\",\"color\":\"red\"}");
            return true;
        }

        NamespacedKey matterCurrencyKey = new NamespacedKey(plugin, "is_matter_currency");
        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta != null && itemMeta.getPersistentDataContainer()
                .has(matterCurrencyKey, PersistentDataType.BYTE)) {
            sendCannotConvertMessage(player);
            return true;
        }

        int[] result = matterManager.calculateConversion(item.getType(), item.getAmount());
        int statusCodeOrMatter = result[0];
        int itemsToConsume = result[1];

        // РАЗДЕЛЕНИЕ ПРИЧИН ОТКАЗА НА ОСНОВЕ КОДОВ СТАТУСА:
        if (statusCodeOrMatter == -1) {
            // Предмет вообще отсутствует в таблице ценностей (мусор, который нельзя сжечь)
            sendCannotConvertMessage(player);
            return true;
        }

        if (statusCodeOrMatter == -2) {
            // Предмет правильный, но его количества в руке физически не хватает до 1 Материи
            int requiredTotal = matterManager.getRequiredAmountForOne(item.getType());
            int missingAmount = requiredTotal - item.getAmount();
            
            String errorJson = "[{\"text\":\"[Конвертер] \",\"color\":\"red\"},"
                + "{\"text\":\"Предмет подходит, но этого количества слишком мало! Вам не хватает ещё \",\"color\":\"red\"},"
                + "{\"text\":\"" + missingAmount + "\",\"color\":\"yellow\",\"bold\":true},"
                + "{\"text\":\" шт. до получения 1 Материи.\",\"color\":\"red\"}]";
            sendRawMessage(player, errorJson);
            return true;
        }

        // Логика успешной конвертации (если код статуса положительный)
        item.setAmount(item.getAmount() - itemsToConsume);
        player.getInventory().setItemInMainHand(item);

        ItemStack matterItem = matterManager.createMatterItem(statusCodeOrMatter);

        player.getInventory().addItem(matterItem);
        
        String successJson = "[{\"text\":\"[Конвертер] \",\"color\":\"gold\"},"
            + "{\"text\":\"Успешно переработано блоков: \",\"color\":\"green\"},"
            + "{\"text\":\"" + itemsToConsume + "\",\"color\":\"yellow\"},"
            + "{\"text\":\" шт. Получено Материи: \",\"color\":\"green\"},"
            + "{\"text\":\"" + statusCodeOrMatter + "\",\"color\":\"light_purple\",\"bold\":true},"
            + "{\"text\":\" ед.!\",\"color\":\"green\"}]";
        sendRawMessage(player, successJson);

        return true;
    }

    private void sendCannotConvertMessage(Player player) {
        sendRawMessage(player, "{\"text\":\"[Конвертер] Этот тип предметов не имеет энергетической ценности и не принимается для извлечения Материи!\",\"color\":\"red\"}");
    }

    private void sendRawMessage(Player player, String json) {
        io.lithcore.civasunder.util.MessageUtil.sendJson(player, json);
    }
}
