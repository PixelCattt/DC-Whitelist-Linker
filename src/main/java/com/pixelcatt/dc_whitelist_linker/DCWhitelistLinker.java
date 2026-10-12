package com.pixelcatt.dc_whitelist_linker;

import org.bukkit.configuration.file.FileConfiguration;
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


public class DCWhitelistLinker extends JavaPlugin {

    public WhitelistManager manager;
    private DatabaseWorker dbWorker;
    private DiscordBot dcBot;

    private LoginListener loginListener;
    private JoinListener joinListener;

    public boolean adminOnlyMode;

    @Override
    public void onEnable() {
        getLogger().info("~ Created by PixelCatt ~");

        // Initialize Discord-Bot
        getLogger().info("Initializing Discord-Bot...");
        dcBot = new DiscordBot(this);

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

    private void loadConfig() {
        String botToken = getConfig().getString("bot-token", "YOUR-BOT-TOKEN-HERE");
        String serverId = getConfig().getString("server-id", "YOUR-SERVER-ID-HERE");
        String whitelistRoleId = getConfig().getString("whitelist-role-id", "YOUR-ROLE-ID-HERE");
        boolean adminOnly = getConfig().getBoolean("admin-only-mode", false);
        boolean requireAccessRole = getConfig().getBoolean("require-access-role", false);
        boolean removeAccessRoleOnUnlink = getConfig().getBoolean("remove-access-role-on-unlink", false);
        String accessRoleId = getConfig().getString("access-role-id", "YOUR-ROLE-ID-HERE");
        boolean useLinkingChannel = getConfig().getBoolean("use-linking-channel", false);
        String linkingChannelId = getConfig().getString("linking-channel-id", "YOUR-CHANNEL-ID-HERE");

        String configVersion = getConfig().getString("config-version", "1.0.0");
        String currentVersion = getDescription().getVersion();


        saveResource("config.yml", true);
        reloadConfig();
        FileConfiguration config = getConfig();


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

        if (isNewerVersion(configVersion, "1.0.0")) {
            if (isOlderVersion(configVersion, currentVersion)) {
                getLogger().info("Configuration Update: \"config-version\" has been updated to \"" + currentVersion + "\".");
                configVersion = currentVersion;
            }
        } else {
            getLogger().warning("Configuration Error: \"config-version\" was configured incorrectly and reset to \"" + currentVersion + "\".");
            configVersion = currentVersion;
        }
        config.set("config-version", configVersion);

        saveConfig();
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

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Accept", "application/json")
                    .build();

            try {
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
            } catch (IOException | InterruptedException e) {
                System.out.println("Failed to check for Updates!");

                StringWriter stackTrace = new StringWriter();
                e.printStackTrace(new PrintWriter(stackTrace));
                return "error|" + stackTrace;
            }
        }
    }

    public boolean isNewerVersion(String comparingVersion, String referenceVersion) {
        String[] comparingVersionParts = comparingVersion.split("\\.");
        String[] referenceVersionParts = referenceVersion.split("\\.");

        for (int i = 0; i < 3; i++) {
            int comparingVersionPart = i < comparingVersionParts.length ? Integer.parseInt(comparingVersionParts[i]) : 0;
            int referenceVersionPart = i < referenceVersionParts.length ? Integer.parseInt(referenceVersionParts[i]) : 0;

            if (comparingVersionPart > referenceVersionPart) {
                return true;
            } else if (comparingVersionPart < referenceVersionPart) {
                return false;
            }
        }

        return false;
    }

    public boolean isOlderVersion(String comparingVersion, String referenceVersion) {
        String[] comparingVersionParts = comparingVersion.split("\\.");
        String[] referenceVersionParts = referenceVersion.split("\\.");

        for (int i = 0; i < 3; i++) {
            int comparingVersionPart = i < comparingVersionParts.length ? Integer.parseInt(comparingVersionParts[i]) : 0;
            int referenceVersionPart = i < referenceVersionParts.length ? Integer.parseInt(referenceVersionParts[i]) : 0;

            if (comparingVersionPart < referenceVersionPart) {
                return true;
            } else if (comparingVersionPart > referenceVersionPart) {
                return false;
            }
        }

        return false;
    }


    // ------------------------------------------------------------ \\


    @Override
    public void onDisable() {
        dcBot.stopBot();
        dbWorker.shutdown();
    }
}