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

    public record LinkedAccount(String discordId, String discordName, String minecraftId, String minecraftName) { }

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
            throw new IllegalStateException("Could not connect to SQLite database", e);
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
            throw new IllegalStateException("Could not create SQLite tables", e);
        }
    }

    private void cleanupLinkingProcesses() {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM linking_process")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not clear linking processes", e);
        }
    }

    public synchronized void startLinking(String mcUUID, String mcName, String authCode, Long expiryTime) {
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
            throw new IllegalStateException("Could not save linking code", e);
        }
    }

    public synchronized Optional<String> getExistingAuthCode(String mcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT auth_code FROM linking_process WHERE mc_uuid = ? LIMIT 1")) {
            ps.setString(1, mcUUID);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString("auth_code"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not look up linking code", e);
        }
        return Optional.empty();
    }

    public synchronized boolean authCodeValid(String authCode) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM linking_process WHERE auth_code = ? AND expiry_time > ? LIMIT 1")) {
            ps.setString(1, authCode.trim().toUpperCase(java.util.Locale.ROOT));
            ps.setLong(2, Instant.now().getEpochSecond());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not validate linking code because the database query failed", e);
        }
    }

    public synchronized boolean isDiscordAccountLinkedForCommand(String dcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM linked_accounts WHERE dc_uuid = ? LIMIT 1")) {
            ps.setString(1, dcUUID);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check Discord account link because the database query failed", e);
        }
    }

    public synchronized boolean linkedMinecraftAccountExistsStrict(String dcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM linked_accounts WHERE dc_uuid = ? LIMIT 1")) {
            ps.setString(1, dcUUID);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check Discord account link", e);
        }
    }

    public synchronized boolean linkedDiscordAccountExistsStrict(String mcUUID) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM linked_accounts WHERE mc_uuid = ? LIMIT 1")) {
            ps.setString(1, mcUUID);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check Minecraft account link", e);
        }
    }



    public synchronized Optional<LinkedAccount> findLinkedAccountByMinecraftName(String name) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT dc_uuid, dc_name, mc_uuid, mc_name FROM linked_accounts WHERE mc_name = ? COLLATE NOCASE LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new LinkedAccount(rs.getString("dc_uuid"), rs.getString("dc_name"),
                        rs.getString("mc_uuid"), rs.getString("mc_name")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not look up Minecraft account", e);
        }
    }

    public synchronized Optional<LinkedAccount> findLinkedAccountByMinecraftId(String uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT dc_uuid, dc_name, mc_uuid, mc_name FROM linked_accounts WHERE mc_uuid = ? LIMIT 1")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new LinkedAccount(rs.getString("dc_uuid"), rs.getString("dc_name"),
                        rs.getString("mc_uuid"), rs.getString("mc_name")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not look up Minecraft account", e);
        }
    }

    public synchronized Optional<LinkedAccount> findLinkedAccountByDiscordId(String discordId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT dc_uuid, dc_name, mc_uuid, mc_name FROM linked_accounts WHERE dc_uuid = ? LIMIT 1")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new LinkedAccount(rs.getString("dc_uuid"), rs.getString("dc_name"),
                        rs.getString("mc_uuid"), rs.getString("mc_name")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not look up Discord account", e);
        }
    }


    public synchronized void removeExpiredAuthCodesAsync() {
        if (plugin.adminOnlyMode) {
            return;
        }

        dbWorker.execute(() -> {
            synchronized (WhitelistManager.this) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM linking_process WHERE expiry_time < ?")) {
                ps.setLong(1, Instant.now().getEpochSecond());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not remove expired linking codes: " + e.getMessage());
            }
            }
        });
    }

    public synchronized boolean finishLinking(String authCode, String dcUUID, String dcName) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM linking_process WHERE auth_code = ? AND expiry_time > ? LIMIT 1")) {
            ps.setString(1, authCode.trim().toUpperCase(java.util.Locale.ROOT));
            ps.setLong(2, Instant.now().getEpochSecond());
            try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String mcUUID = rs.getString("mc_uuid");
                String mcName = rs.getString("mc_name");

                if (!dcBot.finishLinking(dcUUID, mcName)) return false;

                try (PreparedStatement ps2 = connection.prepareStatement(
                        "REPLACE INTO linked_accounts(dc_uuid, dc_name, mc_uuid, mc_name) VALUES (?, ?, ?, ?)")) {
                    ps2.setString(1, dcUUID);
                    ps2.setString(2, dcName);
                    ps2.setString(3, mcUUID);
                    ps2.setString(4, mcName);
                    ps2.executeUpdate();
                }

                try (PreparedStatement ps3 = connection.prepareStatement(
                        "DELETE FROM linking_process WHERE auth_code = ?")) {
                    ps3.setString(1, authCode.trim().toUpperCase(java.util.Locale.ROOT));
                    ps3.executeUpdate();
                }

                return true;
            }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not finish linking", e);
        }

        return false;
    }

    public synchronized boolean createLink(String dcUUID, String dcName, String mcUUID, String mcName) {
        if (!dcBot.finishLinking(dcUUID, mcName)) return false;
        try (PreparedStatement ps = connection.prepareStatement(
                "REPLACE INTO linked_accounts(dc_uuid, dc_name, mc_uuid, mc_name) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, dcUUID);
            ps.setString(2, dcName);
            ps.setString(3, mcUUID);
            ps.setString(4, mcName);
            ps.executeUpdate();

            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create link", e);
        }
    }

    public synchronized CompletableFuture<Boolean> startUnLinking(String dcUUID, String mcUUID) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        dbWorker.execute(() -> {
            synchronized (WhitelistManager.this) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM linked_accounts WHERE dc_uuid = ?")) {
                ps.setString(1, dcUUID);
                ps.executeUpdate();
                boolean whitelistRoleRemoved = dcBot.finishUnLinking(dcUUID);
                boolean accessRoleRemoved = dcBot.removeAccessRole(dcUUID);
                if (!whitelistRoleRemoved || !accessRoleRemoved) {
                    result.complete(false);
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(UUID.fromString(mcUUID));
                    if (player == null) return;
                    if (plugin.adminOnlyMode) {
                        player.kickPlayer(ChatColor.translateAlternateColorCodes('&', "&cYou are not Whitelisted on this Server!"));
                    } else {
                        new BukkitRunnable() {
                            @Override public void run() {
                                if (player.isOnline()) player.kickPlayer(ChatColor.translateAlternateColorCodes('&', "&aYour Discord-Account has been Unlinked!"));
                            }
                        }.runTaskLater(plugin, 50L);
                    }
                });
                result.complete(true);
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().severe("Could not unlink account: " + e.getMessage());
                result.completeExceptionally(new IllegalStateException("Could not unlink account", e));
            }
            }
        });
        return result;
    }

    public synchronized void kickPlayerUnWL(Player plr) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plr != null) {
                plr.kickPlayer(ChatColor.translateAlternateColorCodes('&', "&cYou are not Whitelisted on this Server!"));
            }
        });
    }

    public synchronized Optional<String> getDiscordUserID(String name) {
        return dcBot.getUserID(name);
    }

    private record MinecraftResolutionContext(UUID knownUuid, boolean libreLogin, boolean floodgate,
                                              boolean onlineMode, String floodgatePrefix, File libreLoginFolder) { }
    private record MojangLookup(UUID uuid, boolean definitivelyNotFound) { }

    /** Resolve known identities on the server thread and perform external lookups asynchronously. */
    public CompletableFuture<UUID> resolveMinecraftUUIDAsync(String name) {
        return plugin.getOfflinePlayerLib().GetOfflinePlayerByName(name)
                .thenApply(player -> player == null ? null : player.getUniqueId());
    }

    private UUID resolveMinecraftUUID(String name, MinecraftResolutionContext info) {
        if (info.knownUuid() != null) return info.knownUuid();

        String prefix = info.floodgatePrefix();
        boolean prefixedBedrockName = info.floodgate() && prefix != null && !prefix.isEmpty() && name.startsWith(prefix);
        if (info.floodgate()) {
            MojangLookup bedrockLookup = lookupGeyserBedrockUuid(name, prefix);
            if (bedrockLookup.uuid() != null) return bedrockLookup.uuid();
            if (prefixedBedrockName) {
                plugin.getLogger().warning("Could not resolve Floodgate UUID for " + name + "; refusing to guess a different identity.");
                return null;
            }
        }

        if (info.libreLogin()) {
            String creator = readLibreLoginUuidCreator(info.libreLoginFolder());
            if ("CRACKED".equals(creator)) return offlineUuid(name);
            if ("MOJANG".equals(creator)) {
                MojangLookup lookup = lookupMojangUuid(name);
                if (lookup.uuid() != null) return lookup.uuid();
                // LibreLogin's MOJANG creator falls back to CRACKED only for a
                // definitive no-profile response, never for a network/API failure.
                return lookup.definitivelyNotFound() ? offlineUuid(name) : null;
            }
            plugin.getLogger().warning("LibreLogin uses UUID creator '" + creator
                    + "'; this UUID cannot be predicted for a player who has never joined. No link was created.");
            return null;
        }

        if (!info.onlineMode()) return offlineUuid(name);
        return getUUID(name);
    }

    private MojangLookup lookupGeyserBedrockUuid(String name, String prefix) {
        HttpURLConnection http = null;
        try {
            String encodedName = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8);
            String encodedPrefix = java.net.URLEncoder.encode(prefix == null ? "" : prefix, StandardCharsets.UTF_8);
            URL url = new URL("https://api.geysermc.org/v2/utils/uuid/bedrock_or_java/" + encodedName
                    + "?prefix=" + encodedPrefix);
            http = (HttpURLConnection) url.openConnection();
            http.setRequestMethod("GET");
            http.setConnectTimeout(3000);
            http.setReadTimeout(3000);
            int status = http.getResponseCode();
            if (status == 204) return new MojangLookup(null, true);
            if (status != 200) {
                plugin.getLogger().warning("Geyser did not return a Bedrock UUID for " + name + " (HTTP " + status + "); trying the configured Java UUID mode.");
                return new MojangLookup(null, true);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                String returnedName = json.get("name").getAsString();
                if (prefix != null && !prefix.isEmpty() && returnedName.startsWith(prefix)) {
                    return new MojangLookup(parseUndashedUuid(json.get("id").getAsString()), false);
                }
                return new MojangLookup(null, true);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Geyser Bedrock UUID lookup failed for " + name + "; trying the configured Java UUID mode: " + e.getMessage());
            return new MojangLookup(null, true);
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static UUID parseUndashedUuid(String id) {
        if (id.length() != 32) throw new IllegalArgumentException("Unexpected UUID format");
        return UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12) + "-"
                + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20));
    }

    private MojangLookup lookupMojangUuid(String name) {
        HttpURLConnection http = null;
        try {
            String encodedName = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8);
            http = (HttpURLConnection) new URL("https://api.mojang.com/users/profiles/minecraft/" + encodedName).openConnection();
            http.setRequestMethod("GET");
            http.setConnectTimeout(3000);
            http.setReadTimeout(3000);
            int status = http.getResponseCode();
            if (status == 204 || status == 404) return new MojangLookup(null, true);
            if (status != 200) return new MojangLookup(null, false);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                return new MojangLookup(parseUndashedUuid(json.get("id").getAsString()), false);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Mojang UUID lookup failed for " + name + ": " + e.getMessage());
            return new MojangLookup(null, false);
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private String readLibreLoginUuidCreator(File libreLoginFolder) {
        if (libreLoginFolder == null) return "UNKNOWN";
        File config = new File(libreLoginFolder, "config.conf");
        try {
            String contents = java.nio.file.Files.readString(config.toPath(), StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?m)^\\s*new-uuid-creator\\s*=\\s*([A-Za-z]+)")
                    .matcher(contents);
            return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "UNKNOWN";
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not read LibreLogin UUID creator setting: " + e.getMessage());
            return "UNKNOWN";
        }
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void unLinkRemovedMember(String dcUUID) {
        dbWorker.execute(() -> {
            synchronized (WhitelistManager.this) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM linked_accounts WHERE dc_uuid = ?")) {
                    ps.setString(1, dcUUID);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().warning("Could not remove departed Discord member from the database: " + e.getMessage());
                }
            }
        });
    }



    public synchronized void updateMinecraftName(String mcUUID, String mcName) {
        dbWorker.execute(() -> {
            synchronized (WhitelistManager.this) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE linked_accounts SET mc_name = ? WHERE mc_uuid = ?")) {
                    ps.setString(1, mcName);
                    ps.setString(2, mcUUID);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().warning("Could not update stored Minecraft name: " + e.getMessage());
                }
            }
        });
    }



    public CompletableFuture<List<Object[]>> getAllLinkedAccountsAsync() {
        CompletableFuture<List<Object[]>> future = new CompletableFuture<>();
        dbWorker.execute(() -> {
            List<Object[]> accounts = new ArrayList<>();
            synchronized (WhitelistManager.this) {
                try (PreparedStatement linked = connection.prepareStatement(
                            "SELECT dc_uuid, dc_name, mc_uuid, mc_name FROM linked_accounts");
                     ResultSet rs = linked.executeQuery()) {
                    while (rs.next()) {
                        accounts.add(new Object[]{rs.getString("dc_uuid"), rs.getString("dc_name"),
                                rs.getString("mc_uuid"), rs.getString("mc_name")});
                    }
                } catch (SQLException e) {
                    future.completeExceptionally(new IllegalStateException("Could not read linked accounts", e));
                    return;
                }
            }
            future.complete(accounts);
        });
        return future;
    }


    public synchronized List<String> getAllLinkedDiscordAccountNames() {
        return getNames("SELECT dc_name FROM linked_accounts", "dc_name");
    }

    public synchronized List<String> getAllLinkedMinecraftAccountNames() {
        return getNames("SELECT mc_name FROM linked_accounts", "mc_name");
    }

    private List<String> getNames(String sql, String column) {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) names.add(rs.getString(column));
            return names;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read linked account names", e);
        }
    }



    public synchronized boolean linkedDiscordAccountExists(String mcUUID) {
        return linkedDiscordAccountExistsStrict(mcUUID);
    }

    public CompletableFuture<Boolean> linkedDiscordAccountExistsAsync(String mcUUID) {
        return CompletableFuture.supplyAsync(() -> linkedDiscordAccountExistsStrict(mcUUID), dbWorker);
    }

    public synchronized boolean isLinking(String mcUUID) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM linking_process WHERE mc_uuid = ? LIMIT 1")) {
            ps.setString(1, mcUUID);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not check linking state", e);
        }
    }

    public synchronized boolean linkedMinecraftAccountExists(String dcUUID) {
        return linkedMinecraftAccountExistsStrict(dcUUID);
    }

    public CompletableFuture<Boolean> linkedMinecraftAccountExistsAsync(String dcUUID) {
        return CompletableFuture.supplyAsync(() -> linkedMinecraftAccountExistsStrict(dcUUID), dbWorker);
    }


    private Optional<String> findString(String sql, String value, String column, String description) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(column)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not look up " + description, e);
        }
    }

    public synchronized Optional<String> getDiscordName(UUID mcUUID) {
        return findString("SELECT dc_name FROM linked_accounts WHERE mc_uuid = ? LIMIT 1", mcUUID.toString(), "dc_name", "Discord name");
    }

    public CompletableFuture<Optional<String>> getDiscordNameAsync(UUID mcUUID) {
        return CompletableFuture.supplyAsync(() -> getDiscordName(mcUUID), dbWorker);
    }

    public synchronized Optional<String> getDiscordUUID(UUID mcUUID) {
        return findString("SELECT dc_uuid FROM linked_accounts WHERE mc_uuid = ? LIMIT 1", mcUUID.toString(), "dc_uuid", "Discord ID");
    }

    public synchronized Optional<String> getDiscordNameFromUUID(String dcUUID) {
        return findString("SELECT dc_name FROM linked_accounts WHERE dc_uuid = ? LIMIT 1", dcUUID, "dc_name", "Discord name");
    }

    public synchronized Optional<String> getDiscordUUIDfromName(String dcName) {
        return findString("SELECT dc_uuid FROM linked_accounts WHERE dc_name = ? COLLATE NOCASE LIMIT 1", dcName, "dc_uuid", "Discord ID");
    }

    public synchronized Optional<String> getMinecraftName(String dcUUID) {
        return findString("SELECT mc_name FROM linked_accounts WHERE dc_uuid = ? LIMIT 1", dcUUID, "mc_name", "Minecraft name");
    }

    public CompletableFuture<Optional<String>> getMinecraftNameAsync(String dcUUID) {
        return CompletableFuture.supplyAsync(() -> getMinecraftName(dcUUID), dbWorker);
    }

    public synchronized Optional<String> getMinecraftUUID(String dcUUID) {
        return findString("SELECT mc_uuid FROM linked_accounts WHERE dc_uuid = ? LIMIT 1", dcUUID, "mc_uuid", "Minecraft UUID");
    }

    public synchronized Optional<String> getMinecraftNameFromUUID(UUID mcUUID) {
        return findString("SELECT mc_name FROM linked_accounts WHERE mc_uuid = ? LIMIT 1", mcUUID.toString(), "mc_name", "Minecraft name");
    }

    /** Returns whether the UUID is linked; SQL failures propagate to the login handler. */
    public synchronized boolean isLinkedForLogin(UUID mcUUID) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM linked_accounts WHERE mc_uuid = ? LIMIT 1")) {
            ps.setString(1, mcUUID.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }


    /** Returns an unexpired code, replacing an expired code; database errors propagate. */
    public synchronized String getOrCreateAuthCode(UUID mcUUID, String mcName) throws SQLException {
        long now = Instant.now().getEpochSecond();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT auth_code, expiry_time FROM linking_process WHERE mc_uuid = ? LIMIT 1")) {
            query.setString(1, mcUUID.toString());
            try (ResultSet rs = query.executeQuery()) {
                if (rs.next() && rs.getLong("expiry_time") > now) return rs.getString("auth_code");
            }
        }
        String code = LoginListener.genAuthCode();
        try (PreparedStatement replace = connection.prepareStatement(
                "REPLACE INTO linking_process(dc_uuid, dc_name, mc_uuid, mc_name, auth_code, expiry_time) VALUES (?, ?, ?, ?, ?, ?)")) {
            replace.setString(1, "?");
            replace.setString(2, "?");
            replace.setString(3, mcUUID.toString());
            replace.setString(4, mcName);
            replace.setString(5, code);
            replace.setLong(6, now + 300);
            replace.executeUpdate();
        }
        return code;
    }

    public synchronized Optional<String> getMinecraftUUIDfromName(String mcName) {
        return findString("SELECT mc_uuid FROM linked_accounts WHERE mc_name = ? COLLATE NOCASE LIMIT 1", mcName, "mc_uuid", "Minecraft UUID");
    }

    public synchronized Optional<String> getMinecraftUuidFromName(String mcName) {
        return getMinecraftUUIDfromName(mcName);
    }

    public synchronized Optional<String> getDiscordUuidFromMcName(String mcName) {
        return findString("SELECT dc_uuid FROM linked_accounts WHERE mc_name = ? COLLATE NOCASE LIMIT 1", mcName, "dc_uuid", "Discord ID");
    }

    public synchronized Optional<String> getDiscordNameFromMinecraftUUID(String mcUUID) {
        return findString("SELECT dc_name FROM linked_accounts WHERE mc_uuid = ? LIMIT 1", mcUUID, "dc_name", "Discord name");
    }

    public static UUID getUUID(String name) {
        HttpURLConnection connection = null;
        try {
            String encodedName = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8);
            URL url = name.startsWith(".")
                    ? new URL("https://api.geysermc.org/v2/utils/uuid/bedrock_or_java/" + encodedName + "?prefix=.")
                    : new URL("https://api.mojang.com/users/profiles/minecraft/" + encodedName);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            int status = connection.getResponseCode();
            if (status != 200) return null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                String id = json.get("id").getAsString();
                return UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12) + "-"
                        + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20));
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
