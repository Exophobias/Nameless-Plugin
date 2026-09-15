package com.namelessmc.plugin.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only, versioned forum settings and link observations. Never a permission grant. */
final class LinkReminderResponse {
    enum State { UNLINKED, DISCORD_MISSING, COMPLETE, UNKNOWN }

    final boolean enabled;
    final long intervalNanos;
    final String connectionsUrl;
    final String unlinkedMessage;
    final String discordMessage;
    final Map<UUID, State> players;

    private LinkReminderResponse(boolean enabled, int minutes, String url, String unlinked,
                                 String discord, Map<UUID, State> players) {
        this.enabled = enabled;
        this.intervalNanos = Duration.ofMinutes(minutes).toNanos();
        this.connectionsUrl = url;
        this.unlinkedMessage = unlinked;
        this.discordMessage = discord;
        this.players = Map.copyOf(players);
    }

    static LinkReminderResponse parse(JsonObject json, Set<UUID> requested) {
        if (integer(json, "protocol_version") != 1 || integer(json, "config_version") != 1) {
            throw invalid();
        }
        JsonElement enabled = json.get("enabled");
        if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean()) {
            throw invalid();
        }
        int minutes = integer(json, "interval_minutes");
        if (minutes < 1 || minutes > 1440) throw invalid();
        String url = string(json, "connections_url", 2048);
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) throw invalid();
        JsonObject messages = object(json, "messages");
        String unlinked = string(messages, "unlinked", 500);
        String discord = string(messages, "discord_missing", 500);
        Map<UUID, State> players = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : object(json, "players").entrySet()) {
            UUID uuid = UUID.fromString(entry.getKey());
            if (!uuid.toString().equals(entry.getKey()) || !requested.contains(uuid)) throw invalid();
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
            State state;
            switch (value.getAsString()) {
                case "unlinked": state = State.UNLINKED; break;
                case "discord_missing": state = State.DISCORD_MISSING; break;
                case "complete": state = State.COMPLETE; break;
                case "unknown": state = State.UNKNOWN; break;
                default: throw invalid();
            }
            players.put(uuid, state);
        }
        return new LinkReminderResponse(enabled.getAsBoolean(), minutes, url, unlinked, discord, players);
    }

    Component message(State state) {
        if (state != State.UNLINKED && state != State.DISCORD_MISSING) throw invalid();
        // Forum text is deliberately literal: no MiniMessage, commands or embedded click events.
        return Component.text("[Patriam] ", NamedTextColor.GOLD)
                .append(Component.text(state == State.UNLINKED ? unlinkedMessage : discordMessage,
                        NamedTextColor.GRAY))
                .append(Component.text(" [Account Connections]", NamedTextColor.GOLD)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(connectionsUrl))
                        .hoverEvent(HoverEvent.showText(Component.text("Open your forum account connections"))));
    }

    private static int integer(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("[0-9]{1,6}")) throw invalid();
        return Integer.parseInt(value.getAsString());
    }

    private static JsonObject object(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject()) throw invalid();
        return value.getAsJsonObject();
    }

    private static String string(JsonObject object, String name, int maximum) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
        String text = value.getAsString();
        if (text.isBlank() || text.codePointCount(0, text.length()) > maximum
                || text.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return text;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid link-reminder response");
    }
}
