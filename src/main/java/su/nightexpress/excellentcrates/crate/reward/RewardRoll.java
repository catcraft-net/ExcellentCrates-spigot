package su.nightexpress.excellentcrates.crate.reward;

import org.jetbrains.annotations.NotNull;
import su.nightexpress.nightcore.util.Randomizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * CatCraft: the weighted reward roll as a plain method, so the plugin and the offline replay harness
 * ({@code src/test/java/.../pity/PityReplay}) run the very same selection code.
 * <p>
 * {@link #weighted} is nightcore's {@code Rnd.getByWeight} algorithm (sum the weights, draw a number in
 * {@code [0, sum)}, walk the list subtracting weights until it drops to or below zero) with the random
 * source as a parameter. With {@link #random()} it draws from the same generator the plugin always used
 * ({@code Randomizer}), so the distribution of every roll is unchanged. The harness passes a seeded
 * generator instead.
 */
public final class RewardRoll {

    private RewardRoll() {}

    /** The plugin's own random source (what nightcore's {@code Rnd.getByWeight} draws from). */
    @NotNull
    public static RandomGenerator random() {
        return Randomizer.getSource();
    }

    /**
     * Picks one item by weight. Same algorithm as {@code Rnd.getByWeight(Map)}.
     *
     * @throws IllegalStateException when nothing is picked (empty list, or all weights zero)
     */
    @NotNull
    public static <T> T weighted(@NotNull Collection<T> items, @NotNull ToDoubleFunction<T> weight, @NotNull RandomGenerator random) {
        double totalWeight = 0D;
        for (T item : items) totalWeight += weight.applyAsDouble(item);

        double randomValue = random.nextDouble(totalWeight);
        for (T item : items) {
            randomValue -= weight.applyAsDouble(item);
            if (randomValue <= 0D) return item;
        }
        throw new IllegalStateException("No element found");
    }

    /**
     * The normal crate roll: first a rarity by rarity weight (among the rarities present in the list, each
     * counted once), then a reward by its own weight among the rewards of that rarity. This is exactly
     * what {@code Crate#rollReward(player, null)} did before; it now calls this.
     */
    @NotNull
    public static <T, R> T rarityThenWeight(@NotNull Collection<T> rewards,
                                            @NotNull Function<T, R> rarity,
                                            @NotNull ToDoubleFunction<R> rarityWeight,
                                            @NotNull ToDoubleFunction<T> weight,
                                            @NotNull RandomGenerator random) {
        Map<R, Double> rarities = new LinkedHashMap<>();
        rewards.forEach(reward -> rarities.putIfAbsent(rarity.apply(reward), rarityWeight.applyAsDouble(rarity.apply(reward))));

        R rolled = weighted(rarities.keySet(), rarities::get, random);

        List<T> candidates = new ArrayList<>();
        for (T reward : rewards) {
            if (rarity.apply(reward).equals(rolled)) candidates.add(reward);
        }
        return weighted(candidates, weight, random);
    }
}
