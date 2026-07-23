package io.lithcore.civasunder.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import java.util.ArrayList;
import java.util.List;

public class CivAsunderCommand extends Command {

    private final CommandExecutor executor;

    public CivAsunderCommand(String name, CommandExecutor executor) {
        this(name, executor, "civ_asunder.user");
    }

    public CivAsunderCommand(String name, CommandExecutor executor, String permission) {
        super(name);
        this.executor = executor;
        this.setDescription("Динамическая команда CivAsunder");
        this.setPermission(permission);
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        return executor.onCommand(sender, this, commandLabel, args);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) throws IllegalArgumentException {
        if (executor instanceof TabCompleter) {
            List<String> list = ((TabCompleter) executor).onTabComplete(sender, this, alias, args);
            if (list != null) return list;
        }
        return new ArrayList<>();
    }
}
