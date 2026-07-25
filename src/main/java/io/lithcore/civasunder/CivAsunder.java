package io.lithcore.civasunder;

import io.lithcore.civasunder.commands.ConvertCommand;
import io.lithcore.civasunder.commands.KingdomCommand;
import io.lithcore.civasunder.commands.VeinCommand;
import io.lithcore.civasunder.commands.RegisterCommand;
import io.lithcore.civasunder.commands.LoginCommand;
import io.lithcore.civasunder.commands.CivWlCommand;
import io.lithcore.civasunder.commands.LogoutCommand;
import io.lithcore.civasunder.commands.CivAsunderCommand;
import io.lithcore.civasunder.commands.EscapeCommand;
import io.lithcore.civasunder.listeners.CompassRadarListener;
import io.lithcore.civasunder.listeners.KingdomBlockListener;
import io.lithcore.civasunder.listeners.OrePreventionListener;
import io.lithcore.civasunder.listeners.TownHallListener;
import io.lithcore.civasunder.listeners.TownHallProtectionListener;
import io.lithcore.civasunder.listeners.VeinMiningListener;
import io.lithcore.civasunder.listeners.SoulListener;
import io.lithcore.civasunder.listeners.SoulAltarListener;
import io.lithcore.civasunder.listeners.AuthListener;
import io.lithcore.civasunder.listeners.ElytraBoostListener;
import io.lithcore.civasunder.listeners.RaidLootListener;
import io.lithcore.civasunder.listeners.VillagerTradeLockListener;
import io.lithcore.civasunder.managers.KingdomManager;
import io.lithcore.civasunder.managers.MatterManager;
import io.lithcore.civasunder.managers.TownHallManager;
import io.lithcore.civasunder.managers.VeinManager;
import io.lithcore.civasunder.managers.SoulManager;
import io.lithcore.civasunder.managers.AuthManager;
import io.lithcore.civasunder.managers.CombatManager;
import io.lithcore.civasunder.listeners.CombatListener;
import io.lithcore.civasunder.util.AsyncFileWriter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandMap;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.logging.Logger;

public class CivAsunder extends JavaPlugin {
    
    private static CivAsunder instance;
    private VeinManager veinManager;
    private KingdomManager kingdomManager;
    private MatterManager matterManager;
    private TownHallManager townHallManager;
    private SoulManager soulManager;
    private AuthManager authManager;
    private CombatManager combatManager;
    private AsyncFileWriter dataWriter;

