# Nameless-Plugin

[![build status](https://ci.rkslot.nl/buildStatus/icon?job=Nameless+Plugin)](https://ci.rkslot.nl/job/Nameless%20Plugin/)
[![translation status](https://translate.namelessmc.com/widgets/namelessmc/-/spigot-plugin/svg-badge.svg)](https://translate.namelessmc.com/engage/namelessmc/)
[![discord](https://discord.com/api/guilds/246705793066467328/widget.png?style=shield)](https://discord.gg/nameless)

The official Minecraft plugin for NamelessMC v2. For compiled files see the [spigot resource page](https://www.spigotmc.org/resources/nameless-plugin-for-v2.59032/)

## Forum-controlled account-link reminders

The maintained Paper release `3.4.2-patriam.2` reads the forum's **Link Reminders** StaffCP
settings through the existing Core API connection. Staff can enable/disable reminders, choose
an interval (1–1440 minutes, default 30) and edit separate plain-text messages for missing
Minecraft and Discord verification. The website's LinkReminders module owns configuration
schema 1; no additional local YAML or credentials are needed. Without that module, or while
its settings are disabled/unavailable, the plugin sends no reminder.

Each online session starts a fresh timer. Account state and settings are checked asynchronously
once per minute, in batches of at most 100 UUIDs. Reminders therefore arrive at the first check
after the configured interval, contain a clickable Account Connections link and stop when both
integrations are verified on the same forum account. Fully linked accounts, unknown/ambiguous
identity and failed/stale lookups do not receive reminders. Reconnects/reloads start a fresh timer;
queued replies cannot cross a player session or plugin reload. Forum text cannot execute commands.

This is a private message to the affected online player, not a chat broadcast. No permissions,
groups, faction membership, leadership or Discord guild-membership policy are changed. Reminder
requests participate in the existing testing publication pause.

## Patriam testing publication isolation

The maintained Paper build exposes protocol 1 on its `NamelessMC` plugin instance:
`acquirePublicationPause(String runId, UUID capability)` returns a `CompletionStage<Void>`,
`releasePublicationPause(String runId, UUID capability)` returns a boolean, and
`publicationPauseStatus()` returns a string map containing `protocol`, `state`, `runId`,
`capability` and `inFlight`. These are trusted-plugin operations used by PatriamTesting.

Acquisition closes admission and waits for already queued publication work, including its
scheduled continuations, to finish. During the pause, website data, group sync, store work,
account requests and Websend are withheld. The server-info timer continues to send only
server identity, time, capacity, MOTD and an empty player map. This keeps the website's
server status online without publishing fixture identities; its player count temporarily
shows zero. Player/global providers and placeholders are not called for this heartbeat.
On release, Websend skips logs written during isolation so they are not uploaded later.

No configuration values or API keys are changed. The exact owner/token is persisted in
`publication-pause.state`; it has no timeout. A server restart retains the pause until
PatriamTesting verifies cleanup and releases it. Replacing an active publisher inside the
same JVM, or an invalid/changed receipt, blocks publication and requires recovery. Keep
publisher installation and enable/disable operations outside fixture runs. Never remove a
pause receipt to force sync on while test data may remain.

Local regressions cover queued work and cancellation, durable ownership/restart behavior,
restricted heartbeat contents and resuming the normal data sender. A live website run is
separate acceptance evidence.

## Features
* Multi-platform! Supports Spigot 1.8-1.19, BungeeCord, Velocity, Sponge 7-9.
* Commands to register or verify an account, report a player, read website notifications and more.
* Configurable command names to avoid conflicts
* Configurable messages with translation support
* Server data sender (the plugin can send detailed information about the minecraft servers and the players online to the website)
* Sync Minecraft groups to website groups
* Whitelist registered users
* Ban users on website when banned in-game
* PlaceholderAPI placeholder for number of notifications (bukkit only)
* Send placeholders to website for leaderboards (bukkit only)
* Integration with Websend module to view server logs in StaffCP and run commands on the server.
* Display website announcements in chat

## Installation
1. Install the plugin jar file in the `plugins` folder
2. Restart the server
3. Modify `config.yaml`: enter API URL and server id.
4. Run `/nlpl reload`

## Translations
<a href="http://translate.namelessmc.com/engage/namelessmc/">
<img src="http://translate.namelessmc.com/widgets/namelessmc/-/spigot-plugin/multi-auto.svg" alt="Translation status" />
</a>

## Compiling

Requirements: Maven, Git, JDK 11, JDK 17 (only required for paper and sponge9)

On Debian/Ubuntu: `apt install maven git openjdk-11-jdk openjdk-17-jdk`

You can also use any newer version of the JDK, but that means the compiled plugin won't be compatible with older Java versions.

```sh
git clone https://github.com/NamelessMC/Nameless-Java-API
cd Nameless-Java-API
mvn clean install # Uses JDK 11
cd ..

git clone https://github.com/NamelessMC/Nameless-Plugin
cd Nameless-Plugin
mvn clean package # Uses JDK 11 and 17
# find jar in {bungeecord,paper,spigot,sponge7,sponge8,sponge9,velocity}/target/*
```

Building the entire project can take quite a long time. You might want to build a single module only:
```sh
mvn package -pl velocity -am
```
