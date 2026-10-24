package com.pixelcatt.dc_whitelist_linker;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.sql.SQLException;


public class LoginListener implements Listener {

    private final DCWhitelistLinker plugin;
    private final WhitelistManager manager;

    public LoginListener(DCWhitelistLinker plugin, WhitelistManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();

        final boolean linked;
        try {
            linked = manager.isLinkedForLogin(uuid);
        } catch (SQLException databaseError) {
            plugin.getLogger().severe("Database lookup failed during player login; rejecting login safely: " + databaseError.getMessage());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "Whitelist service is temporarily unavailable. Please try again shortly.");
            return;
        }

        if (!linked) {
            if (plugin.adminOnlyMode) {
                if (Bukkit.getOfflinePlayer(uuid).isOp()) {
                    return;
                }

                event.disallow(
                        AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                        ChatColor.translateAlternateColorCodes('&',
                                "&cYou are not Whitelisted on this Server!")
                );
                return;
            }

            try {
                showLinkingMessage(event, manager.getOrCreateAuthCode(uuid, event.getName()));
            } catch (SQLException databaseError) {
                plugin.getLogger().severe("Could not create a linking code: " + databaseError.getMessage());
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        "Whitelist service is temporarily unavailable. Please try again shortly.");
            }
        }
    }

    public static String genAuthCode() {
        String codeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom randomVal = new SecureRandom();

        StringBuilder authCode = new StringBuilder(6);

        for (int i = 0; i < 6; i++) {
            int index = randomVal.nextInt(codeChars.length());
            authCode.append(codeChars.charAt(index));
        }

        return authCode.toString();
    }

    public void showLinkingMessage(AsyncPlayerPreLoginEvent event, String authCode) {
        event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                ChatColor.translateAlternateColorCodes('&',
                        "&aPlease Link your Discord Account!\n" +
                        "Use the &6/linkmc " + authCode + " &aCommand on Discord!")
        );
    }
}
