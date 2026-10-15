package com.namelessmc.plugin.common.command;

import com.namelessmc.plugin.common.NamelessPlugin;
import com.namelessmc.plugin.common.Permission;
import com.namelessmc.plugin.common.audiences.NamelessCommandSender;
import com.namelessmc.plugin.common.audiences.NamelessConsole;
import com.namelessmc.plugin.common.audiences.NamelessPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.namelessmc.plugin.common.LanguageHandler.Term.*;

public class NamelessPluginCommand extends CommonCommand {
    private static final int PAGE_SIZE = 8;

    public NamelessPluginCommand(final @NonNull NamelessPlugin plugin) {
        super(plugin, "plugin", COMMAND_PLUGIN_USAGE, COMMAND_PLUGIN_DESCRIPTION, Permission.COMMAND_PLUGIN);
        if (this.actualName() == null) plugin.logger().warning("The commands config file is missing the plugin command");
    }

    // Read-only help is public; each mutation/diagnostic retains its actual staff permission below.
    @Override public void verifyPermissionThenExecute(NamelessCommandSender sender, String[] args) {
        execute(sender, args);
    }

    @Override public List<String> verifyPermissionThenComplete(NamelessCommandSender sender, String[] args) {
        return complete(sender, args);
    }

    @Override public void execute(final @NonNull NamelessCommandSender sender, final @NonNull String@NonNull[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("help") || action.equals("?")) {
            sendHelp(sender, args.length > 1 ? args[1] : "1");
            return;
        }
        if (action.equals("reload") || action.equals("rl") || action.equals("last_error")) {
            if (!sender.hasPermission(Permission.COMMAND_PLUGIN)) {
                sender.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
                return;
            }
            if (args.length != 1) {
                sender.sendMessage(Component.text("Invalid command arguments.", NamedTextColor.RED));
                sendHelp(sender, "1");
                return;
            }
            if (action.equals("reload") || action.equals("rl")) {
                this.plugin().unload();
                this.plugin().load();
                sender.sendMessage(this.language().get(COMMAND_PLUGIN_OUTPUT_RELOAD_SUCCESSFUL));
            } else {
                String stackTrace = this.plugin().logger().getLastExceptionStackTrace();
                if (stackTrace == null) sender.sendMessage(Component.text("No error since last plugin reload."));
                else {
                    this.plugin().logger().warning(stackTrace);
                    if (sender instanceof NamelessPlayer) sender.sendMessage(Component.text("Last error has been printed to the console"));
                }
            }
            return;
        }
        sender.sendMessage(Component.text("Unknown command.", NamedTextColor.RED));
        sendHelp(sender, "1");
    }

    private static final class Entry {
        final String form;
        final String description;
        Entry(String form, String description) { this.form = form; this.description = description; }
    }

    private void add(List<Entry> entries, NamelessCommandSender sender, String key, Permission permission, String args, String description) {
        String name = plugin().config().commands().node(key).getString();
        if (name != null && sender.hasPermission(permission)) entries.add(new Entry("/" + name + args, description));
    }

    private List<List<Entry>> sections(NamelessCommandSender sender) {
        List<Entry> ordinary = new ArrayList<>();
        List<Entry> staff = new ArrayList<>();
        ordinary.add(new Entry("/" + actualName() + " help [page|staff]", "Show permitted website commands"));
        add(ordinary, sender, "get-notifications", Permission.COMMAND_GET_NOTIFICATIONS, "", "Read your website notifications");
        add(ordinary, sender, "register", Permission.COMMAND_REGISTER,
                plugin().config().main().node("register-custom-username").getBoolean(true) ? " <email> [username]" : " <email>", "Register your website account");
        add(ordinary, sender, "verify", Permission.COMMAND_VERIFY, " <code>", "Verify your Minecraft account link");
        add(ordinary, sender, "report", Permission.COMMAND_REPORT, " <player> <reason>", "Report a player to website staff");
        add(ordinary, sender, "suggest", Permission.COMMAND_SUGGEST, " <suggestion>", "Send a website suggestion");
        add(ordinary, sender, "store-view-credits", Permission.COMMAND_STORE_VIEW_CREDITS, "", "Show your Store credits");
        if (sender.hasPermission(Permission.COMMAND_PLUGIN)) {
            staff.add(new Entry("/" + actualName() + " reload", "Reload the website integration"));
            staff.add(new Entry("/" + actualName() + " last_error", "Print the last exception to the console"));
        }
        add(staff, sender, "user-info", Permission.COMMAND_USER_INFO, " [username|UUID]", "Inspect website account information");
        add(staff, sender, "store-change-credits", Permission.COMMAND_STORE_CHANGE_CREDITS, " <player> <amount>", "Change a player's Store credits");
        if (sender.hasPermission(Permission.COMMAND_STORE_VIEW_CREDITS)) {
            add(staff, sender, "store-view-credits", Permission.COMMAND_STORE_VIEW_CREDITS_OTHERS, " <player>", "Show another player's Store credits");
        }
        if (sender instanceof NamelessConsole) {
            add(staff, sender, "verify", Permission.COMMAND_VERIFY, " <code> <player>", "Verify an online player's account link from console");
        }
        return List.of(ordinary, staff);
    }

