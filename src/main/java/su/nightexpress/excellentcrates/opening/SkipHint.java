package su.nightexpress.excellentcrates.opening;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.config.Lang;
import su.nightexpress.excellentcrates.config.Perms;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CatCraft: players who keep skipping opening animations get a one-off tip to use /crates fast
 * (a title pop-up plus a clickable chat line), at most once per cooldown.
 */
public final class SkipHint {

    private static final class Skips {
        long windowStart;
        int  count;
        long lastHint;
    }

    private static final Map<UUID, Skips> SKIPS = new ConcurrentHashMap<>();

    private SkipHint() {}

    /** Call when the player skipped an opening themselves (closed or clicked the window). */
    public static void recordSkip(@NotNull CratesPlugin plugin, @NotNull Player player) {
        if (!Config.SKIP_HINT_ENABLED.get()) return;
        if (!player.hasPermission(Perms.COMMAND_FAST.getName()) || FastOpen.isEnabled(plugin, player)) return;

        long now = System.currentTimeMillis();
        long window = Config.SKIP_HINT_WITHIN_MINUTES.get() * 60_000L;
        long cooldown = Config.SKIP_HINT_COOLDOWN_MINUTES.get() * 60_000L;

        Skips skips = SKIPS.computeIfAbsent(player.getUniqueId(), id -> new Skips());
        if (now - skips.windowStart > window) {
            skips.windowStart = now;
            skips.count = 0;
        }
        skips.count++;
        if (skips.count < Config.SKIP_HINT_AFTER_SKIPS.get() || now - skips.lastHint < cooldown) return;

        skips.lastHint = now;
        skips.count = 0;
        // Next tick, after the opening window has closed.
        plugin.runTask(() -> {
            if (!player.isOnline()) return;
            Lang.CRATE_SKIP_HINT_TITLE.message().send(player);
            Lang.CRATE_SKIP_HINT_CHAT.message().send(player);
        });
    }

    public static void forget(@NotNull UUID playerId) {
        SKIPS.remove(playerId);
    }
}
