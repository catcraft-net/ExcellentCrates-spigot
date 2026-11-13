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
            .branch(Commands.literal("backfill")
                .description("Seeds every pity counter from this server's openings.log (keys since the last Ultra Legendary).")
                .permission(Perms.COMMAND_PITY)
                .executes(this::backfill)
            )
        );
    }

    /**
     * One-off seeding after pity goes live: replays this server's own openings.log (the full history) and sets each
     * player's counter per group to the keys they opened on that group's crates since their last Ultra Legendary,
     * capped at Hard - 1 so a player already past the cap gets the guarantee on their next key. Crates without a
     * pity group and rewards no longer in a crate file are skipped (an unknown reward counts as a dry key).
     * Overwrites every counter it computes, so run it once, right after the restart that enabled pity.
     */
    private boolean backfill(@NotNull CommandContext context, @NotNull ParsedArguments arguments) {
        CommandSender sender = context.getSender();
        java.io.File log = new java.io.File(this.plugin.getDataFolder(), su.nightexpress.excellentcrates.config.Config.FILE_LOGS);
        if (!log.isFile()) {
            sender.sendMessage("No " + log.getName() + " to read.");
            return true;
        }
        java.util.Map<String, su.nightexpress.excellentcrates.crate.impl.Crate> crates = new java.util.HashMap<>();
        this.plugin.getCrateManager().getCrates().forEach(crate -> {
            if (crate.getPity().isEnabled()) crates.put(crate.getId().toLowerCase(), crate);
        });
        if (crates.isEmpty()) {
            sender.sendMessage("No crate has a pity group.");
            return true;
        }
        sender.sendMessage("Reading " + log.getName() + " for " + crates.size() + " pity crates...");

        this.plugin.runTaskAsync(() -> {
            java.util.regex.Pattern line = java.util.regex.Pattern.compile("^\\[[^\\]]+] (\\S+) won .*\\(ID: ([^,)]+), Weight: [^)]*\\) from .* \\(ID: ([^)]+)\\)\\s*$");
            // player -> group -> keys since the last UL
            java.util.Map<String, java.util.Map<String, Integer>> counters = new java.util.HashMap<>();
            java.util.Map<String, Integer> hardCaps = new java.util.HashMap<>();
            long keys = 0;
            try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(log.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String text;
                while ((text = reader.readLine()) != null) {
                    java.util.regex.Matcher m = line.matcher(text);
                    if (!m.matches()) continue;
                    su.nightexpress.excellentcrates.crate.impl.Crate crate = crates.get(m.group(3).toLowerCase());
                    if (crate == null) continue;
                    String group = crate.getPity().group();
                    hardCaps.merge(group, crate.getPity().hard(), Math::max);
                    su.nightexpress.excellentcrates.api.crate.Reward reward = crate.getReward(m.group(2));
                    boolean ultra = reward != null && reward.isUltraLegendary();
                    java.util.Map<String, Integer> byGroup = counters.computeIfAbsent(m.group(1), k -> new java.util.HashMap<>());
                    byGroup.merge(group, ultra ? 0 : 1, (old, add) -> ultra ? 0 : old + add);
                    keys++;
                }
            }
            catch (java.io.IOException exception) {
                this.plugin.runTask(() -> sender.sendMessage("Could not read " + log.getName() + ": " + exception.getMessage()));
                return;
            }
            final long total = keys;
            java.util.List<java.util.Map.Entry<String, java.util.Map<String, Integer>>> todo = counters.entrySet().stream()
                .filter(entry -> entry.getValue().values().stream().anyMatch(count -> count > 0)).toList();
            this.plugin.runTask(() -> {
                sender.sendMessage("Read " + total + " pity-crate keys for " + counters.size() + " players; setting counters for " + todo.size() + " of them (the rest are at 0)...");
                java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicInteger missing = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicInteger capped = new java.util.concurrent.atomic.AtomicInteger();
                for (java.util.Map.Entry<String, java.util.Map<String, Integer>> entry : todo) {
                    this.plugin.getUserManager().manageUser(entry.getKey(), user -> {
                        if (user == null) {
                            missing.incrementAndGet();
                        }
                        else {
                            entry.getValue().forEach((group, count) -> {
                                int cap = Math.max(0, hardCaps.getOrDefault(group, 150) - 1);
                                if (count > cap) capped.incrementAndGet();
                                user.setPity(group, Math.min(count, cap));
                            });
                            this.plugin.getUserManager().save(user);
                        }
                        if (done.incrementAndGet() == todo.size()) {
                            sender.sendMessage("Pity backfill done: " + (todo.size() - missing.get()) + " players set, " + missing.get()
                                + " names unknown to the plugin, " + capped.get() + " counters capped to Hard - 1 (next key guaranteed).");
                        }
                    });
                }
                if (todo.isEmpty()) sender.sendMessage("Pity backfill done: nothing to set.");
            });
        });
        return true;
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
            int left = Math.max(0, hard - count);
            // Same rule as the hologram: never "within 1 keys"; at 149 of 150 the next key is the guaranteed one.
            String promise = (hard <= 0 ? Lang.COMMAND_PITY_SHOW_UNUSED.text()
                : PitySettings.isNextKey(left) ? Lang.COMMAND_PITY_SHOW_NEXT.text() : Lang.COMMAND_PITY_SHOW_LEFT.text())
                .replace(Placeholders.GENERIC_AMOUNT, String.valueOf(left))
                .replace(Placeholders.GENERIC_MAX, String.valueOf(hard));
            Lang.COMMAND_PITY_SHOW_ENTRY.message().send(sender, replacer -> replacer
                .replace(Placeholders.GENERIC_NAME, group)
                .replace(Placeholders.GENERIC_CURRENT, String.valueOf(count))
                .replace(Placeholders.GENERIC_AMOUNT, promise)
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
