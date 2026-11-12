package su.nightexpress.excellentcrates;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.nightcore.util.text.NightMessage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class CrateLogger {

    private final CratesPlugin plugin;
    private final DateTimeFormatter formatter;

    public CrateLogger(@NotNull CratesPlugin plugin) {
        this.plugin = plugin;
        this.formatter = DateTimeFormatter.ofPattern(Config.LOGS_DATE_FORMAT.get());
    }

    public void logReward(@NotNull Player player, @NotNull Reward reward) {
        Crate crate = reward.getCrate();

        String text = player.getName() + " won " + reward.getName() +
            " (ID: " + reward.getId() + ", Weight: " + reward.getWeight() + ")" +
            " from " + crate.getName() + " (ID: " + crate.getId() + ")";

        this.log(text);
    }

    // CatCraft: keys.log records every /crate key give|set|take|giveall, so staff can see when (and whether) a store
    // purchase or a manual give actually reached a player. Read back by /crate history.
    public static final String FILE_KEY_LOGS = "keys.log";

    // [date] GIVE 6 eldritch2026 to _jazzie_ by CONSOLE (balance 6)
    private static final Pattern KEY_LINE = Pattern.compile("^\\[(.+?)] (GIVE|TAKE|SET) (\\d+) (\\S+) (?:to|from|for) (\\S+) by (\\S+)(?: \\(balance (\\d+)\\))?$");

    public record KeyRecord(String date, String action, int amount, String keyId, String player, String by, Integer balance) {}

    public synchronized void logKey(@NotNull String action, @NotNull String player, @NotNull String keyId, int amount, @NotNull String by, @org.jetbrains.annotations.Nullable Integer balance) {
        String preposition = switch (action) { case "TAKE" -> "from"; case "SET" -> "for"; default -> "to"; };
        String text = action + " " + amount + " " + keyId + " " + preposition + " " + player + " by " + by.replace(' ', '_') +
            (balance == null ? "" : " (balance " + balance + ")");
        String date = LocalDateTime.now().format(this.formatter);
        try (BufferedWriter output = new BufferedWriter(new FileWriter(new File(this.plugin.getDataFolder(), FILE_KEY_LOGS), StandardCharsets.UTF_8, true))) {
            output.append("[").append(date).append("] ").append(text);
            output.newLine();
        }
        catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    @NotNull
    public List<KeyRecord> readKeyRecords(@NotNull String player) throws IOException {
        List<KeyRecord> records = new ArrayList<>();
        File file = new File(this.plugin.getDataFolder(), FILE_KEY_LOGS);
        if (!file.isFile()) return records;

        String needle = " " + player + " by ";
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.toLowerCase().contains(needle.toLowerCase())) continue;
                Matcher matcher = KEY_LINE.matcher(line);
                if (!matcher.matches() || !matcher.group(5).equalsIgnoreCase(player)) continue;
                records.add(new KeyRecord(matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)), matcher.group(4),
                    matcher.group(5), matcher.group(6), matcher.group(7) == null ? null : Integer.parseInt(matcher.group(7))));
            }
        }
        return records;
    }

    private void log(@NotNull String text) {
        if (!Config.LOGS_TO_CONSOLE.get() && !Config.LOGS_TO_FILE.get()) return;

        text = NightMessage.stripTags(text);

        if (Config.LOGS_TO_CONSOLE.get()) {
            this.plugin.info(text);
        }
        if (Config.LOGS_TO_FILE.get()) {
            String date = LocalDateTime.now().format(this.formatter);
            String path = this.plugin.getDataFolder() + "/" + Config.FILE_LOGS;
            BufferedWriter output;
            try {
                output = new BufferedWriter(new FileWriter(path, true));
                output.append("[").append(date).append("] ").append(text);
                output.newLine();
                output.close();
            }
            catch (IOException exception) {
                exception.printStackTrace();
            }
        }
    }
}