    @Override
    public void onEnable() {
        instance = this;
        this.dataWriter = new AsyncFileWriter(this);
        this.veinManager = new VeinManager(this);
        this.kingdomManager = new KingdomManager(this);
        this.kingdomManager.registerKingdomRecipe();
        this.matterManager = new MatterManager(this);
        
        this.townHallManager = new TownHallManager(this);
        this.townHallManager.registerTownHallRecipe();
        this.townHallManager.loadData();
        
        this.soulManager = new SoulManager(this);
        this.soulManager.loadData();
        
        this.authManager = new AuthManager(this);
        this.combatManager = new CombatManager(this);
        
        KingdomBlockListener kingdomBlockListener = new KingdomBlockListener(kingdomManager);
        
        // Регистрация слушателей событий
        OrePreventionListener orePreventionListener = new OrePreventionListener(this, veinManager);
        orePreventionListener.install();
        Bukkit.getPluginManager().registerEvents(orePreventionListener, this);
        Bukkit.getPluginManager().registerEvents(new VeinMiningListener(veinManager), this);
        CompassRadarListener compassRadarListener = new CompassRadarListener(this, veinManager);
        Bukkit.getPluginManager().registerEvents(compassRadarListener, this);
        compassRadarListener.registerRecipe();
        Bukkit.getOnlinePlayers().forEach(player ->
                player.setCompassTarget(player.getWorld().getSpawnLocation()));
        Bukkit.getPluginManager().registerEvents(kingdomBlockListener, this);
        Bukkit.getPluginManager().registerEvents(new TownHallListener(this, townHallManager, kingdomManager), this);
        Bukkit.getPluginManager().registerEvents(new TownHallProtectionListener(this, townHallManager, kingdomManager), this);
        Bukkit.getPluginManager().registerEvents(new SoulListener(this, soulManager), this);
        SoulAltarListener altarListener = new SoulAltarListener(this, soulManager);
        Bukkit.getPluginManager().registerEvents(altarListener, this);
        altarListener.registerAltarRecipe();
        Bukkit.getPluginManager().registerEvents(new AuthListener(this, authManager), this);
        Bukkit.getPluginManager().registerEvents(new CombatListener(combatManager), this);
        Bukkit.getPluginManager().registerEvents(new ElytraBoostListener(), this);
        Bukkit.getPluginManager().registerEvents(new VillagerTradeLockListener(this), this);
        Bukkit.getPluginManager().registerEvents(new RaidLootListener(), this);
        

        
        // Классическая буккитовская привязка исполнителей (на всякий случай)
        if (getCommand("vein") != null) getCommand("vein").setExecutor(new VeinCommand(veinManager));
        if (getCommand("k") != null) getCommand("k").setExecutor(new KingdomCommand(kingdomManager, kingdomBlockListener));
        if (getCommand("convert") != null) getCommand("convert").setExecutor(new ConvertCommand(this, matterManager));
        
        RegisterCommand regCmd = new RegisterCommand(authManager);
        LoginCommand logCmd = new LoginCommand(authManager);
        CivWlCommand wlCmd = new CivWlCommand(authManager);
        LogoutCommand logoutCmd = new LogoutCommand(authManager);
        EscapeCommand escapeCmd = new EscapeCommand(soulManager);
        
        if (getCommand("register") != null) { getCommand("register").setExecutor(regCmd); getCommand("register").setTabCompleter(regCmd); }
        if (getCommand("login") != null) { getCommand("login").setExecutor(logCmd); getCommand("login").setTabCompleter(logCmd); }
        if (getCommand("civwl") != null) { getCommand("civwl").setExecutor(wlCmd); getCommand("civwl").setTabCompleter(wlCmd); }
        if (getCommand("logout") != null) { getCommand("logout").setExecutor(logoutCmd); getCommand("logout").setTabCompleter(logoutCmd); }
        if (getCommand("escape") != null) getCommand("escape").setExecutor(escapeCmd);
        
        // Потоковая инжекция ВСЕХ команд в CommandMap Paper (как мы делали для регистрации!)
        try {
            Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            CommandMap commandMap = (CommandMap) commandMapField.get(Bukkit.getServer());

            commandMap.register("civasunder", new CivAsunderCommand("vein", new VeinCommand(veinManager),
                    "civ_asunder.admin"));
            commandMap.register("civasunder", new CivAsunderCommand("k", new KingdomCommand(kingdomManager, kingdomBlockListener)));
            commandMap.register("civasunder", new CivAsunderCommand("convert", new ConvertCommand(this, matterManager)));
            commandMap.register("civasunder", new CivAsunderCommand("register", regCmd));
            commandMap.register("civasunder", new CivAsunderCommand("login", logCmd));
            commandMap.register("civasunder", new CivAsunderCommand("civwl", wlCmd));
            commandMap.register("civasunder", new CivAsunderCommand("logout", logoutCmd));
            commandMap.register("auth", new CivAsunderCommand("logout", logoutCmd)); // <--- ЖЕСТКАЯ ФИКСАЦИЯ ТУТ!
            commandMap.register("civasunder", new CivAsunderCommand("escape", escapeCmd,
                    "civ_asunder.escape"));

        } catch (Exception e) {
            this.getLogger().severe("[CivAsunder] Ошибка при инжекции командной карты: " + e.getMessage());
        }
        
        Logger logger = this.getLogger();
        logger.info("[CivAsunder] Все модули плагина и 8 глобальных команд успешно синхронизированы!");
    }

    @Override
    public void onDisable() {
        if (veinManager != null) veinManager.cleanUp();
        if (kingdomManager != null) kingdomManager.cleanUp();
        if (townHallManager != null) townHallManager.saveData();
        if (soulManager != null) soulManager.saveData();
        if (authManager != null) authManager.flushSaves();
        if (dataWriter != null) dataWriter.flushAndShutdown();
        this.getLogger().info("[CivAsunder] Базы данных сохранены.");
    }

    public static CivAsunder getInstance() { return instance; }
    public VeinManager getVeinManager() { return veinManager; }
    public KingdomManager getKingdomManager() { return kingdomManager; }
    public MatterManager getMatterManager() { return matterManager; }
    public TownHallManager getTownHallManager() { return townHallManager; }
    public SoulManager getSoulManager() { return soulManager; }
    public AuthManager getAuthManager() { return authManager; }
    public CombatManager getCombatManager() { return combatManager; }
    public AsyncFileWriter getDataWriter() { return dataWriter; }
}
