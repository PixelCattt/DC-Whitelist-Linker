package com.pixelcatt.dc_whitelist_linker;

import org.bukkit.configuration.file.YamlConfiguration;
import com.pixelcatt.offlineplayerlib.OfflinePlayerLib;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class DCWhitelistLinker extends JavaPlugin {

    public WhitelistManager manager;
    private DatabaseWorker dbWorker;
    private DiscordBot dcBot;
    private OfflinePlayerLib offlinePlayerLib;

    private LoginListener loginListener;
    private JoinListener joinListener;

    public boolean adminOnlyMode;

    @Override
    public void onEnable() {
        getLogger().info("~ Created by PixelCatt ~");

        // Initialize Discord-Bot
        getLogger().info("Initializing Discord-Bot...");
        dcBot = new DiscordBot(this);

        offlinePlayerLib = (OfflinePlayerLib) getServer().getPluginManager().getPlugin("OfflinePlayerLib");

        // Initialize Database-Worker
        getLogger().info("Initializing Database-Worker...");
        dbWorker = new DatabaseWorker();

        // Initialize Whitelist-Manager
        getLogger().info("Initializing Whitelist-Manager...");
        manager = new WhitelistManager(this, dbWorker, dcBot);

        // Initialize Event Listeners
        getLogger().info("Initializing Event Listeners...");
        initializeEventListeners();

        // Load Configuration
        getLogger().info("Loading Configuration...");
        loadConfig();

        // Register Commands
        getLogger().info("Registering Commands...");
        registerCommands();

        // Check for Updates
        getLogger().info("Checking for Updates...");
        checkForUpdates();

        // Start Discord Bot
        BukkitRunnable onLoad = new BukkitRunnable() {
            @Override
            public void run() {
                dcBot.startBot();
            }
        };
        onLoad.runTaskLater(this, 1L);
    }


    public void initializeEventListeners() {
        loginListener = new LoginListener(this, manager);
        getServer().getPluginManager().registerEvents(loginListener, this);
        joinListener = new JoinListener(this, manager);
        getServer().getPluginManager().registerEvents(joinListener, this);
    }

    public OfflinePlayerLib getOfflinePlayerLib() {
        return offlinePlayerLib;
    }

    private void loadConfig() {
        Path file = getDataFolder().toPath().resolve("config.yml");
        if (!Files.exists(file)) {
            saveResource("config.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
        String botToken = config.getString("bot-token", "YOUR-BOT-TOKEN-HERE");
        String serverId = config.getString("server-id", "YOUR-SERVER-ID-HERE");
        String whitelistRoleId = config.getString("whitelist-role-id", "YOUR-ROLE-ID-HERE");
        boolean adminOnly = config.getBoolean("admin-only-mode", false);
        boolean requireAccessRole = config.getBoolean("require-access-role", false);
        boolean removeAccessRoleOnUnlink = config.getBoolean("remove-access-role-on-unlink", false);
        String accessRoleId = config.getString("access-role-id", "YOUR-ROLE-ID-HERE");
        boolean useLinkingChannel = config.getBoolean("use-linking-channel", false);
        String linkingChannelId = config.getString("linking-channel-id", "YOUR-CHANNEL-ID-HERE");

        String configVersion = config.getString("config-version", "1.0.0");
        String currentVersion = getDescription().getVersion();


        dcBot.botToken = botToken;
        config.set("bot-token", botToken);

        dcBot.guildId = serverId;
        config.set("server-id", serverId);

        dcBot.whitelistRoleId = whitelistRoleId;
        config.set("whitelist-role-id", whitelistRoleId);

        adminOnlyMode = adminOnly;
        config.set("admin-only-mode", adminOnly);

        dcBot.accessRoleRequired = requireAccessRole;
        config.set("require-access-role", requireAccessRole);

        dcBot.removeAccessRoleOnUnlink = removeAccessRoleOnUnlink;
        config.set("remove-access-role-on-unlink", removeAccessRoleOnUnlink);

        dcBot.accessRoleId = accessRoleId;
        config.set("access-role-id", accessRoleId);

        dcBot.useLinkingChannel = useLinkingChannel;
        config.set("use-linking-channel", useLinkingChannel);

        dcBot.linkingChannelId = linkingChannelId;
        config.set("linking-channel-id", linkingChannelId);

        if (compareVersions(configVersion, "1.0.0") >= 0) {
            if (compareVersions(configVersion, currentVersion) < 0) {
                getLogger().info("Configuration Update: \"config-version\" has been updated to \"" + currentVersion + "\".");
                configVersion = currentVersion;
            }
        } else {
            getLogger().warning("Configuration Error: invalid config-version; resetting it to \"" + currentVersion + "\".");
            configVersion = currentVersion;
        }
        config.set("config-version", configVersion);
        try {
            Path temporary = Files.createTempFile(getDataFolder().toPath(), "config.yml.", ".tmp");
            try {
                Files.writeString(temporary, config.saveToString(), java.nio.charset.StandardCharsets.UTF_8);
                if (Files.exists(file) && Files.mismatch(temporary, file) == -1L) return;
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            getLogger().severe("Could not safely save config.yml; the existing file was preserved: " + e.getMessage());
        }
    }

    private void registerCommands() {
        CommandHandler commandHandler = new CommandHandler(this, this.manager);
        TabCompleter tabCompleter = new TabCompleter(this, this.manager);

        getCommand("unlinkdc").setExecutor(commandHandler);
        getCommand("addwhitelist").setExecutor(commandHandler);
        getCommand("remwhitelist").setExecutor(commandHandler);

        getCommand("unlinkdc").setTabCompleter(tabCompleter);
        getCommand("addwhitelist").setTabCompleter(tabCompleter);
        getCommand("remwhitelist").setTabCompleter(tabCompleter);
    }

    private void checkForUpdates() {
        String[] latestVersion = getLatestVersion().split("\\|", 2);
        String currentVersion = getDescription().getVersion();

        if (!"error".equals(latestVersion[0])) {
            if (isNewerVersion(latestVersion[0], currentVersion)) {
                getLogger().warning("A new Version of DC-Whitelist-Linker is available: " + latestVersion[0]);
                joinListener.setUpdateAvailable(true);
            } else {
                getLogger().info("No new Updates available.");
            }
        } else {
            getLogger().warning("Failed to Check for Updates!\n" + latestVersion[1]);
        }
    }

    public String getLatestVersion() {
        String apiUrl = "https://api.modrinth.com/v2/project/dc_whitelist_linker/version";

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Accept", "application/json")
                    .timeout(java.time.Duration.ofSeconds(8))
                    .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JSONArray jsonArray = new JSONArray(response.body());
                    if (!jsonArray.isEmpty()) {
                        JSONObject latestVersion = jsonArray.getJSONObject(0);
                        return latestVersion.getString("version_number");
                    } else {
                        return "error|No Version Data Found: Project has no Versions on Modrinth";
                    }
                } else {
                    return "error|No Version Data Found: Failed to Connect to Modrinth API";
                }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            getLogger().warning("Failed to check for updates: " + e.getMessage());
            StringWriter stackTrace = new StringWriter();
            e.printStackTrace(new PrintWriter(stackTrace));
            return "error|" + stackTrace;
        }
    }

    public boolean isNewerVersion(String comparingVersion, String referenceVersion) {
        return compareVersions(comparingVersion, referenceVersion) > 0;
    }

    public boolean isOlderVersion(String comparingVersion, String referenceVersion) {
        return compareVersions(comparingVersion, referenceVersion) < 0;
    }

    private int compareVersions(String left, String right) {
        int[] a = versionNumbers(left);
        int[] b = versionNumbers(right);
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(a[i], b[i]);
            if (result != 0) return result;
        }
        boolean aPre = left != null && left.matches(".*-[0-9A-Za-z].*");
        boolean bPre = right != null && right.matches(".*-[0-9A-Za-z].*");
        return aPre == bPre ? 0 : (aPre ? -1 : 1);
    }

    private int[] versionNumbers(String version) {
        int[] numbers = new int[3];
        if (version == null) return numbers;
        Matcher matcher = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matcher(version.trim());
        if (!matcher.matches()) return numbers;
        try {
            for (int i = 0; i < 3; i++) numbers[i] = Integer.parseInt(matcher.group(i + 1));
        } catch (NumberFormatException ignored) { return new int[3]; }
        return numbers;
    }


    // ------------------------------------------------------------ \\


    @Override
    public void onDisable() {
        dcBot.stopBot();
        dbWorker.shutdown();
    }
}
