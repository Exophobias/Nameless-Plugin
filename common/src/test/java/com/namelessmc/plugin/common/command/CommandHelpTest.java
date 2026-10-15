package com.namelessmc.plugin.common.command;

import com.namelessmc.plugin.common.NamelessPlugin;
import com.namelessmc.plugin.common.Permission;
import com.namelessmc.plugin.common.audiences.NamelessCommandSender;
import com.namelessmc.plugin.common.audiences.NamelessConsole;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import com.namelessmc.plugin.common.logger.JulLogger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CommandHelpTest {
    @TempDir Path temp;

    private static final class Scheduler extends AbstractScheduler {
        int async;
        public void runAsync(Runnable runnable) { async++; }
        public void runSync(Runnable runnable) { runnable.run(); }
        public AbstractScheduledTask runTimer(Runnable runnable, Duration interval) { return null; }
        public AbstractScheduledTask runDelayed(Runnable runnable, Duration delay) { return null; }
    }
    private static final class Plugin extends NamelessPlugin {
        int loads, unloads;
        Plugin(Path root, Scheduler scheduler) {
            super(root, scheduler, config -> new JulLogger(config, Logger.getLogger("command-help-test")), null, "test", "test");
        }
        @Override public void load() { loads++; }
        @Override public void unload() { unloads++; }
    }
    private static final class Fixture {
        final Plugin plugin;
        final Scheduler scheduler = new Scheduler();
        final List<Component> messages = new ArrayList<>();
        final NamelessPluginCommand root;
        final NamelessCommandSender sender;
        Fixture(Path path, boolean console, Permission... permissions) throws Exception {
            plugin = new Plugin(path, scheduler);
            plugin.config().load();
            plugin.language().load();
            plugin.config().commands().node("store-view-credits").set("credits");
            plugin.config().commands().node("store-change-credits").set("changecredits");
            plugin.config().commands().node("suggest").set("suggest");
            EnumSet<Permission> granted = EnumSet.noneOf(Permission.class);
            for (Permission permission : permissions) granted.add(permission);
            Audience audience = new Audience() {
                @Override public void sendMessage(Component message) { messages.add(message); }
            };
            sender = console ? new NamelessConsole(audience) {
                public void dispatchCommand(String command) { fail("Help must not dispatch console commands"); }
                @Override public boolean hasPermission(Permission permission) { return granted.contains(permission); }
            } : new NamelessPlayer(plugin.config(), audience, UUID.randomUUID(), "Reader") {
                public boolean hasPermission(Permission permission) { return granted.contains(permission); }
            };
            root = new NamelessPluginCommand(plugin);
        }
        void run(String... args) { root.verifyPermissionThenExecute(sender, args); }
        String text() { return messages.stream().map(CommandHelpTest::plain).reduce("", (a, b) -> a + "\n" + b); }
    }
    private static String plain(Component component) {
        String text = component instanceof TextComponent ? ((TextComponent) component).content() : "";
        for (Component child : component.children()) text += plain(child);
        return text;
    }

    @Test void ordinaryRootHelpFiltersCommandsAndHasNoStaffHints() throws Exception {
        Fixture f = new Fixture(temp, false, Permission.COMMAND_GET_NOTIFICATIONS, Permission.COMMAND_VERIFY);
        f.run();
        assertTrue(f.text().contains("/notifications - Read"));
        assertTrue(f.text().contains("/verify <code> - Verify"));
        assertFalse(f.text().contains("reload"));
        assertFalse(f.text().contains("last_error"));
        assertFalse(f.text().contains("<code> <player>"));
        assertFalse(f.text().contains("for more"));
        assertEquals(List.of("help"), f.root.complete(f.sender, new String[]{""}));
        assertEquals(List.of("1"), f.root.complete(f.sender, new String[]{"help", ""}));
    }

    @Test void partialStaffGrantsSeparateOtherCreditTargetsFromOrdinaryCommands() throws Exception {
        Fixture f = new Fixture(temp, false, Permission.COMMAND_STORE_VIEW_CREDITS, Permission.COMMAND_STORE_VIEW_CREDITS_OTHERS);
        f.run("help");
        assertTrue(f.text().contains("/credits - Show your"));
        assertFalse(f.text().contains("/credits <player>"));
        assertTrue(f.text().contains("/nlpl help 2"));
        f.messages.clear(); f.run("help", "staff");
        assertTrue(f.text().contains("NamelessMC Staff"));
        assertTrue(f.text().contains("/credits <player>"));
        assertFalse(f.text().contains("reload"));
        assertFalse(f.text().contains("for more"));
    }

    @Test void staffNavigationAndPrivilegedExecutionRemainDeniedWithoutActualGrant() throws Exception {
        Fixture f = new Fixture(temp, false);
        f.run("help", "staff");
        assertEquals(1, f.messages.size());
        assertTrue(f.text().contains("do not have permission"));
        for (String command : List.of("reload", "rl", "last_error")) f.run(command);
        assertEquals(0, f.plugin.loads);
        assertEquals(0, f.plugin.unloads);
        assertEquals(0, f.scheduler.async);
        assertFalse(f.root.complete(f.sender, new String[]{"help", ""}).contains("staff"));
    }

    @Test void permittedReloadAliasUsesActualHandlerAndStaffHelpOnlyShowsGrantedTools() throws Exception {
        Fixture f = new Fixture(temp, false, Permission.COMMAND_PLUGIN);
        f.run("rl");
        assertEquals(1, f.plugin.loads);
        assertEquals(1, f.plugin.unloads);
        f.messages.clear(); f.run("help", "staff");
        assertTrue(f.text().contains("/nlpl reload"));
        assertTrue(f.text().contains("/nlpl last_error"));
        assertFalse(f.text().contains("changecredits"));
        assertTrue(f.root.complete(f.sender, new String[]{"last"}).contains("last_error"));
    }

    @Test void numericBoundsUnknownCommandsAndInvalidPagesShowOnlyPermittedContent() throws Exception {
        Fixture f = new Fixture(temp, false, Permission.COMMAND_PLUGIN, Permission.COMMAND_VERIFY);
        for (String page : List.of("0", "99", "banana")) {
            f.messages.clear(); f.run("help", page);
            assertTrue(f.messages.stream().map(CommandHelpTest::plain).filter(line -> line.startsWith("/")).count() <= 8);
            assertFalse(f.text().contains("changecredits"));
        }
        f.messages.clear(); f.run("unknown");
        assertTrue(f.text().contains("Unknown command"));
        assertTrue(f.text().contains("/verify <code>"));
    }

    @Test void consoleCanReadPlayerHelpWhilePlayerCannotUseConsoleOnlyVerification() throws Exception {
        Fixture console = new Fixture(temp.resolve("console"), true, Permission.COMMAND_VERIFY);
        console.run("help");
        assertTrue(console.text().contains("/verify <code>"));
        console.messages.clear(); console.run("help", "staff");
        assertTrue(console.text().contains("/verify <code> <player>"));
        Fixture player = new Fixture(temp.resolve("player"), false, Permission.COMMAND_VERIFY);
        new VerifyCommand(player.plugin).verifyPermissionThenExecute(player.sender, new String[]{"code", "Other"});
        assertEquals(0, player.scheduler.async);
        assertFalse(player.messages.isEmpty());
    }

    @Test void disabledCommandsAndUnpermittedCreditsTargetsNeverReachSuggestionCache() throws Exception {
        Fixture f = new Fixture(temp, false, Permission.COMMAND_VERIFY, Permission.COMMAND_STORE_VIEW_CREDITS);
        f.plugin.config().commands().node("verify").set(null);
        f.run();
        assertFalse(f.text().contains("/verify"));
        assertTrue(new StoreViewCreditsCommand(f.plugin).verifyPermissionThenComplete(f.sender, new String[]{""}).isEmpty());
        new StoreChangeCreditsCommand(f.plugin).verifyPermissionThenExecute(f.sender, new String[]{"Other", "100"});
        assertEquals(0, f.scheduler.async);
    }
}
