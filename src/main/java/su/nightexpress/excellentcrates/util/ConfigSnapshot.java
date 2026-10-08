package su.nightexpress.excellentcrates.util;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * CatCraft: the plain values of a config (sections left out), to tell whether loading a file really changed it.
 * Loading a crate or key reads its options through helpers that write them back even when nothing changed, which
 * made every /crates reload re-serialise and rewrite every crate and key file on the main thread.
 */
public final class ConfigSnapshot {

    private ConfigSnapshot() {}

    @NotNull
    public static Map<String, Object> of(@NotNull ConfigurationSection config) {
        Map<String, Object> values = new HashMap<>();
        for (String key : config.getKeys(true)) {
            Object value = config.get(key);
            if (!(value instanceof ConfigurationSection)) values.put(key, value);
        }
        return values;
    }
}
