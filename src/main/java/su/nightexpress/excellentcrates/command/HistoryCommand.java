package su.nightexpress.excellentcrates.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CrateLogger;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.config.Perms;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.key.CrateKey;
import su.nightexpress.nightcore.commands.Arguments;
import su.nightexpress.nightcore.commands.Commands;
import su.nightexpress.nightcore.commands.builder.HubNodeBuilder;
import su.nightexpress.nightcore.commands.context.CommandContext;
import su.nightexpress.nightcore.commands.context.ParsedArguments;
import su.nightexpress.nightcore.util.text.NightMessage;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CatCraft: /crate history <player> [crate|all] [page] shows a player's crate openings (from openings.log) and key
 * gives/takes (from keys.log) as one readable timeline, newest first. Admin only (excellentcrates.command.history,
 * not part of the excellentcrates.command.* wildcard). Files are read off the main thread.
 */
public class HistoryCommand {

    private static final String PLAYER = "player";
    private static final String FILTER = "filter";
    private static final String PAGE   = "page";

    private static final int PER_PAGE        = 10;
    private static final int SHOWN_REWARDS   = 3;
    private static final long SESSION_GAP_S  = 60;

    // [date] <player> won <reward> (ID: <id>, Weight: <w>) from <crate> (ID: <crateId>)
    private static final Pattern OPENING = Pattern.compile("^\\[(.+?)] (\\S+) won (.*) \\(ID: ([^,]+), Weight: [^)]*\\) from (.*) \\(ID: ([^)]+)\\)$");

    private enum Kind { OPEN, KEY }

    private record Win(LocalDateTime time, String name) {}

    private static final class Entry {
        final Kind kind;
        final LocalDateTime time;
        LocalDateTime last;
        final String crateId;
        final String crateName;
        final List<Win> wins = new ArrayList<>();
        final CrateLogger.KeyRecord key;
        int order; // position in the logs, so same-second entries keep their real order

        Entry(Kind kind, LocalDateTime time, String crateId, String crateName, CrateLogger.KeyRecord key) {
            this.kind = kind;
            this.time = time;
            this.last = time;
            this.crateId = crateId;
            this.crateName = crateName;
            this.key = key;
        }
    }

    private final CratesPlugin plugin;