    private int pages(List<Entry> entries) { return (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE; }

    private void sendHelp(NamelessCommandSender sender, String requested) {
        List<List<Entry>> sections = sections(sender);
        int ordinaryPages = pages(sections.get(0));
        int total = ordinaryPages + pages(sections.get(1));
        int page = 1;
        if (requested.equalsIgnoreCase("staff")) {
            if (sections.get(1).isEmpty()) {
                sender.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
                return;
            }
            page = ordinaryPages + 1;
        } else {
            try { page = Integer.parseInt(requested); }
            catch (NumberFormatException invalid) { sender.sendMessage(Component.text("Invalid help page.", NamedTextColor.RED)); }
        }
        page = Math.max(1, Math.min(total, page));
        boolean staffPage = page > ordinaryPages;
        List<Entry> entries = sections.get(staffPage ? 1 : 0);
        int offset = ((staffPage ? page - ordinaryPages : page) - 1) * PAGE_SIZE;
        Component heading = Component.text("---------- ", NamedTextColor.DARK_GRAY)
                .append(Component.text("NamelessMC" + (staffPage ? " Staff" : ""), NamedTextColor.GREEN));
        if (total > 1) heading = heading.append(Component.text(" (", NamedTextColor.DARK_GRAY))
                .append(Component.text(Integer.toString(page), NamedTextColor.WHITE))
                .append(Component.text("/", NamedTextColor.DARK_GRAY)).append(Component.text(Integer.toString(total), NamedTextColor.WHITE))
                .append(Component.text(")", NamedTextColor.DARK_GRAY));
        sender.sendMessage(heading.append(Component.text(" ----------", NamedTextColor.DARK_GRAY)));
        for (int i = offset; i < Math.min(offset + PAGE_SIZE, entries.size()); i++) {
            Entry entry = entries.get(i);
            sender.sendMessage(Component.text(entry.form, NamedTextColor.GREEN)
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY)).append(Component.text(entry.description, NamedTextColor.GRAY)));
        }
        sender.sendMessage(page < total ? Component.text("---------- ", NamedTextColor.DARK_GRAY)
                .append(Component.text("/" + actualName() + " help " + (page + 1), NamedTextColor.GREEN))
                .append(Component.text(" for more ", NamedTextColor.GRAY)).append(Component.text("----------", NamedTextColor.DARK_GRAY))
                : Component.text("----------------------------------------", NamedTextColor.DARK_GRAY));
    }

    @Override public List<String> complete(@NonNull NamelessCommandSender sender, @NonNull String @NonNull [] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("help"));
            if (sender.hasPermission(Permission.COMMAND_PLUGIN)) options.addAll(List.of("reload", "rl", "last_error"));
            return options.stream().filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("help")) {
            List<List<Entry>> sections = sections(sender);
            int total = pages(sections.get(0)) + pages(sections.get(1));
            List<String> options = new ArrayList<>();
            for (int page = 1; page <= total; page++) options.add(Integer.toString(page));
            if (!sections.get(1).isEmpty()) options.add("staff");
            return options.stream().filter(option -> option.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
