package io.lithcore.civasunder.util;

import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.Bukkit;
import io.lithcore.civasunder.CivAsunder;

/** Sends the same JSON components as tellraw without routing them through the command parser. */
public final class MessageUtil {
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private MessageUtil() {
    }

    public static void sendJson(CommandSender recipient, String json) {
        final net.kyori.adventure.text.Component message;
        try {
            message = GSON.deserialize(json);
        } catch (RuntimeException invalidJson) {
            CivAsunder.getInstance().getLogger().warning("[CivAsunder] Некорректный JSON сообщения: "
                    + invalidJson.getMessage());
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            recipient.sendMessage(message);
        } else {
            Bukkit.getScheduler().runTask(CivAsunder.getInstance(), () -> recipient.sendMessage(message));
        }
    }
}
