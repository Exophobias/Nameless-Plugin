package com.namelessmc.plugin.common;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.namelessmc.plugin.common.audiences.*;
import com.namelessmc.plugin.common.command.AbstractScheduledTask;
import com.namelessmc.plugin.common.command.AbstractScheduler;
import com.namelessmc.plugin.common.logger.JulLogger;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.audience.Audience;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the real provider, HTTP client and per-user sync route against a local endpoint. */
class GroupSyncTest {
    @TempDir Path temp;

    @Test void bothSiteVersionsAcceptApiAndReceiveOnlyGroupDeltas() throws Exception {
        for (String version : List.of("2.2.5", "2.3.0")) {
            try (Fixture f = new Fixture(temp.resolve(version), version)) {
                UUID id = f.addPlayer("Reader", Set.of("member", "builder"));
                f.sync();
                assertEquals(1, f.requests.size());
                f.assertDelta(0, id, Set.of("member", "builder"), Set.of());
                f.sync();
                assertEquals(1, f.requests.size(), "Acknowledged groups need no request");
                f.groups.put(id, Set.of("member", "moderator"));
                f.sync();
                f.assertDelta(1, id, Set.of("moderator"), Set.of("builder"));
                assertNotNull(f.plugin.apiProvider().apiIfCached());
                assertFalse(f.plugin.config().main().node("api", "bypass-version-check").getBoolean());
            }
        }
    }

    @Test void failedRemovalRetainsBaselineAndRetriesWithoutResendingSuccessfulPlayers() throws Exception {
        try (Fixture f = new Fixture(temp, "2.3.0")) {
            UUID first = f.addPlayer("First", Set.of("member", "builder"));
            UUID second = f.addPlayer("Second", Set.of("member"));
            f.sync();
            f.requests.clear();
            f.paths.clear();
            f.groups.put(first, Set.of("member"));
            f.groups.put(second, Set.of("member", "helper"));
            f.failUuid = first;
            f.sync();
            assertEquals(2, f.requests.size());
            f.sync();
            assertEquals(3, f.requests.size());
            f.assertDelta(2, first, Set.of(), Set.of("builder"));
            f.sync();
            assertEquals(3, f.requests.size());
        }
    }

    @Test void pendingSnapshotsAreNotDuplicatedAndRetiredWorkDoesNotSend() throws Exception {
        try (Fixture f = new Fixture(temp, "2.3.0")) {
            f.addPlayer("Reader", Set.of("member"));
            f.plugin.groupSync().syncGroups();
            f.plugin.groupSync().syncGroups();
            assertEquals(1, f.raw.queue.size());
            f.plugin.groupSync().unload();
            f.raw.drain();
            assertTrue(f.requests.isEmpty());
        }
    }

    @Test void inactiveUnverifiedAndMissingLinksDoNotReceiveGroupChanges() throws Exception {
        try (Fixture f = new Fixture(temp, "2.3.0")) {
            UUID inactive = f.addPlayer("Inactive", Set.of("member"));
            UUID unverified = f.addPlayer("Unverified", Set.of("member"));
            UUID missing = f.addPlayer("Missing", Set.of("member"));
            UUID valid = f.addPlayer("Valid", Set.of("member"));
            f.inactive.add(inactive.toString().replace("-", ""));
            f.unverified.add(unverified.toString().replace("-", ""));
            f.missing.add(missing.toString().replace("-", ""));
            f.sync();
            assertEquals(1, f.requests.size());
            f.assertDelta(0, valid, Set.of("member"), Set.of());
            f.inactive.clear();
            f.unverified.clear();
            f.missing.clear();
            f.sync();
            assertEquals(4, f.requests.size(), "Eligibility can recover without a group change or reload");
        }
    }

    private static Set<String> strings(JsonObject value, String key) {
        Set<String> result = new HashSet<>();
        value.getAsJsonArray(key).forEach(item -> result.add(item.getAsString()));
        return result;
    }

    private static final class QueueScheduler extends AbstractScheduler {
        final Deque<Runnable> queue = new ArrayDeque<>();
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
        public void runAsync(Runnable task) { queue.add(task); }
        public void runSync(Runnable task) { queue.add(task); }
        public AbstractScheduledTask runTimer(Runnable task, Duration interval) {
            return new AbstractScheduledTask() { public void cancel() { } };
        }
        public AbstractScheduledTask runDelayed(Runnable task, Duration delay) {
            return new AbstractScheduledTask() { public void cancel() { } };
        }
    }

