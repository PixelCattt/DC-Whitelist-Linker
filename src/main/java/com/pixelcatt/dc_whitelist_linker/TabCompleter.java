package com.pixelcatt.dc_whitelist_linker;


import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import java.util.*;
import java.util.stream.Collectors;


public class TabCompleter implements org.bukkit.command.TabCompleter {

    private final WhitelistManager manager;


    public TabCompleter(DCWhitelistLinker plugin, WhitelistManager manager) {
        this.manager = manager;
    }


    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String string, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        switch (command.getName()) {
            case "unlinkdc":
                return Collections.emptyList();

            case "addwhitelist":
                if (player.hasPermission("dcwhitelistlinker.addwhitelist")) {
                    if (args.length == 1) {
                        Set<String> linkedAccounts = Set.copyOf(manager.getAllLinkedMinecraftAccountNames());

                        List<String> availablePlayers = Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .filter(name -> !linkedAccounts.contains(name))
                                .collect(Collectors.toList());

                        return StringUtil.copyPartialMatches(args[0], availablePlayers, new ArrayList<>());
                    }

                    if (args.length == 2) {
                        return Collections.singletonList("<Discord-Username>");
                    }
                }
                break;

            case "remwhitelist":
                if (player.hasPermission("dcwhitelistlinker.remwhitelist")) {
                    if (args.length == 1) {
                        return StringUtil.copyPartialMatches(
                                args[0],
                                manager.getAllLinkedMinecraftAccountNames(),
                                new ArrayList<>()
                        );
                    }
                }
                break;
        }

        return Collections.emptyList();
    }
}