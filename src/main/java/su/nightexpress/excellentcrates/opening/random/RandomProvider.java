package su.nightexpress.excellentcrates.opening.random;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.api.opening.Opening;
import su.nightexpress.excellentcrates.api.opening.OpeningProvider;
import su.nightexpress.excellentcrates.crate.cost.Cost;
import su.nightexpress.excellentcrates.crate.impl.CrateSource;
import su.nightexpress.excellentcrates.opening.AbstractProvider;
import su.nightexpress.excellentcrates.opening.world.provider.DummyProvider;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.util.random.Rnd;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CatCraft: a random opening group (openings/random/&lt;id&gt;.yml). A crate whose Animation.Id names the group
 * plays one of the listed openings, picked by weight every time it opens. Members are looked up when the crate
 * opens, so they can live in any opening folder; unknown ids and other random groups are skipped.
 */
public class RandomProvider extends AbstractProvider {

    private final Map<String, Double> weights = new LinkedHashMap<>();

    public RandomProvider(@NotNull CratesPlugin plugin, @NotNull String id) {
        super(plugin, id);
    }

    @Override
    public void load(@NotNull FileConfig config) {
        this.weights.clear();
        for (String member : config.getSection("Openings")) {
            double weight = config.getDouble("Openings." + member, 1D);
            if (weight > 0) this.weights.put(member.toLowerCase(), weight);
        }
        if (this.weights.isEmpty()) this.plugin.warn("Random opening group '" + this.id + "' lists no openings.");
    }

    @NotNull
    public Map<String, Double> getWeights() {
        return this.weights;
    }

    @Override
    @NotNull
    public Opening createOpening(@NotNull Player player, @NotNull CrateSource source, @Nullable Cost cost) {
        Map<OpeningProvider, Double> available = new LinkedHashMap<>();
        this.weights.forEach((member, weight) -> {
            OpeningProvider provider = this.plugin.getOpeningManager().getProviderById(member);
            if (provider != null && !(provider instanceof RandomProvider)) available.merge(provider, weight, Double::sum);
        });
        OpeningProvider picked = available.isEmpty() ? new DummyProvider(this.plugin) : Rnd.getByWeight(available);
        return picked.createOpening(player, source, cost);
    }
}
