<div align="center">
  <img src="https://github.com/PixelCattt/DC-Whitelist-Linker/blob/HEAD/icons/DC-Whitelist-Linker-250x250.png?raw=true" alt="DC-Whitelist-Linker-Icon">
  
  <h1>DC-Whitelist-Linker</h1>
  <a href="https://modrinth.com/plugin/dc_whitelist_linker/versions">
    <img src="https://img.shields.io/modrinth/v/dc_whitelist_linker?style=for-the-badge&label=Version&color=5A00FF">
    <img src="https://img.shields.io/modrinth/dt/dc_whitelist_linker?style=for-the-badge&label=Downloads&color=29A100">
  </a>
</div>


## 🎯 Features
- **Self Whitelisting**  
Players can whitelist themselves by Linking their Discord and Minecraft Accounts.  
This removes the need for manual Whitelisting and can be restricted or turned off in the Config.

####

- **Fast & Easy Account Linking**  
Only takes a few Seconds and is very Easy to do.  
Just Enter your Code on Discord, and you’re already Done.

####

- **Automatic Role-Based Whitelisting**  
When an Account is Linked, the Player automatically receives the configured Whitelist Role on Discord.  
This makes managing Server Access to Players very Simple.

####

- **Admin Account Management**  
Admins can easily remove Linked Accounts by taking away their Whitelist/Access Role or using the Add/Rem Whitelist Command.  
This makes Moderation and Account Management Simpler and Efficient.

####

- **Automatic Nicknames**  
When a Player Links their Account, their Discord Nickname is automatically Updated to their Minecraft Username every 15 Minutes.  
This lets you easily Identify Players on the Discord Server.


<hr/>


### DC-Commands:
```
/linkmc <Auth-Code> - Link your Minecraft Account
/unlinkmc - Unlink your Minecraft Account
```

### MC-Commands:
```
/unlinkdc - Unlink your Discord Account
```

### Admin Commands (DC + MC):
```
/addwhitelist <Minecraft_Username> <Discord-Username> - Add a player to the Whitelist
/remwhitelist <Minecraft_Username> - Remove a Player from the Whitelist
```

### Permissions:
```
dcwhitelistlinker.unlinkdc (Default: true) - Allow UnLinking a Discord-Account
dcwhitelistlinker.addwhitelist (Default: op) - Allow adding other Players to the Whitelist
dcwhitelistlinker.remwhitelist (Default: op) - Allow removing a Players from the Whitelist
```

### Config:
```
bot-token - Your Discord Bot-Token
server-id - ID of your Discord Server
whitelist-role-id - ID of the Whitelist Role
admin-only-mode - If only Admins should be able to Link Accounts
require-access-role - If Linking should require a specific Access Role
remove-access-role-on-unlink - If the Access Role should also be removed when UnLinking
access-role-id - ID of the Access Role
use-linking-channel - If Linking should be restricted to a specific Linking Channel
linking-channel-id - ID of the Linking Channel
```