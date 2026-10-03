package su.nightexpress.excellentcrates.opening.inventory.reveal;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.opening.inventory.spinner.SpinnerHolder;
import su.nightexpress.nightcore.util.ItemUtil;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CatCraft: animation spinners started when the reward spinners stop, chosen by what was won.
 * Reveals are checked in config order; the first one whose {@code Match} fits any won reward runs.
 * An empty {@code Match} always fits, so put the catch-all reveal last.
 */
public record Reveal(@NotNull String id,
                     @Nullable Boolean broadcast,
                     double maxChance,
                     @Nullable String loreContains,
                     @NotNull Set<String> rewardIds,
                     @NotNull List<SpinnerHolder> spinners) {

    public boolean matches(@NotNull List<Reward> rewards) {
        return rewards.stream().anyMatch(this::matches);
    }

    private boolean matches(@NotNull Reward reward) {
        if (this.broadcast != null && reward.isBroadcast() != this.broadcast) return false;
        if (this.maxChance > 0 && reward.getRollChance() > this.maxChance) return false;
        if (!this.rewardIds.isEmpty() && !this.rewardIds.contains(reward.getId().toLowerCase(Locale.ROOT))) return false;
        if (this.loreContains != null && !this.loreContains.isBlank()) {
            ItemStack preview = reward.getPreviewItem();
            String lore = String.join("\n", ItemUtil.getLoreSerialized(preview)).toUpperCase(Locale.ROOT);
            if (!lore.contains(this.loreContains.toUpperCase(Locale.ROOT))) return false;
        }
        return true;
    }
}
