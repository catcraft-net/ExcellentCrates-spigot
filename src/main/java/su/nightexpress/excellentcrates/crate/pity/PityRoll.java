package su.nightexpress.excellentcrates.crate.pity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.crate.reward.RewardRoll;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * CatCraft: the pity rules as plain methods with no Bukkit types, so the plugin ({@link PityService})
 * and the offline replay harness run the identical code.
 * <p>
 * Rules (set by the server owner):
 * <ul>
 *   <li>One counter per player per pity group: keys opened since the last Ultra Legendary (UL).</li>
 *   <li>For key number c = counter + 1: if c >= Hard the reward is a UL picked by weight among the crate's
 *       UL rewards (duplicates allowed). Else if c >= Soft, with chance Step * (c - Soft + 1) pick a UL the
 *       same way; if that misses, the normal roll runs (and may still give a UL). Else the normal roll.</li>
 *   <li>Any UL (natural or pity) resets the counter to 0; any other reward adds 1.</li>
 * </ul>
 */
public final class PityRoll {

    private PityRoll() {}

    /** How a key's reward was decided. */
    public enum Source { NORMAL, PITY_SOFT, PITY_HARD }

    /** The outcome of one key: the reward, how it was picked and the player's new counter. */
    public record Result<T>(@NotNull T reward, @NotNull Source source, int newCount, boolean ultra) {}

    /**
     * Decides one key for a player whose counter is {@code count}.
     *
     * @param settings   the crate's pity settings (must be enabled)
     * @param count      keys opened since the player's last UL
     * @param ultras     the crate's UL rewards the player can win (may be empty: then pity can't fire)
     * @param weight     a reward's weight
     * @param isUltra    whether a reward counts as a UL (resets the counter)
     * @param normalRoll the crate's normal weighted roll
     * @param random     random source (the plugin's, or a seeded one in the harness)
     */
    @NotNull
    public static <T> Result<T> roll(@NotNull PitySettings settings,
                                     int count,
                                     @NotNull Collection<T> ultras,
                                     @NotNull ToDoubleFunction<T> weight,
                                     @NotNull java.util.function.Predicate<T> isUltra,
                                     @NotNull Supplier<T> normalRoll,
                                     @NotNull RandomGenerator random) {
        int keyNumber = count + 1;
        T reward = null;
        Source source = Source.NORMAL;

        if (!ultras.isEmpty()) {
            double chance = settings.pityChance(keyNumber);
            if (chance >= 1D) {
                reward = RewardRoll.weighted(ultras, weight, random);
                source = Source.PITY_HARD;
            }
            else if (chance > 0D && random.nextDouble() < chance) {
                reward = RewardRoll.weighted(ultras, weight, random);
                source = Source.PITY_SOFT;
            }
        }
        if (reward == null) reward = normalRoll.get();

        boolean ultra = isUltra.test(reward);
        return new Result<>(reward, source, ultra ? 0 : keyNumber, ultra);
    }

    // ------------------------------------------------------------------------------------------------
    // Ultra Legendary detection from a reward's description (lore)
    // ------------------------------------------------------------------------------------------------

    /** True when any description line says "ULTRA-LEGENDARY" or "ULTRA LEGENDARY" once formatting is removed. */
    public static boolean isUltraDescription(@Nullable List<String> description) {
        if (description == null) return false;
        for (String line : description) {
            if (isUltraText(line)) return true;
        }
        return false;
    }

    public static boolean isUltraText(@Nullable String line) {
        if (line == null) return false;
        String plain = stripFormatting(line).toUpperCase(Locale.ROOT);
        return plain.contains("ULTRA-LEGENDARY") || plain.contains("ULTRA LEGENDARY");
    }

    /**
     * Removes nightcore/MiniMessage style tags ({@code <gray>}, {@code <#6A2FC4>}, {@code </b>}),
     * legacy colour codes ({@code &c}, {@code §l}, {@code §x§6§a§2§f§c§4}) and bare hex codes
     * ({@code #6A2FC4}), so per-letter gradients become one plain word.
     */
    @NotNull
    public static String stripFormatting(@NotNull String text) {
        String out = text.replaceAll("<[^<>]*>", "");
        out = out.replaceAll("(?i)[§&]x(?:[§&][0-9a-f]){6}", "");
        out = out.replaceAll("(?i)[§&]#[0-9a-f]{6}", "");
        out = out.replaceAll("(?i)[§&][0-9a-fk-or]", "");
        out = out.replaceAll("(?i)#[0-9a-f]{6}", "");
        return out;
    }
}
