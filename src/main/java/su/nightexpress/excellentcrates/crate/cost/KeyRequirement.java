package su.nightexpress.excellentcrates.crate.cost;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.crate.cost.entry.impl.KeyCostEntry;
import su.nightexpress.excellentcrates.crate.impl.Crate;

import java.util.HashMap;
import java.util.Map;

/** The strict key policy is independent of the crate's legacy Key.Required setting. */
public final class KeyRequirement {
    private KeyRequirement() {}

    public static boolean isValidCost(Crate crate, @Nullable Cost cost) {
        return cost != null && crate.getCost(cost.getId()) == cost && cost.isEnabled()
            && !cost.hasInvalids() && cost.getEntries().stream().anyMatch(entry ->
                entry instanceof KeyCostEntry key && key.getAmount() > 0 && key.isValid());
    }

    public static boolean hasEnoughKeys(Player player, Cost cost) {
        Map<String, Long> required = new HashMap<>();
        Map<String, KeyCostEntry> entries = new HashMap<>();
        cost.getEntries().forEach(entry -> {
            if (entry instanceof KeyCostEntry key) {
                required.merge(key.getKeyId(), (long) key.getAmount(), Long::sum);
                entries.put(key.getKeyId(), key);
            }
        });
        return !required.isEmpty() && required.entrySet().stream()
            .allMatch(entry -> entries.get(entry.getKey()).countKeys(player) >= entry.getValue());
    }
}
