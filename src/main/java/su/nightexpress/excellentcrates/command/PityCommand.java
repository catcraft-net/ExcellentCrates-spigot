package su.nightexpress.excellentcrates.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.Placeholders;
import su.nightexpress.excellentcrates.config.Lang;
import su.nightexpress.excellentcrates.config.Perms;
import su.nightexpress.excellentcrates.crate.pity.PitySettings;
import su.nightexpress.excellentcrates.user.CrateUser;
import su.nightexpress.nightcore.commands.Arguments;
import su.nightexpress.nightcore.commands.Commands;
import su.nightexpress.nightcore.commands.builder.HubNodeBuilder;
import su.nightexpress.nightcore.commands.context.CommandContext;
import su.nightexpress.nightcore.commands.context.ParsedArguments;
import su.nightexpress.nightcore.util.Lists;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CatCraft: {@code /crate pity [player]} shows pity counters (keys since the last Ultra Legendary, per group);
 * {@code /crate pity set <player> <group> <n>} sets one. Both need {@code excellentcrates.command.pity};
 * both work from the console.
 */
public class PityCommand {

    private static final String GROUP = "group";

    private final CratesPlugin plugin;

    public PityCommand(@NotNull CratesPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(@NotNull HubNodeBuilder nodeBuilder) {
        nodeBuilder.branch(Commands.hub("pity")
            .description(Lang.COMMAND_PITY_DESC.text())
            .permission(Perms.COMMAND_PITY)
            .withHelpCommand(false)
            .executes(this::show) // /crate pity = your own counters (console: name a player with /crate pity show <player>)
            .branch(Commands.literal("show")
                .description(Lang.COMMAND_PITY_DESC.text())
                .permission(Perms.COMMAND_PITY)
                .withArguments(Arguments.playerName(CommandArguments.PLAYER).optional())
                .executes(this::show)
            )
            .branch(Commands.literal("set")
                .description(Lang.COMMAND_PITY_SET_DESC.text())
                .permission(Perms.COMMAND_PITY)
                .withArguments(
                    Arguments.playerName(CommandArguments.PLAYER),
                    Arguments.string(GROUP).suggestions((reader, context) -> this.plugin.getPityService().getGroups()),
                    Arguments.integer(CommandArguments.AMOUNT, 0).suggestions((reader, context) -> Lists.newList("0", "119", "149"))
                )
                .executes(this::set)
            )
        );
    }

    private boolean show(@NotNull CommandContext context, @NotNull ParsedArguments arguments) {
        CommandSender sender = context.getSender();
        String name = arguments.getString(CommandArguments.PLAYER, sender instanceof Player player ? player.getName() : "");
        if (name.isEmpty()) {
            sender.sendMessage("Use /crate pity show <player> from the console.");
            return true;
        }

        this.plugin.getUserManager().manageUser(name, user -> {
            if (user == null) {
                context.send(su.nightexpress.nightcore.core.config.CoreLang.ERROR_INVALID_PLAYER.message());
                return;
            }
            this.sendCounters(sender, user);
        });
        return true;
    }

    private void sendCounters(@NotNull CommandSender sender, @NotNull CrateUser user) {
        Map<String, Integer> counters = new TreeMap<>(user.getPityMap());
        // Groups a crate uses show up even before the player opened a key on them.
        this.plugin.getPityService().getGroups().forEach(group -> counters.putIfAbsent(group, 0));

        if (counters.isEmpty()) {
            Lang.COMMAND_PITY_SHOW_EMPTY.message().send(sender, replacer -> replacer.replace(Placeholders.PLAYER_NAME, user.getName()));
            return;
        }

        Lang.COMMAND_PITY_SHOW_HEADER.message().send(sender, replacer -> replacer.replace(Placeholders.PLAYER_NAME, user.getName()));
        counters.forEach((group, count) -> {
            int hard = this.plugin.getPityService().getHardCap(group);
            Lang.COMMAND_PITY_SHOW_ENTRY.message().send(sender, replacer -> replacer
                .replace(Placeholders.GENERIC_NAME, group)
                .replace(Placeholders.GENERIC_CURRENT, String.valueOf(count))
                .replace(Placeholders.GENERIC_AMOUNT, String.valueOf(Math.max(0, hard - count)))
                .replace(Placeholders.GENERIC_MAX, hard > 0 ? String.valueOf(hard) : "-")
            );
        });
    }

    private boolean set(@NotNull CommandContext context, @NotNull ParsedArguments arguments) {
        CommandSender sender = context.getSender();
        String name = arguments.getString(CommandArguments.PLAYER);
        String group = PitySettings.normalizeGroup(arguments.getString(GROUP));
        int amount = arguments.getInt(CommandArguments.AMOUNT);

        List<String> groups = this.plugin.getPityService().getGroups();
        if (group.isEmpty() || !groups.contains(group)) {
            Lang.COMMAND_PITY_NO_GROUP.message().send(sender, replacer -> replacer
                .replace(Placeholders.GENERIC_NAME, arguments.getString(GROUP))
                .replace(Placeholders.GENERIC_AVAILABLE, groups.isEmpty() ? "-" : String.join(", ", groups)));
            return true;
        }

        this.plugin.getUserManager().manageUser(name, user -> {
            if (user == null) {
                context.send(su.nightexpress.nightcore.core.config.CoreLang.ERROR_INVALID_PLAYER.message());
                return;
            }
            user.setPity(group, amount);
            this.plugin.getUserManager().save(user);
            Lang.COMMAND_PITY_SET_DONE.message().send(sender, replacer -> replacer
                .replace(Placeholders.GENERIC_NAME, group)
                .replace(Placeholders.PLAYER_NAME, user.getName())
                .replace(Placeholders.GENERIC_AMOUNT, String.valueOf(amount)));
        });
        return true;
    }
}
