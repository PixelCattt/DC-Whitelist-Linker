package com.pixelcatt.dc_whitelist_linker;

import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.UUID;


public class CommandHandler implements CommandExecutor {

    private final DCWhitelistLinker plugin;
    private final WhitelistManager manager;


    public CommandHandler(DCWhitelistLinker plugin, WhitelistManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
        switch (command.getName()) {
            case "unlinkdc":
                if (plugin.adminOnlyMode) {
                    sender.sendMessage("§cThis Command is not available in Admin-Only Mode!");
                    return true;
                }

                if (!(sender instanceof Player)) {
                    sender.sendMessage("§cOnly Players can run this Command!");
                    return true;
                }

                Player player = (Player) sender;

                if (player.hasPermission("dcwhitelistlinker.unlinkdc")) {
                    String mcUUID = player.getUniqueId().toString();
                    manager.dbWorker.execute(() -> {
                        java.util.Optional<WhitelistManager.LinkedAccount> account = manager.findLinkedAccountByMinecraftId(mcUUID);
                        if (account.isEmpty()) {
                            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage("§cYou do not have a linked Discord-Account."));
                            return;
                        }
                        manager.startUnLinking(account.get().discordId(), mcUUID).whenComplete((success, error) ->
                                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                                        error == null && Boolean.TRUE.equals(success)
                                                ? "§aUnlinked your Discord account."
                                                : "§cCould not unlink the account. Check the server console.")));
                    });
                }

                return true;


            case "addwhitelist":
                if (sender.hasPermission("dcwhitelistlinker.addwhitelist")) {
                    if (args.length == 2) {
                        String mcName = args[0];
                        String dcName = args[1];

                        if (!manager.dcBot.isReady()) {
                            sender.sendMessage(ChatColor.RED + "The Discord bot is not connected yet. Please try again shortly.");
                            return true;
                        }
                        java.util.Optional<String> discordId = manager.getDiscordUserID(dcName);
                        if (discordId.isEmpty()) {
                            sender.sendMessage(ChatColor.RED + "Could not find Discord user: " + dcName);
                            return true;
                        }
                        String dcUUID = discordId.get();

                        manager.resolveMinecraftUUIDAsync(mcName).whenComplete((uuid, resolutionError) -> {
                            if (resolutionError != null) {
                                plugin.getLogger().warning("Could not resolve Minecraft UUID for " + mcName + ": " + resolutionError.getMessage());
                                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED + "Could not resolve the Minecraft account. Try again or check the server logs."));
                                return;
                            }
                            if (uuid == null) {
                                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ChatColor.RED
                                        + "Could not resolve a definitive Minecraft UUID. No link was created; check LibreLogin UUID mode, Floodgate, and lookup availability."));
                                return;
                            }
                            manager.dbWorker.execute(() -> {
                                String message;
                                try {
                                    boolean discordAlreadyLinked = manager.linkedMinecraftAccountExistsStrict(dcUUID);
                                    boolean minecraftAlreadyLinked = manager.linkedDiscordAccountExistsStrict(uuid.toString());
                                    if (discordAlreadyLinked) {
                                        message = ChatColor.RED + "Discord account " + dcName + " is already linked.";
                                    } else if (minecraftAlreadyLinked) {
                                        message = ChatColor.RED + "Minecraft account " + mcName + " is already linked.";
                                    } else {
                                        boolean linked = manager.createLink(dcUUID, dcName, uuid.toString(), mcName);
                                        message = linked
                                                ? ChatColor.GREEN + "Successfully linked " + mcName + " to " + dcName
                                                : ChatColor.RED + "Discord could not apply the whitelist role or save the link. Check the server console and bot permissions.";
                                    }
                                } catch (RuntimeException error) {
                                    plugin.getLogger().warning("/addwhitelist failed: " + error.getMessage());
                                    message = ChatColor.RED + "The command failed due to a database or Discord error. Check the server console.";
                                }
                                String resultMessage = message;
                                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(resultMessage));
                            });
                        });

                    } else {
                        sender.sendMessage(ChatColor.RED
                                + "Usage: /addwhitelist <Minecraft-Name> <Discord-Name>");
                    }

                } else {
                    sender.sendMessage(ChatColor.RED
                            + "You do not have permission to run this Command!");
                }

                return true;


            case "remwhitelist":
                if (!sender.hasPermission("dcwhitelistlinker.remwhitelist")) {
                    sender.sendMessage(ChatColor.RED
                            + "You do not have permission to run this command!");
                    return true;
                }

                if (args.length != 1) {
                    sender.sendMessage(ChatColor.RED
                            + "Usage: /remwhitelist <Minecraft-Name>");
                    return true;
                }

                String targetMcName = args[0];
                manager.dbWorker.execute(() -> {
                    try {
                        java.util.Optional<WhitelistManager.LinkedAccount> account = manager.findLinkedAccountByMinecraftName(targetMcName);
                        String message;
                        if (account.isPresent()) {
                            WhitelistManager.LinkedAccount linked = account.get();
                            manager.startUnLinking(linked.discordId(), linked.minecraftId()).whenComplete((success, error) -> {
                                String response = error == null && Boolean.TRUE.equals(success)
                                        ? ChatColor.GREEN + "Successfully unlinked " + linked.minecraftName() + " from " + linked.discordName() + "."
                                        : ChatColor.RED + "Could not unlink the account. Check the server console.";
                                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(response));
                            });
                            return;
                        } else {
                            message = ChatColor.RED + "Minecraft account " + targetMcName + " is not linked to any Discord Account.";
                        }
                        String resultMessage = message;
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(resultMessage));
                    } catch (RuntimeException error) {
                        plugin.getLogger().warning("/remwhitelist failed: " + error.getMessage());
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                                ChatColor.RED + "The command failed due to a database error. Check the server console."));
                    }
                });

                return true;
        }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Command /" + command.getName() + " failed: " + e.getMessage());
            sender.sendMessage(ChatColor.RED + "The command failed due to a database or integration error. Check the server console.");
        }

        return true;
    }
}