    private static final class Fixture implements AutoCloseable {
        final QueueScheduler raw = new QueueScheduler();
        final Map<UUID, Set<String>> groups = new HashMap<>();
        final List<NamelessPlayer> players = new ArrayList<>();
        final List<JsonObject> requests = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        final Map<String, Integer> userIds = new HashMap<>();
        final HttpServer server;
        final NamelessPlugin plugin;
        UUID failUuid;
        final Set<String> inactive = new HashSet<>();
        final Set<String> unverified = new HashSet<>();
        final Set<String> missing = new HashSet<>();

        Fixture(Path path, String version) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String route = exchange.getRequestURI().getPath();
                String response;
                if (route.equals("/info")) {
                    response = "{\"nameless_version\":\"" + version + "\",\"modules\":[\"Core\"],\"locale\":\"en_UK\"}";
                } else if (route.startsWith("/users/")) {
                    String uuid = route.substring(route.lastIndexOf(':') + 1);
                    if (missing.contains(uuid)) response = "{\"error\":\"nameless:cannot_find_user\"}";
                    else response = "{\"id\":" + userIds.get(uuid) + ",\"exists\":true,\"validated\":" + !inactive.contains(uuid)
                            + ",\"integrations\":[{\"integration\":\"Minecraft\",\"identifier\":\"" + uuid
                            + "\",\"username\":\"Reader\",\"verified\":" + !unverified.contains(uuid)
                            + ",\"linked_date\":1,\"show_publicly\":true}]}";
                } else {
                    paths.add(route);
                    requests.add(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8)).getAsJsonObject());
                    if (failUuid != null && route.equals("/minecraft/id:" + userIds.get(failUuid.toString().replace("-", "")) + "/sync-groups")) {
                        response = "{\"error\":\"core:invalid_server_id\"}";
                        failUuid = null;
                    } else response = "{}";
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            plugin = new NamelessPlugin(path, raw,
                    config -> new JulLogger(config, Logger.getLogger("group-sync-test")), null, "test", "test") {
                @Override public AbstractPermissions permissions() {
                    return new AbstractPermissions() {
                        public boolean isUsable() { return true; }
                        public Set<String> getGroups() { return Set.of(); }
                        public Set<String> getPlayerGroups(NamelessPlayer player) { return groups.get(player.uuid()); }
                        public void load() { }
                        public void unload() { }
                    };
                }
            };
            plugin.config().load();
            plugin.config().main().node("api", "url").set("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            plugin.config().main().node("api", "key").set("local-test");
            plugin.config().main().node("api", "server-id").set(7);
            plugin.config().main().node("group-sync", "enabled").set(true);
            plugin.setAudienceProvider(new AbstractAudienceProvider() {
                public NamelessConsole console() { throw new AssertionError(); }
                public Audience broadcast() { return Audience.empty(); }
                public NamelessPlayer player(UUID id) { return null; }
                public NamelessPlayer playerByUsername(String name) { return null; }
                public Collection<NamelessPlayer> onlinePlayers() { return players; }
            });
            plugin.apiProvider().load();
            raw.drain();
            assertNotNull(plugin.apiProvider().api(), "Version gate must accept " + version);
            plugin.groupSync().load();
            raw.drain();
        }
        UUID addPlayer(String name, Set<String> memberships) {
            UUID id = UUID.randomUUID();
            groups.put(id, memberships);
            userIds.put(id.toString().replace("-", ""), userIds.size() + 1);
            players.add(new NamelessPlayer(plugin.config(), Audience.empty(), id, name) {
                public boolean hasPermission(Permission permission) { return true; }
            });
            return id;
        }
        void sync() { plugin.groupSync().syncGroups(); raw.drain(); }
        void assertDelta(int index, UUID id, Set<String> add, Set<String> remove) {
            assertEquals("/minecraft/id:" + userIds.get(id.toString().replace("-", "")) + "/sync-groups", paths.get(index));
            JsonObject request = requests.get(index);
            assertEquals(7, request.get("server_id").getAsInt());
            assertEquals(add, strings(request, "add"));
            assertEquals(remove, strings(request, "remove"));
        }
        public void close() { server.stop(0); }
    }
}
