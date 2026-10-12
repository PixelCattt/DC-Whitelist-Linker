package com.pixelcatt.dc_whitelist_linker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;


public class WhitelistManager {

    private final DCWhitelistLinker plugin;

    public final DiscordBot dcBot;
    public final Executor dbWorker;

    private final File dbFile;
    private Connection connection;

    public WhitelistManager(DCWhitelistLinker plugin, DatabaseWorker dbWorker, DiscordBot dcBot) {
        this.plugin = plugin;
        this.dbWorker = query -> dbWorker.getDatabaseExecutor().execute(query);
        this.dcBot = dcBot;
        this.dbFile = new File(plugin.getDataFolder(), "users.db");
        openConnection();
        createTables();
        cleanupLinkingProcesses();
    }


    private void openConnection() {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        } catch (Exception e) {
            plugin.getLogger().severe("Could not connect to SQLite database!");
            e.printStackTrace();
        }
    }

    private void createTables() {
        try (Statement stmt = connection.createStatement()) {
            // Linking-Process Table
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS linking_process (" +
                    "dc_uuid TEXT NOT NULL," +
                    "dc_name TEXT NOT NULL," +
                    "mc_uuid TEXT NOT NULL," +
                    "mc_name TEXT NOT NULL," +
                    "auth_code TEXT NOT NULL," +
                    "expiry_time LONG NOT NULL," +
                    "PRIMARY KEY(mc_uuid))");

            // Linked-Accounts Table
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS linked_accounts (" +
                    "dc_uuid TEXT NOT NULL," +
                    "dc_name TEXT NOT NULL," +
                    "mc_uuid TEXT NOT NULL," +
                    "mc_name TEXT NOT NULL," +
                    "PRIMARY KEY(mc_uuid))");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create tables in SQLite database.");
            e.printStackTrace();
        }
    }

    private void cleanupLinkingProcesses() {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM linking_process")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void startLinking(String mcUUID, String mcName, String authCode, Long expiryTime) {
        try (PreparedStatement ps = connection.prepareStatement(
                "REPLACE INTO linking_process(dc_uuid, dc_name, mc_uuid, mc_name, auth_code, expiry_time) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "?");
            ps.setString(2, "?");
            ps.setString(3, mcUUID);
            ps.setString(4, mcName);
            ps.setString(5, authCode);
            ps.setLong(6, expiryTime);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String getExistingAuthCode(String mcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT auth_code FROM linking_process WHERE mc_uuid = ? LIMIT 1")) {
            ps.setString(1, mcUUID);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("auth_code");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "ERROR";
        }

        return "ERROR";
    }

    public boolean authCodeValid(String authCode) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM linking_process WHERE auth_code = ? AND expiry_time > ? LIMIT 1")) {
            ps.setString(1, authCode);
            ps.setLong(2, Instant.now().getEpochSecond());
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void removeExpiredAuthCodesAsync() {
        if (plugin.adminOnlyMode) {
            return;
        }

        dbWorker.execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM linking_process WHERE expiry_time < ?")) {
                ps.setLong(1, Instant.now().getEpochSecond());
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        });
    }

    public boolean finishLinking(String authCode, String dcUUID, String dcName) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM linking_process WHERE auth_code = ? LIMIT 1")) {
            ps.setString(1, authCode);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String mcUUID = rs.getString("mc_uuid");
                String mcName = rs.getString("mc_name");

                PreparedStatement ps2 = connection.prepareStatement(
                        "REPLACE INTO linked_accounts(dc_uuid, dc_name, mc_uuid, mc_name) VALUES (?, ?, ?, ?)");
                ps2.setString(1, dcUUID);
                ps2.setString(2, dcName);
                ps2.setString(3, mcUUID);
                ps2.setString(4, mcName);
                ps2.executeUpdate();

                PreparedStatement ps3 = connection.prepareStatement(
                        "DELETE FROM linking_process WHERE auth_code = ?");
                ps3.setString(1, authCode);
                ps3.executeUpdate();

                dcBot.finishLinking(dcUUID, dcName);

                return true;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }

        return false;
    }

    public void createLink(String dcUUID, String dcName, String mcUUID, String mcName) {
        try (PreparedStatement ps = connection.prepareStatement(
                "REPLACE INTO linked_accounts(dc_uuid, dc_name, mc_uuid, mc_name) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, dcUUID);
            ps.setString(2, dcName);
            ps.setString(3, mcUUID);
            ps.setString(4, mcName);
            ps.executeUpdate();

            dcBot.finishLinking(dcUUID, mcName);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void startUnLinking(String dcUUID, String mcUUID) {

        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM linked_accounts WHERE dc_uuid = ?")) {
            ps.setString(1, dcUUID);
            ps.executeUpdate();

            dcBot.finishUnLinking(dcUUID);

            Player player = Bukkit.getPlayer(UUID.fromString(mcUUID));

            if (plugin.adminOnlyMode) {
                kickPlayerUnWL(player);
            } else {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (player != null && player.isOnline()) {
                            player.kickPlayer(ChatColor.translateAlternateColorCodes('§', "§aYour Discord-Account has been Unlinked!"));
                        }
                    }
                }.runTaskLater(plugin, 50L);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void kickPlayerUnWL(Player plr) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plr != null) {
                plr.kickPlayer(ChatColor.translateAlternateColorCodes('&', "&cYou are not Whitelisted on this Server!"));
            }
        });
    }

    public String getDiscordUserID(String name) {
        return dcBot.getUserID(name);
    }

    public void unLinkRemovedMember(String dcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM linked_accounts WHERE dc_uuid = ?")) {
            ps.setString(1, dcUUID);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }



    public void updateMinecraftName(String mcUUID, String mcName) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE linked_accounts SET mc_name = ? WHERE mc_uuid = ?")) {
            ps.setString(1, mcName);
            ps.setString(2, mcUUID);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }



    public CompletableFuture<List<Object[]>> getAllLinkedAccountsAsync() {
        CompletableFuture<List<Object[]>> future = new CompletableFuture<>();

        dbWorker.execute(() -> {
            List<Object[]> linkedAccountNames = new ArrayList<>();

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT dc_uuid, dc_name, mc_uuid, mc_name FROM linked_accounts")) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    String dcUUID = rs.getString("dc_uuid");
                    String dcName = rs.getString("dc_name");
                    String mcUUID = rs.getString("mc_uuid");
                    String mcName = rs.getString("mc_name");

                    linkedAccountNames.add(new Object[]{dcUUID, dcName, mcUUID, mcName} );
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            Bukkit.getScheduler().runTask(plugin, () -> future.complete(linkedAccountNames));
        });

        return future;
    }



    public List<String> getAllLinkedDiscordAccountNames() {
        List <String> linkedAccountNames = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT dc_name FROM linked_accounts")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                linkedAccountNames.add(rs.getString("dc_name"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return linkedAccountNames;
    }

    public List<String> getAllLinkedMinecraftAccountNames() {
        List <String> linkedAccountNames = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT mc_name FROM linked_accounts")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                linkedAccountNames.add(rs.getString("mc_name"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return linkedAccountNames;
    }



    public boolean linkedDiscordAccountExists(String mcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM linked_accounts WHERE mc_uuid = ? LIMIT 1")) {
            ps.setString(1, mcUUID);
            ResultSet rs = ps.executeQuery();

            return rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public CompletableFuture<Boolean> linkedDiscordAccountExistsAsync(String mcUUID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        dbWorker.execute(() -> {
            boolean accountExists;

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM linked_accounts WHERE mc_uuid = ? LIMIT 1")) {
                ps.setString(1, mcUUID);
                ResultSet rs = ps.executeQuery();
                accountExists = rs.next();
            } catch (SQLException e) {
                e.printStackTrace();
                accountExists = false;
            }


            final boolean finalAccountExists = accountExists;
            Bukkit.getScheduler().runTask(plugin, () -> future.complete(finalAccountExists));
        });

        return future;
    }


    public boolean isLinking(String mcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM linking_process WHERE mc_uuid = ? LIMIT 1")) {
            ps.setString(1, mcUUID);
            ResultSet rs = ps.executeQuery();

            return rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }


    public boolean linkedMinecraftAccountExists(String dcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM linked_accounts WHERE dc_uuid = ? LIMIT 1")) {
            ps.setString(1, dcUUID);
            ResultSet rs = ps.executeQuery();

            return rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public CompletableFuture<Boolean> linkedMinecraftAccountExistsAsync(String dcUUID) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        dbWorker.execute(() -> {
            boolean accountExists;

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM linked_accounts WHERE dc_uuid = ? LIMIT 1")) {
                ps.setString(1, dcUUID);
                ResultSet rs = ps.executeQuery();
                accountExists = rs.next();
            } catch (SQLException e) {
                e.printStackTrace();
                accountExists = false;
            }


            final boolean finalAccountExists = accountExists;
            Bukkit.getScheduler().runTask(plugin, () -> future.complete(finalAccountExists));
        });

        return future;
    }



    public String getDiscordName(UUID mcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT dc_name FROM linked_accounts WHERE mc_uuid = ? LIMIT 1")) {
            ps.setString(1, mcUUID.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("dc_name");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "ERROR";
        }

        return "ERROR";
    }

    public CompletableFuture<String> getDiscordNameAsync(UUID mcUUID) {
        CompletableFuture<String> future = new CompletableFuture<>();

        dbWorker.execute(() -> {
            String discordName = "ERROR";

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT DISTINCT dc_name FROM linked_accounts WHERE mc_uuid = ? LIMIT 1")) {
                ps.setString(1, mcUUID.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    discordName = rs.getString("dc_name");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                discordName = "ERROR";
            }


            final String finalDiscordName = discordName;
            Bukkit.getScheduler().runTask(plugin, () -> future.complete(finalDiscordName));
        });

        return future;
    }

    public String getDiscordUUID(UUID mcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT dc_uuid FROM linked_accounts WHERE mc_uuid = ? LIMIT 1")) {
            ps.setString(1, mcUUID.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("dc_uuid");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "ERROR";
        }

        return "ERROR";
    }

    public String getDiscordNameFromUUID(String dcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT dc_name FROM linked_accounts WHERE dc_uuid = ? LIMIT 1")) {
            ps.setString(1, dcUUID);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("dc_name");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "ERROR";
        }

        return "ERROR";
    }

    public String getDiscordUUIDfromName(String dcName) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT dc_uuid FROM linked_accounts WHERE dc_name = ? LIMIT 1")) {
            ps.setString(1, dcName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("dc_uuid");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "ERROR";
        }

        return "ERROR";
    }

    public String getMinecraftName(String dcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT mc_name FROM linked_accounts WHERE dc_uuid = ? LIMIT 1")) {
            ps.setString(1, dcUUID);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("mc_name");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "ERROR";
        }

        return "ERROR";
    }

    public CompletableFuture<String> getMinecraftNameAsync(String dcUUID) {
        CompletableFuture<String> future = new CompletableFuture<>();

        dbWorker.execute(() -> {
            String minecraftName = "ERROR";

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT DISTINCT mc_name FROM linked_accounts WHERE dc_uuid = ? LIMIT 1")) {
                ps.setString(1, dcUUID);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    minecraftName = rs.getString("mc_name");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                minecraftName = "ERROR";
            }


            final String finalMinecraftName = minecraftName;
            Bukkit.getScheduler().runTask(plugin, () -> future.complete(finalMinecraftName));
        });

        return future;
    }

    public String getMinecraftUUID(String dcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT mc_uuid FROM linked_accounts WHERE dc_uuid = ? LIMIT 1")) {
            ps.setString(1, dcUUID);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("mc_uuid");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "ERROR";
        }

        return "ERROR";
    }

    public String getMinecraftNameFromUUID(UUID mcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT mc_name FROM linked_accounts WHERE mc_uuid = ? LIMIT 1")) {
            ps.setString(1, mcUUID.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("mc_name");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "ERROR";
        }

        return "ERROR";
    }

    public String getMinecraftUUIDfromName(String mcName) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT mc_uuid FROM linked_accounts WHERE mc_name = ? LIMIT 1")) {
            ps.setString(1, mcName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("mc_uuid");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "ERROR";
        }

        return "ERROR";
    }

    public String getMinecraftUuidFromName(String mcName) {
        return getMinecraftUUIDfromName(mcName);
    }

    public String getDiscordUuidFromMcName(String mcName) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT dc_uuid FROM linked_accounts WHERE mc_name = ? LIMIT 1")) {
            ps.setString(1, mcName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("dc_uuid");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "ERROR";
        }

        return "ERROR";
    }

    public String getDiscordNameFromMinecraftUUID(String mcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT dc_name FROM linked_accounts WHERE mc_uuid = ? LIMIT 1")) {
            ps.setString(1, mcUUID);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("dc_name");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "ERROR";
        }

        return "ERROR";
    }

    public static UUID getUUID(String name) {
        try {
            if (name.startsWith(".")) {
                URL url = new URL(
                        "https://api.geysermc.org/v2/utils/uuid/bedrock_or_java/"
                                + name + "?prefix=."
                );

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);

                int status = connection.getResponseCode();

                if (status != 200) {
                    return null;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()))) {

                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    String id = json.get("id").getAsString();

                    return UUID.fromString(
                            id.substring(0, 8) + "-" +
                                    id.substring(8, 12) + "-" +
                                    id.substring(12, 16) + "-" +
                                    id.substring(16, 20) + "-" +
                                    id.substring(20)
                    );
                }
            }

            URL url = new URL(
                    "https://api.mojang.com/users/profiles/minecraft/" + name
            );

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);

            int status = connection.getResponseCode();

            if (status == 204 || status == 404) {
                return UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)
                );
            }

            if (status != 200) {
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {

                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                String id = json.get("id").getAsString();

                return UUID.fromString(
                        id.substring(0, 8) + "-" +
                                id.substring(8, 12) + "-" +
                                id.substring(12, 16) + "-" +
                                id.substring(16, 20) + "-" +
                                id.substring(20)
                );
            }

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}