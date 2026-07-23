package io.lithcore.civasunder.listeners;

import io.lithcore.civasunder.CivAsunder;
import io.lithcore.civasunder.managers.AuthManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;

import java.util.UUID;

public class AuthListener implements Listener {

    private final CivAsunder plugin;
    private final AuthManager authManager;

    public AuthListener(CivAsunder plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
        startReminderTask();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWhitelistCheck(AsyncPlayerPreLoginEvent event) {
        String name = event.getName().toLowerCase();
        if (!authManager.getWhitelist().contains(name)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, "§c[CivAsunder] Вас нет в белом списке!");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLoginCheckSession(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String ip = event.getAddress().getHostAddress();

        if (authManager.isSessionValid(uuid, ip)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                sendRaw(player, "{\"text\":\"[Авторизация] С возвращением! Сессия подтверждена.\",\"color\":\"green\"}");
                player.updateCommands(); // Жестко обновляем сетку команд в клиенте
            });
        } else {
            authManager.getUnauthenticatedPlayers().add(uuid);
            
            org.bukkit.World soulWorld = Bukkit.getWorld("civ_souls");
            if (soulWorld != null) {
                Location authLoc = new Location(soulWorld, 0.5, 61.0, 0.5);
                player.setGameMode(org.bukkit.GameMode.ADVENTURE);
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    authManager.cacheAndClearInventory(player);
                    player.teleport(authLoc);
                    player.playSound(authLoc, org.bukkit.Sound.AMBIENT_CAVE, 1.0f, 0.5f);
                    player.playSound(authLoc, org.bukkit.Sound.MUSIC_DISC_5, 0.5f, 0.5f); // Отголоски мистики
                    player.updateCommands(); // Жестко обновляем сетку команд в клиенте
                    
                    if (authManager.isRegistered(player.getName())) {
                        sendRaw(player, "{\"text\":\"[Авторизация] Пожалуйста, войдите: /login [пароль]\",\"color\":\"yellow\"}");
                    } else {
                        sendRaw(player, "{\"text\":\"[Регистрация] Добро пожаловать! Зарегистрируйтесь: /register [пароль] [пароль]\",\"color\":\"gold\"}");
                    }
                });
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String ip = player.getAddress().getAddress().getHostAddress();

        if (!authManager.getUnauthenticatedPlayers().contains(uuid)) {
            authManager.updateSession(uuid, ip);
        } else {
            authManager.restoreInventory(player);
        }
        authManager.getUnauthenticatedPlayers().remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAuthMoveRestriction(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (authManager.getUnauthenticatedPlayers().contains(player.getUniqueId())) {
            if (!player.getWorld().getName().equals("civ_souls")) {
                org.bukkit.World soulWorld = Bukkit.getWorld("civ_souls");
                if (soulWorld != null) player.teleport(new Location(soulWorld, 0.5, 61.0, 0.5));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAuthChat(AsyncPlayerChatEvent event) {
        if (authManager.getUnauthenticatedPlayers().contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            sendRaw(event.getPlayer(), "{\"text\":\"[Ошибка] Вы не можете писать в чат до авторизации!\",\"color\":\"red\"}");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAuthCommand(PlayerCommandPreprocessEvent event) {
        if (authManager.getUnauthenticatedPlayers().contains(event.getPlayer().getUniqueId())) {
            String msg = event.getMessage().toLowerCase();
            if (!msg.startsWith("/login") && !msg.startsWith("/register")) {
                event.setCancelled(true);
                sendRaw(event.getPlayer(), "{\"text\":\"[Ошибка] Доступны только команды /login и /register!\",\"color\":\"red\"}");
            }
        }
    }

    private void startReminderTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID uuid : authManager.getUnauthenticatedPlayers()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    if (authManager.isRegistered(p.getName())) {
                        sendRaw(p, "{\"text\":\"[Авторизация] Введите пароль: /login [пароль]\",\"color\":\"yellow\"}");
                    } else {
                        sendRaw(p, "{\"text\":\"[Регистрация] Зарегистрируйтесь: /register [пароль] [пароль]\",\"color\":\"gold\"}");
                    }
                }
            }
        }, 100L, 100L);
    }

    private void sendRaw(Player p, String json) {
        io.lithcore.civasunder.util.MessageUtil.sendJson(p, json);
    }
}
