package com.pixelcatt.dc_whitelist_linker;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.guild.member.GuildMemberUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;


public class DiscordBot extends ListenerAdapter {

    private JDA jda;
    private final DCWhitelistLinker plugin;
    private WhitelistManager manager;

    public String botToken;
    public String guildId;
    public String whitelistRoleId;
    public boolean accessRoleRequired;
    public boolean removeAccessRoleOnUnlink;
    public String accessRoleId;
    public boolean useLinkingChannel;
    public String linkingChannelId;

    public DiscordBot(DCWhitelistLinker plugin) {
        this.plugin = plugin;
    }


    public void startBot() {
        this.manager = plugin.manager;

        try {
            jda = JDABuilder.createDefault(botToken)
                    .enableIntents(
                            GatewayIntent.GUILD_MEMBERS
                    )
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .addEventListeners(this)
                    .build();

            List<SlashCommandData> slashCommands = new ArrayList<>();

            if (!plugin.adminOnlyMode) {
                slashCommands.add(Commands.slash("linkmc", "Link your Minecraft-Account to your Discord-Account.")
                        .addOption(OptionType.STRING, "auth_code", "Your Auth-Code from Minecraft.", true));
                slashCommands.add(Commands.slash("unlinkmc", "Unlink your Minecraft-Account from your Discord-Account."));
            }

            slashCommands.add(Commands.slash("addwhitelist", "Link a Minecraft and Discord Account")
                    .addOption(OptionType.STRING, "minecraft_name", "The Minecraft Username.", true)
                    .addOption(OptionType.USER, "discord_user", "The Discord User.", true)
                    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)));
            slashCommands.add(Commands.slash("remwhitelist", "Unlink a Minecraft and Discord Account")
                    .addOption(OptionType.STRING, "minecraft_name", "The Minecraft Username.", false)
                    .addOption(OptionType.USER, "discord_user", "The Discord User.", false)
                    .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)));

            jda.updateCommands().addCommands(slashCommands).queue();

            new BukkitRunnable() {
                @Override
                public void run() {
                    UpdateAllMembersTask();
                }
            }.runTaskLater(plugin, 50L);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not initialize the Discord bot", e);
        }
    }

    public boolean isReady() {
        return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    public void UpdateAllMembersTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!isReady()) return;
            Runnable syncTask = () -> manager.getAllLinkedAccountsAsync().thenAccept(linkedAccounts -> {
                Guild guild = jda.getGuildById(guildId);

                if (guild != null) {
                    Role whitelistRole = guild.getRoleById(whitelistRoleId);

                    if (whitelistRole != null) {
                        for (Member member : guild.getMembersWithRoles(whitelistRole)) {
                            boolean isLinked = linkedAccounts.stream().anyMatch(account -> account[0].equals(member.getId()));

                            if (!isLinked) {
                                plugin.getLogger().info("Cleaning Up Non-Linked Discord User: " + member.getUser().getName());
                                finishUnLinking(member.getId());
                            }
                        }
                    }

                    for (Object[] account : linkedAccounts) {
                        String dcUUID = (String) account[0];
                        String dcName = (String) account[1];
                        String mcUUID = (String) account[2];
                        String mcName = (String) account[3];

                        Member member = guild.getMemberById(dcUUID);

                        // The server owner cannot be modified by the bot and is exempt from
                        // automated role cleanup regardless of configured roles.
                        if (dcUUID.equals(guild.getOwnerId())) {
                            continue;
                        }

                        boolean shouldUnlink = member == null || member.getRoles().stream().noneMatch(r -> r.getId().equals(whitelistRoleId));

                        if (!plugin.adminOnlyMode) {
                            shouldUnlink = shouldUnlink || (accessRoleRequired && member != null && member.getRoles().stream().noneMatch(r -> r.getId().equals(accessRoleId)));
                        }

                        if (shouldUnlink) {
                            plugin.getLogger().info("Auto Unlinking Discord User: " + dcName);
                            manager.startUnLinking(dcUUID, mcUUID);
                            continue;
                        }

                        if (!dcUUID.equals(guild.getOwnerId())) member.modifyNickname(mcName).queue();
                    }
                }
            }).exceptionally(error -> {
                plugin.getLogger().severe("Periodic account sync skipped because the database read failed: " + error.getMessage());
                return null;
            });

            if (plugin.adminOnlyMode) {
                syncTask.run();
            } else {
                manager.dbWorker.execute(() -> {
                    syncTask.run();
                    manager.removeExpiredAuthCodesAsync();
                });
            }
        }, 0L, 15 * 60 * 20L);
    }

    public java.util.Optional<String> getUserID(String name) {
        if (!isReady()) return java.util.Optional.empty();
        Guild guild = jda.getGuildById(guildId);

        if (guild != null) {
            Member member = guild.getMembers().stream()
                    .filter(m -> m.getUser().getName().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);

            if (member != null) {
                return java.util.Optional.of(member.getId());
            }
        }

        return java.util.Optional.empty();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();
        try {
        if (event.getGuild() == null) {
            event.getHook().editOriginal("This command can only be used in the configured Discord server.").queue();
            return;
        }
        if (event.getGuild().getId().equals(guildId)) {
                String commandName = event.getName();

                if ("addwhitelist".equals(commandName) || "remwhitelist".equals(commandName)) {
                    manager.dbWorker.execute(() -> {
                        try {
                            handleWhitelistSlashCommand(event);
                        } catch (RuntimeException error) {
                            plugin.getLogger().warning("/" + commandName + " failed: " + error.getMessage());
                            event.getHook().editOriginal("Command failed because of a database or Discord error. Check the server console.").queue();
                        }
                    });
                    return;
                }

                if (plugin.adminOnlyMode) {
                    event.getHook().editOriginal("Account linking commands are disabled in Admin-Only Mode.").queue();
                    return;
                }

                if (useLinkingChannel) {
                    if (!event.getChannel().getId().equals(linkingChannelId)) {
                        event.getHook().editOriginal("Please use the <#" + linkingChannelId + "> Channel for Linking Commands!").queue();
                        return;
                    }
                }

                switch (commandName) {
                    case "linkmc":
                        String authCode = event.getOption("auth_code", OptionMapping::getAsString);
                        if (authCode != null) authCode = authCode.trim().toUpperCase(java.util.Locale.ROOT);

                        if (authCode != null) {
                            if (accessRoleRequired) {
                                Member member = event.getMember();
                                if (member != null) {
                                    if (member.getRoles().stream().noneMatch(role -> role.getId().equals(accessRoleId))) {
                                        event.getHook().editOriginal("The <@&" + accessRoleId + "> Role is required to Link your Account!").queue();
                                        return;
                                    }
                                } else {
                                    event.getHook().editOriginal("Failed to get your User Data! Please try Again.").queue();
                                    return;
                                }
                            }

                            String discordId = event.getUser().getId();
                            String discordName = event.getUser().getName();
                            String code = authCode;
                            manager.dbWorker.execute(() -> {
                                String response;
                                try {
                                    if (manager.isDiscordAccountLinkedForCommand(discordId)) {
                                        response = "You already have a linked Minecraft-Account!";
                                    } else if (!manager.authCodeValid(code)) {
                                        response = "Invalid or Expired Auth-Code!";
                                    } else {
                                        response = manager.finishLinking(code, discordId, discordName)
                                                ? "Successfully linked your Minecraft-Account!"
                                                : "Failed to link your Minecraft-Account. Check the server console and bot role permissions.";
                                    }
                                } catch (RuntimeException error) {
                                    plugin.getLogger().warning("/linkmc failed: " + error.getMessage());
                                    response = "Link failed because of a database or Discord error. Check the server console.";
                                }
                                String resultMessage = response;
                                event.getHook().editOriginal(resultMessage).queue();
                            });
                        } else {
                            event.getHook().editOriginal("Please enter a Valid Auth-Code!").queue();
                        }

                        break;


                    case "unlinkmc":
                        String dcUUID = event.getUser().getId();
                        manager.dbWorker.execute(() -> {
                            try {
                                java.util.Optional<WhitelistManager.LinkedAccount> account = manager.findLinkedAccountByDiscordId(dcUUID);
                                if (account.isEmpty()) {
                                    event.getHook().editOriginal("You do not have a linked Minecraft-Account.").queue();
                                    return;
                                }
                                WhitelistManager.LinkedAccount linked = account.get();
                                manager.startUnLinking(dcUUID, linked.minecraftId()).whenComplete((success, error) ->
                                        event.getHook().editOriginal(error == null && Boolean.TRUE.equals(success)
                                                ? "Unlinked your Minecraft account: " + linked.minecraftName()
                                                : "Could not unlink your Minecraft account. Check the server console.").queue());
                            } catch (RuntimeException error) {
                                plugin.getLogger().warning("/unlinkmc failed: " + error.getMessage());
                                event.getHook().editOriginal("Unlink failed because of a database error. Check the server console.").queue();
                            }
                        });

                        break;
                    default:
                        event.getHook().editOriginal("Unknown command.").queue();
                        break;
                }
        } else {
            event.getHook().editOriginal("This command is not available in this Discord server.").queue();
        }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Slash command failed: " + e.getMessage());
            event.getHook().editOriginal("The command failed while processing. Please check the server console and try again.").queue();
        }
    }

    private void handleWhitelistSlashCommand(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "addwhitelist":
                if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                    event.getHook().editOriginal("You do not have permission to run this command!").queue();
                    return;
                }

                User discordUser = event.getOption("discord_user", OptionMapping::getAsUser);
                String mcName = event.getOption("minecraft_name", OptionMapping::getAsString);

                if (discordUser == null || mcName == null) {
                    event.getHook().editOriginal("Please provide both a Discord user and Minecraft name!").queue();
                    return;
                }

                String dcUUID = discordUser.getId();
                manager.resolveMinecraftUUIDAsync(mcName).whenComplete((uuid, resolutionError) -> {
                    if (resolutionError != null || uuid == null) {
                        if (resolutionError != null) plugin.getLogger().warning("Could not resolve Minecraft UUID for " + mcName + ": " + resolutionError.getMessage());
                        event.getHook().editOriginal("Could not resolve a definitive Minecraft UUID. No link was created; check LibreLogin UUID mode, Floodgate, and lookup availability.").queue();
                        return;
                    }
                    manager.dbWorker.execute(() -> {
                        try {
                            boolean discordLinked = manager.linkedMinecraftAccountExistsStrict(dcUUID);
                            boolean minecraftLinked = manager.linkedDiscordAccountExistsStrict(uuid.toString());

                            if (discordLinked) {
                                String linkedMcName = manager.getMinecraftName(dcUUID).orElse("an existing Minecraft account");
                                event.getHook().editOriginal("Discord account <@" + dcUUID + "> is already linked to Minecraft account: "
                                        + escapeDiscordFormatting(linkedMcName)).queue();
                            } else if (minecraftLinked) {
                                String linkedDcName = manager.getDiscordName(uuid).orElse("an existing Discord account");
                                event.getHook().editOriginal("Minecraft account: " + escapeDiscordFormatting(mcName)
                                        + " is already linked to Discord account: " + escapeDiscordFormatting(linkedDcName)).queue();
                            } else if (manager.createLink(dcUUID, discordUser.getName(), uuid.toString(), mcName)) {
                                event.getHook().editOriginal("Successfully linked " + escapeDiscordFormatting(mcName)
                                        + " to <@" + dcUUID + ">!").queue();
                            } else {
                                event.getHook().editOriginal("Link failed. The bot could not apply the Discord changes; check the server console and bot permissions.").queue();
                            }
                        } catch (RuntimeException error) {
                            plugin.getLogger().warning("Discord addwhitelist failed: " + error.getMessage());
                            event.getHook().editOriginal("The link failed because of a database or Discord error. Please check the server console.").queue();
                        }
                    });
                });
                break;

            case "remwhitelist":
                if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                    event.getHook().editOriginal("You do not have permission to run this command!").queue();
                    return;
                }

                User userOption = event.getOption("discord_user", OptionMapping::getAsUser);
                String mcNameOption = event.getOption("minecraft_name", OptionMapping::getAsString);

                if (userOption == null && mcNameOption == null) {
                    event.getHook().editOriginal("Please provide atleast one of Discord user and Minecraft name!").queue();
                    return;
                }

                java.util.Optional<WhitelistManager.LinkedAccount> found = mcNameOption != null
                        ? manager.findLinkedAccountByMinecraftName(mcNameOption)
                        : manager.findLinkedAccountByDiscordId(userOption.getId());
                if (found.isEmpty()) {
                    event.getHook().editOriginal("No matching linked account was found.").queue();
                    return;
                }
                WhitelistManager.LinkedAccount linked = found.get();
                if (userOption != null && !linked.discordId().equals(userOption.getId())) {
                    event.getHook().editOriginal("That Minecraft account is linked to a different Discord account.").queue();
                    return;
                }
                String responseMcName = linked.minecraftName();
                String responseDcUUID = linked.discordId();
                manager.startUnLinking(linked.discordId(), linked.minecraftId()).whenComplete((success, error) ->
                        event.getHook().editOriginal(error == null && Boolean.TRUE.equals(success)
                                ? "Successfully unlinked " + escapeDiscordFormatting(responseMcName) + " from <@" + responseDcUUID + ">!"
                                : "Could not unlink the account. Check the server console.").queue());
                break;
        }
    }

    @Override
    public void onGuildMemberUpdate(GuildMemberUpdateEvent event) {
        if (event.getGuild().getId().equals(guildId)) {
            String userId = event.getUser().getId();
            String userName = event.getUser().getName();

            event.getGuild().retrieveAuditLogs()
                    .type(ActionType.MEMBER_ROLE_UPDATE)
                    .limit(1)
                    .queue(logs -> {
                        if (!logs.isEmpty()) {
                            var entry = logs.get(0);
                            if (entry.getUser() != null &&
                                    entry.getUser().getId().equals(event.getJDA().getSelfUser().getId())) {
                                return;
                            }
                        }

                        event.getGuild().retrieveMemberById(userId).queue(member -> {
                            boolean hasMemberRole = member.getRoles().stream()
                                    .anyMatch(r -> r.getId().equals(whitelistRoleId));

                            if (userId.equals(event.getGuild().getOwnerId())) {
                                return;
                            }

                            boolean shouldUnlink = !hasMemberRole;

                            if (!plugin.adminOnlyMode && accessRoleRequired) {
                                boolean hasAccessRole = member.getRoles().stream()
                                        .anyMatch(r -> r.getId().equals(accessRoleId));
                                shouldUnlink = shouldUnlink || !hasAccessRole;
                            }

                            if (shouldUnlink) {
                                manager.dbWorker.execute(() -> {
                                    try {
                                        manager.findLinkedAccountByDiscordId(userId).ifPresent(account -> {
                                            plugin.getLogger().info("Auto Unlinking Discord User: " + userName);
                                            manager.startUnLinking(userId, account.minecraftId());
                                        });
                                    } catch (RuntimeException error) {
                                        plugin.getLogger().severe("Automatic unlink failed for " + userName + ": " + error.getMessage());
                                    }
                                });
                            }
                        });
                    });
        }
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        if (event.getGuild().getId().equals(guildId)) {
            plugin.getLogger().info("Player left Discord: " + event.getUser().getName());
            manager.unLinkRemovedMember(event.getUser().getId());
        }
    }

    public boolean finishLinking(String dcUUID, String mcName) {
        if (!isReady()) {
            plugin.getLogger().warning("Cannot link accounts because the Discord bot is not connected.");
            return false;
        }
        try {
            Guild guild = jda.getGuildById(guildId);

            if (guild != null) {
                Member member = guild.retrieveMemberById(dcUUID).complete();
                if (member == null) {
                    plugin.getLogger().info("Error: Member not Found.");
                    return false;
                }
                if (dcUUID.equals(guild.getOwnerId())) return true;
                Role role = guild.getRoleById(whitelistRoleId);

                if (role != null) {
                    if (botRoleHighEnoughToManage(guild, member)) {
                        guild.addRoleToMember(member, role).complete();
                        guild.modifyNickname(member, mcName).complete();
                        return true;
                    } else {
                        plugin.getLogger().info("Linking Error: Bot's Highest Role is not Higher than Linked Member's Highest Role!");
                        return false;
                    }
                } else {
                    plugin.getLogger().info("Error: Whitelist Role not Found.");
                    return false;
                }
            } else {
                plugin.getLogger().info("Error: Guild not Found.");
                return false;
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not apply Discord linking changes: " + e.getMessage());
            return false;
        }
    }

    public boolean finishUnLinking(String dcUUID) {
        if (!isReady()) {
            plugin.getLogger().warning("Cannot unlink accounts because the Discord bot is not connected.");
            return false;
        }
        try {
            Guild guild = jda.getGuildById(guildId);

            if (guild != null) {
                Role role = guild.getRoleById(whitelistRoleId);

                if (role != null) {
                    Member member = guild.retrieveMemberById(dcUUID).complete();

                    if (member != null) {
                        if (dcUUID.equals(guild.getOwnerId())) return true;
                        if (botRoleHighEnoughToManage(guild, member)) {
                            guild.removeRoleFromMember(member, role).complete();
                            if (!dcUUID.equals(guild.getOwnerId())) {
                                guild.modifyNickname(member, null).complete();
                            }
                            return true;
                        } else {
                            plugin.getLogger().info("UnLinking Error: Bot's Highest Role is not Higher than Linked Member's Highest Role!");
                            return false;
                        }
                    } else {
                        plugin.getLogger().info("Error: Member not Found.");
                        return false;
                    }
                } else {
                    plugin.getLogger().info("Error: Whitelist Role not Found.");
                    return false;
                }
            } else {
                plugin.getLogger().info("Error: Guild not Found.");
                return false;
            }
        } catch (ErrorResponseException e) {
            plugin.getLogger().info("Error: " + e.getMessage());
            return false;
        }
    }

    public boolean removeAccessRole(String dcUUID) {
        if (!isReady()) {
            plugin.getLogger().warning("Cannot update Discord roles because the Discord bot is not connected.");
            return false;
        }
        if (accessRoleRequired && removeAccessRoleOnUnlink) {
            Guild guild = jda.getGuildById(guildId);

            if (guild != null) {
                Role role = guild.getRoleById(accessRoleId);

                if (role != null) {
                    try {
                    Member member = guild.retrieveMemberById(dcUUID).complete();

                    if (member != null) {
                        if (dcUUID.equals(guild.getOwnerId())) return true;
                        guild.removeRoleFromMember(member, role).complete();
                        return true;
                    } else {
                        plugin.getLogger().info("Error: Member not Found.");
                        return false;
                    }
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Could not remove access role: " + e.getMessage());
                        return false;
                    }
                } else {
                    plugin.getLogger().info("Error: Access Role not Found.");
                    return false;
                }
            } else {
                plugin.getLogger().info("Error: Guild not Found.");
                return false;
            }
        }
        return true;
    }

    public boolean botRoleHighEnoughToManage(Guild guild, Member member) {
        if (guild != null && member != null) {
            Member bot = guild.getSelfMember();

            if (bot != null) {
                if (bot.getRoles().isEmpty()) {
                    return false;
                }

                if (member.getRoles().isEmpty()) {
                    return true;
                }

                        return bot.getRoles().get(0).getPosition() > member.getRoles().get(0).getPosition();
            }
        }

        return false;
    }

    private String escapeDiscordFormatting(String text) {
        return text.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("~", "\\~");
    }

    public void stopBot() {
        if (jda != null) jda.shutdown();
    }
}