    public HistoryCommand(@NotNull CratesPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(@NotNull HubNodeBuilder nodeBuilder) {
        nodeBuilder.branch(Commands.literal("history")
            .description("Show a player's crate openings and key gives (admin).")
            .permission(Perms.COMMAND_HISTORY)
            .withArguments(
                Arguments.playerName(PLAYER),
                Commands.argument(FILTER, (context, string) -> string).suggestions((reader, context) -> {
                    List<String> ids = new ArrayList<>(plugin.getCrateManager().getCrateIds());
                    Collections.sort(ids);
                    ids.add(0, "all");
                    return ids;
                }).optional(),
                Commands.argument(PAGE, (context, string) -> string).suggestions((reader, context) -> List.of("1", "2", "3")).optional()
            )
            .executes(this::execute)
        );
    }

    private boolean execute(@NotNull CommandContext context, @NotNull ParsedArguments arguments) {
        CommandSender sender = context.getSender();
        String typedName = arguments.getString(PLAYER);
        String filter = arguments.getString(FILTER, "all");
        String pageArg = arguments.getString(PAGE, "1");

        // "/crate history name 2" means page 2 of everything.
        if (filter.matches("\\d+")) {
            pageArg = filter;
            filter = "all";
        }
        String crateFilter = filter.equalsIgnoreCase("all") ? null : filter.toLowerCase(Locale.ROOT);
        int page = Math.max(1, parseInt(pageArg, 1));

        plugin.getUserManager().manageUser(typedName, user -> {
            String name = user == null ? typedName : user.getName();
            plugin.runTaskAsync(() -> {
                List<Entry> entries;
                try {
                    entries = this.collect(name, crateFilter);
                }
                catch (IOException exception) {
                    exception.printStackTrace();
                    plugin.runTask(() -> sender.sendMessage(Component.text("Could not read the crate logs: " + exception.getMessage(), NamedTextColor.RED)));
                    return;
                }
                List<Component> lines = this.render(name, crateFilter, entries, page);
                plugin.runTask(() -> lines.forEach(sender::sendMessage));
            });
        });
        return true;
    }

    // ---------------------------------------------------------------- reading

    @NotNull
    private List<Entry> collect(@NotNull String name, @Nullable String crateFilter) throws IOException {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(Config.LOGS_DATE_FORMAT.get());
        List<Entry> entries = new ArrayList<>();

        File openings = new File(plugin.getDataFolder(), Config.FILE_LOGS);
        if (openings.isFile()) {
            String needle = "] " + name + " won ";
            Entry current = null;
            try (BufferedReader reader = Files.newBufferedReader(openings.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int close = line.indexOf("] ");
                    if (close < 0 || !line.regionMatches(true, close, needle, 0, needle.length())) continue;

                    Matcher matcher = OPENING.matcher(line);
                    if (!matcher.matches()) continue;

                    String crateId = matcher.group(6);
                    if (crateFilter != null && !crateId.equalsIgnoreCase(crateFilter)) continue;

                    LocalDateTime time = parseTime(matcher.group(1), formatter);
                    if (time == null) continue;

                    String rewardName = this.rewardName(crateId, matcher.group(4), matcher.group(3));
                    boolean sameSession = current != null && current.crateId.equalsIgnoreCase(crateId)
                        && !time.isBefore(current.last) && Duration.between(current.last, time).getSeconds() <= SESSION_GAP_S;
                    if (!sameSession) {
                        current = new Entry(Kind.OPEN, time, crateId, this.crateName(crateId, matcher.group(5)), null);
                        entries.add(current);
                    }
                    current.wins.add(new Win(time, rewardName));
                    current.last = time;
                }
            }
        }

        for (CrateLogger.KeyRecord record : plugin.getCrateLogger().readKeyRecords(name)) {
            if (crateFilter != null && !this.keyMatchesCrate(record.keyId(), crateFilter)) continue;
            LocalDateTime time = parseTime(record.date(), formatter);
            if (time == null) continue;
            entries.add(new Entry(Kind.KEY, time, null, null, record));
        }

        for (int i = 0; i < entries.size(); i++) entries.get(i).order = i;
        entries.sort(Comparator.comparing((Entry entry) -> entry.time).thenComparingInt(entry -> entry.order).reversed());
        return entries;
    }

    private boolean keyMatchesCrate(@NotNull String keyId, @NotNull String crateId) {
        if (keyId.equalsIgnoreCase(crateId)) return true;
        Crate crate = plugin.getCrateManager().getCrateById(crateId);
        if (crate == null) return false;
        for (su.nightexpress.excellentcrates.crate.cost.Cost cost : crate.getCosts()) {
            for (Object entry : cost.getEntries()) {
                if (entry instanceof su.nightexpress.excellentcrates.crate.cost.entry.impl.KeyCostEntry keyEntry
                    && keyEntry.getKeyId().equalsIgnoreCase(keyId)) return true;
            }
        }
        return false;
    }

    @NotNull
    private String crateName(@NotNull String crateId, @NotNull String logged) {
        String name = logged.trim();
        if (!name.isEmpty()) return name;
        Crate crate = plugin.getCrateManager().getCrateById(crateId);
        if (crate != null) {
            String live = NightMessage.stripTags(crate.getName()).trim();
            if (!live.isEmpty()) return live;
        }
        return crateId;
    }

    @NotNull
    private String rewardName(@NotNull String crateId, @NotNull String rewardId, @NotNull String logged) {
        String name = logged.trim();
        if (!name.isEmpty()) return name;

        Crate crate = plugin.getCrateManager().getCrateById(crateId);
        Reward reward = crate == null ? null : crate.getReward(rewardId);
        if (reward != null) {
            String live = NightMessage.stripTags(reward.getName()).trim();
            if (!live.isEmpty()) return live;
            try {
                return pretty(reward.getPreviewItem().getType().name());
            }
            catch (Exception ignored) {
                // Preview item can fail to build for broken custom items; fall through.
            }
        }
        // Vanilla rewards are logged with their material as the ID (oak_log); UUID IDs have nothing readable.
        if (rewardId.matches("[a-z0-9_]+")) return pretty(rewardId);
        return "unnamed reward";
    }

    // ---------------------------------------------------------------- output

    @NotNull
    private List<Component> render(@NotNull String name, @Nullable String crateFilter, @NotNull List<Entry> entries, int page) {
        List<Component> lines = new ArrayList<>();
        String zone = TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT);
        String scope = crateFilter == null ? "" : " (" + crateFilter + ")";

        long openings = entries.stream().filter(e -> e.kind == Kind.OPEN).mapToLong(e -> e.wins.size()).sum();
        long keyRecords = entries.stream().filter(e -> e.kind == Kind.KEY).count();

        lines.add(Component.text("Crate history: ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
            .append(Component.text(name + scope, NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false)));

        if (entries.isEmpty()) {
            lines.add(Component.text("No openings or key gives found for this player.", NamedTextColor.GRAY));
            lines.add(Component.text("Names must match the name at the time (renamed players show under their old name).", NamedTextColor.DARK_GRAY));
            return lines;
        }

        int pages = (entries.size() + PER_PAGE - 1) / PER_PAGE;
        page = Math.min(page, pages);

        lines.add(Component.text(openings + " rewards opened, " + keyRecords + " key changes. Newest first, times " + zone + ".", NamedTextColor.GRAY));

        int now = LocalDateTime.now().getYear();
        List<Entry> slice = entries.subList((page - 1) * PER_PAGE, Math.min(entries.size(), page * PER_PAGE));
        for (Entry entry : slice) {
            lines.add(entry.kind == Kind.OPEN ? this.openLine(entry, now) : this.keyLine(entry, now));
        }

        if (pages > 1) lines.add(this.pager(name, crateFilter, page, pages));
        lines.add(Component.text("Key gives are logged from the catcraft.14 update on; hover openings for every reward.", NamedTextColor.DARK_GRAY));
        return lines;
    }

    @NotNull
    private Component openLine(@NotNull Entry entry, int year) {
        // Count rewards by name, keeping the order they were won in.
        Map<String, Integer> counts = new LinkedHashMap<>();
        entry.wins.forEach(win -> counts.merge(win.name(), 1, Integer::sum));

        List<String> parts = new ArrayList<>();
        counts.forEach((reward, count) -> parts.add(count > 1 ? reward + " x" + count : reward));
        String shown = String.join(", ", parts.subList(0, Math.min(SHOWN_REWARDS, parts.size())));
        int hidden = parts.size() - Math.min(SHOWN_REWARDS, parts.size());

        TextComponent.Builder hover = Component.text();
        hover.append(Component.text(entry.crateName + " (" + entry.crateId + ")", NamedTextColor.GOLD));
        DateTimeFormatter clock = DateTimeFormatter.ofPattern("HH:mm:ss");
        for (Win win : entry.wins) {
            hover.append(Component.newline())
                .append(Component.text(win.time().format(clock) + "  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(win.name(), NamedTextColor.WHITE));
        }

        return Component.text()
            .append(Component.text(stamp(entry.time, year) + "  ", NamedTextColor.DARK_GRAY))
            .append(Component.text("Opened " + entry.wins.size() + "x ", NamedTextColor.WHITE))
            .append(Component.text(entry.crateName, NamedTextColor.GOLD))
            .append(Component.text(": " + shown, NamedTextColor.GRAY))
            .append(hidden > 0 ? Component.text(" +" + hidden + " more", NamedTextColor.YELLOW) : Component.empty())
            .hoverEvent(HoverEvent.showText(hover.build()))
            .build();
    }

    @NotNull
    private Component keyLine(@NotNull Entry entry, int year) {
        CrateLogger.KeyRecord record = entry.key;
        CrateKey key = plugin.getKeyManager().getKeyById(record.keyId());
        String keyName = key == null ? record.keyId() : NightMessage.stripTags(key.getName()).trim();
        if (keyName.isEmpty()) keyName = record.keyId();

        String action;
        NamedTextColor color;
        switch (record.action()) {
            case "TAKE" -> { action = "Lost " + record.amount() + "x "; color = NamedTextColor.RED; }
            case "SET" -> { action = "Set to " + record.amount() + "x "; color = NamedTextColor.YELLOW; }
            default -> { action = "Got " + record.amount() + "x "; color = NamedTextColor.GREEN; }
        }
        String by = record.by().equalsIgnoreCase("CONSOLE") ? "console (store/plugin)" : record.by();
        String balance = record.balance() == null ? "" : ", now " + record.balance();

        return Component.text()
            .append(Component.text(stamp(entry.time, year) + "  ", NamedTextColor.DARK_GRAY))
            .append(Component.text(action, color))
            .append(Component.text(keyName, NamedTextColor.GOLD))
            .append(Component.text(" from " + by + balance, NamedTextColor.GRAY))
            .hoverEvent(HoverEvent.showText(Component.text(record.action() + " " + record.amount() + " " + record.keyId() + " by " + record.by(), NamedTextColor.GRAY)))
            .build();
    }

    @NotNull
    private Component pager(@NotNull String name, @Nullable String crateFilter, int page, int pages) {
        String base = "/excellentcrates:crate history " + name + " " + (crateFilter == null ? "all" : crateFilter) + " ";
        Component prev = page > 1
            ? Component.text("« Newer", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand(base + (page - 1))).hoverEvent(HoverEvent.showText(Component.text("Page " + (page - 1))))
            : Component.text("« Newer", NamedTextColor.DARK_GRAY);
        Component next = page < pages
            ? Component.text("Older »", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand(base + (page + 1))).hoverEvent(HoverEvent.showText(Component.text("Page " + (page + 1))))
            : Component.text("Older »", NamedTextColor.DARK_GRAY);
        return Component.text()
            .append(prev)
            .append(Component.text("   Page " + page + "/" + pages + "   ", NamedTextColor.GRAY))
            .append(next)
            .build();
    }

    // ---------------------------------------------------------------- helpers

    @NotNull
    private static String stamp(@NotNull LocalDateTime time, int currentYear) {
        String pattern = time.getYear() == currentYear ? "dd MMM HH:mm" : "dd MMM yyyy HH:mm";
        return time.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH));
    }

    @Nullable
    private static LocalDateTime parseTime(@NotNull String text, @NotNull DateTimeFormatter formatter) {
        try {
            return LocalDateTime.parse(text, formatter);
        }
        catch (DateTimeParseException exception) {
            return null;
        }
    }

    @NotNull
    private static String pretty(@NotNull String id) {
        StringBuilder builder = new StringBuilder();
        for (String word : id.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }

    private static int parseInt(@NotNull String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        }
        catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
