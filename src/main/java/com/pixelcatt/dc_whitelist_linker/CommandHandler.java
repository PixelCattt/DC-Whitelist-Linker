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

                    if (!mcUUID.equals("ERROR")) {
                        String dcUUID = manager.getDiscordUUID(UUID.fromString(mcUUID));

                        if (!dcUUID.equals("ERROR")) {
                            sender.sendMessage("§aUnlinking your Discord-Account: " + manager.getDiscordName(UUID.fromString(mcUUID)));
                            manager.startUnLinking(dcUUID, mcUUID);
                        } else {
                            sender.sendMessage("§cYou do not have a linked Discord-Account.");
                        }
                    } else {
                        sender.sendMessage("§cFailed to determine your UUID!");
                    }
                }

                return true;


            case "addwhitelist":
                if (sender.hasPermission("dcwhitelistlinker.addwhitelist")) {
                    if (args.length == 2) {
                        String mcName = args[0];
                        String dcName = args[1];

                        UUID uuid = WhitelistManager.getUUID(mcName);

                        if (uuid == null) {
                            sender.sendMessage(ChatColor.RED + "Could not find Minecraft player: " + mcName);
                            return true;
                        }

                        String mcUUID = uuid.toString();
                        String dcUUID = manager.getDiscordUserID(dcName);

                        if (dcUUID.equals("ERROR")) {
                            sender.sendMessage(ChatColor.RED + "Could not find Discord user: " + dcName);
                            return true;
                        }

                        boolean discordAlreadyLinked = manager.linkedMinecraftAccountExists(dcUUID);
                        boolean minecraftAlreadyLinked = manager.linkedDiscordAccountExists(mcUUID);

                        if (!discordAlreadyLinked && !minecraftAlreadyLinked) {
                            manager.createLink(dcUUID, dcName, mcUUID, mcName);

                            sender.sendMessage(ChatColor.GREEN
                                    + "Successfully linked "
                                    + mcName
                                    + " to "
                                    + dcName);

                        } else if (discordAlreadyLinked) {
                            String linkedMcName = manager.getMinecraftName(dcUUID);

                            sender.sendMessage(ChatColor.RED
                                    + "Discord account "
                                    + dcName
                                    + " is already linked to Minecraft account: "
                                    + linkedMcName);

                        } else {
                            String linkedDcName = manager.getDiscordNameFromMinecraftUUID(mcUUID);

                            sender.sendMessage(ChatColor.RED
                                    + "Minecraft account "
                                    + mcName
                                    + " is already linked to Discord account: "
                                    + linkedDcName);
                        }

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
                String targetMcUUID = manager.getMinecraftUuidFromName(targetMcName);

                if (targetMcUUID.equals("ERROR")) {
                    sender.sendMessage(ChatColor.RED
                            + "Could not find Minecraft player: "
                            + targetMcName);
                    return true;
                }

                String targetDcUUID = manager.getDiscordUuidFromMcName(targetMcName);

                if (targetDcUUID.equals("ERROR")) {
                    sender.sendMessage(ChatColor.RED
                            + "Minecraft account "
                            + targetMcName
                            + " does not have a linked Discord account.");
                    return true;
                }

                String targetDcName = manager.getDiscordNameFromUUID(targetDcUUID);

                if (manager.linkedMinecraftAccountExists(targetDcUUID) && manager.linkedDiscordAccountExists(targetMcUUID)) {
                    manager.startUnLinking(targetDcUUID, targetMcUUID);

                    sender.sendMessage(ChatColor.GREEN
                            + "Successfully unlinked "
                            + targetMcName
                            + " from "
                            + targetDcName
                            + ".");

                } else {
                    sender.sendMessage(ChatColor.RED
                            + "Minecraft account "
                            + targetMcName
                            + " is not linked to any Discord Account.");
                }

                return true;
        }


        return true;
    }
}
