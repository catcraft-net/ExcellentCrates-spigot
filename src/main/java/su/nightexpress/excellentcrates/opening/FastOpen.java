package su.nightexpress.excellentcrates.opening;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.CratesPlugin;

/** CatCraft: a per-player "skip opening animations" preference (/crates fast), stored on the player. */
public final class FastOpen {

    private FastOpen() {}

    @NotNull
    private static NamespacedKey key(@NotNull CratesPlugin plugin) {
        return new NamespacedKey(plugin, "fast_open");
    }

    public static boolean isEnabled(@NotNull CratesPlugin plugin, @NotNull Player player) {
        return player.getPersistentDataContainer().getOrDefault(key(plugin), PersistentDataType.BOOLEAN, false);
    }

    /** Flips the preference and returns the new value. */
    public static boolean toggle(@NotNull CratesPlugin plugin, @NotNull Player player) {
        boolean enabled = !isEnabled(plugin, player);
        player.getPersistentDataContainer().set(key(plugin), PersistentDataType.BOOLEAN, enabled);
        return enabled;
    }
}
