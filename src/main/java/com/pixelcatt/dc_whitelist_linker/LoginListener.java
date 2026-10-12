package com.pixelcatt.dc_whitelist_linker;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;


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

        if (manager.getMinecraftNameFromUUID(uuid).equals("ERROR")) {
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

            if (!manager.isLinking(uuid.toString())) {
                String authCode = genAuthCode();

                showLinkingMessage(event, authCode);
                manager.startLinking(uuid.toString(), event.getName(), authCode, Instant.now().plusSeconds(5 * 60).getEpochSecond());
            } else {
                showLinkingMessage(event, manager.getExistingAuthCode(uuid.toString()));
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