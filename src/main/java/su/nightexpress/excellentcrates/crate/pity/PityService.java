package su.nightexpress.excellentcrates.crate.pity;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.Placeholders;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.config.Lang;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.reward.RewardRoll;
import su.nightexpress.excellentcrates.user.CrateUser;
import su.nightexpress.nightcore.util.placeholder.Replacer;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * CatCraft: the pity system glue between the plain rules ({@link PityRoll}), the crate settings
 * ({@link PitySettings}) and the player's stored counters ({@link CrateUser#getPityMap()}).
 */
public class PityService {

    private final CratesPlugin plugin;

    public PityService(@NotNull CratesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Decides the prize for one key. Crates without a pity group just run {@code normalRoll} and leave
     * every counter alone. Otherwise the counter of the crate's group is read, the pity rules run and the
     * counter is written back (0 after any Ultra Legendary, +1 otherwise) and saved through the user
     * manager (same scheduled save as keys).
     */
    @NotNull
    public Reward roll(@NotNull Player player, @NotNull Crate crate, @NotNull Supplier<Reward> normalRoll) {
        PitySettings settings = crate.getPity();
        if (!settings.isEnabled()) return normalRoll.get();

        CrateUser user = this.plugin.getUserManager().getOrFetch(player);
        int count = user.getPity(settings.group());

        List<Reward> ultras = new ArrayList<>(crate.getUltraLegendaries());
        ultras.removeIf(reward -> !reward.canWin(player));

        PityRoll.Result<Reward> result = PityRoll.roll(settings, count, ultras, Reward::getWeight, Reward::isUltraLegendary,
            normalRoll, RewardRoll.random());

        user.setPity(settings.group(), result.newCount());
        this.plugin.getUserManager().save(user);

        if (result.source() != PityRoll.Source.NORMAL) {
            this.plugin.info("Pity " + result.source().name().toLowerCase().replace("pity_", "") + " hit for " + player.getName()
                + " on " + crate.getId() + " (group " + settings.group() + ", key " + (count + 1) + "): " + result.reward().getId());
        }
        return result.reward();
    }

    public int getCount(@NotNull Player player, @NotNull Crate crate) {
        PitySettings settings = crate.getPity();
        if (!settings.isEnabled()) return 0;
        return this.plugin.getUserManager().getOrFetch(player).getPity(settings.group());
    }

    /** Keys until the guarantee for this player on this crate (1 = the next key is guaranteed, see {@link PitySettings#keysLeft}). */
    public int getKeysLeft(@NotNull Player player, @NotNull Crate crate) {
        PitySettings settings = crate.getPity();
        if (!settings.isEnabled()) return 0;
        return settings.keysLeft(this.getCount(player, crate));
    }

    /** The second hologram line for {@code keysLeft}: "guaranteed on your next key" once the next key is guaranteed. */
    @NotNull
    public static String secondLine(int keysLeft) {
        return PitySettings.isNextKey(keysLeft) ? Lang.PITY_HOLOGRAM_LINE2_NEXT.text() : Lang.PITY_HOLOGRAM_LINE2.text();
    }

    /**
     * Per-player replacements for the {@code %crate_pity_*%} placeholders (used in the crate hologram, the preview
     * menu's own items and anywhere a crate placeholder is applied for a player). {@code %crate_pity_line1%} and
     * {@code %crate_pity_line2%} are the lang file's hologram lines resolved for the viewer; {@code %crate_pity_lore%}
     * is both joined with a newline.
     */
    @NotNull
    public UnaryOperator<String> placeholders(@NotNull Player player, @NotNull Crate crate) {
        PitySettings settings = crate.getPity();
        int count = this.getCount(player, crate);
        int left = settings.keysLeft(count);

        Replacer replacer = Replacer.create()
            .replace(Placeholders.CRATE_PITY_COUNT, () -> String.valueOf(count))
            .replace(Placeholders.CRATE_PITY_LEFT, () -> String.valueOf(left))
            .replace(Placeholders.CRATE_PITY_HARD, () -> String.valueOf(settings.hard()))
            .replace(Placeholders.CRATE_PITY_GROUP, () -> settings.isEnabled() ? settings.group() : PitySettings.NONE);

        return text -> {
            if (text.contains(Placeholders.CRATE_PITY_LINE1) || text.contains(Placeholders.CRATE_PITY_LINE2) || text.contains(Placeholders.CRATE_PITY_LORE)) {
                String line1 = settings.isEnabled() ? Lang.PITY_HOLOGRAM_LINE1.text() : "";
                String line2 = settings.isEnabled() ? secondLine(left) : "";
                text = text.replace(Placeholders.CRATE_PITY_LORE, line1.isEmpty() ? "" : line1 + "\n" + line2)
                    .replace(Placeholders.CRATE_PITY_LINE1, line1)
                    .replace(Placeholders.CRATE_PITY_LINE2, line2);
            }
            return replacer.apply(text);
        };
    }

    /** Every pity group used by a loaded crate, sorted. */
    @NotNull
    public List<String> getGroups() {
        TreeSet<String> groups = new TreeSet<>();
        this.plugin.getCrateManager().getCrates().forEach(crate -> {
            if (crate.getPity().isEnabled()) groups.add(crate.getPity().group());
        });
        return new ArrayList<>(groups);
    }

    /** The largest Hard cap among the crates of a group (for the command's "guaranteed in" column). */
    public int getHardCap(@NotNull String group) {
        int hard = 0;
        for (Crate crate : this.plugin.getCrateManager().getCrates()) {
            if (crate.getPity().isEnabled() && crate.getPity().group().equals(group)) hard = Math.max(hard, crate.getPity().hard());
        }
        return hard;
    }
}
