package su.nightexpress.excellentcrates.opening.inventory.spinner.impl;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.impl.Rarity;
import su.nightexpress.excellentcrates.opening.inventory.InventoryOpening;
import su.nightexpress.excellentcrates.opening.inventory.spinner.AbstractSpinner;
import su.nightexpress.excellentcrates.opening.inventory.spinner.SpinMode;
import su.nightexpress.excellentcrates.opening.inventory.spinner.SpinnerData;
import su.nightexpress.nightcore.util.Lists;
import su.nightexpress.nightcore.util.random.Rnd;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RewardSpinner extends AbstractSpinner {

    private final Set<Rarity> rarities;

    private final java.util.Deque<Reward> showcase = new java.util.ArrayDeque<>(); // CatCraft
    private int rewardIndex;

    public RewardSpinner(@NotNull SpinnerData data, @NotNull InventoryOpening opening, @NotNull Set<Rarity> rarities) {
        this(data, opening, rarities, false);
    }

    public RewardSpinner(@NotNull SpinnerData data, @NotNull InventoryOpening opening, @NotNull Set<Rarity> rarities, boolean showcase) {
        super(data, opening);
        this.rarities = rarities;
        if (showcase && data.getMode() == SpinMode.SEQUENTAL) {
            List<Reward> ultras = new java.util.ArrayList<>(opening.getCrate().getRewards(opening.getPlayer()));
            ultras.removeIf(reward -> !reward.isBroadcast() || !rarities.contains(reward.getRarity()));
            java.util.Collections.shuffle(ultras);
            this.showcase.addAll(ultras);
        }
        this.rewardIndex = opening.getRewards().size(); // Start from latest index after previous reward spinners added their rewards.

        this.prepareRewards();
    }

    private boolean isWinSlot(int slot) {
        return Lists.contains(this.winSlots, slot);
    }

    private void prepareRewards() {
        for (int winSlot : this.winSlots) {
            if (Lists.contains(this.slots, winSlot)) {
                this.opening.addReward(this.rollReward(false));
            }
        }
    }

    @NotNull
    private Reward rollReward(boolean visual) {
        Crate crate = this.opening.getCrate();
        Player player = this.opening.getPlayer();

        if (!visual || Config.OPENINGS_GUI_SIMULATE_REAL_CHANCES.get()) {
            Map<Rarity, Double> rarityMap = new HashMap<>();
            this.rarities.forEach(rarity -> {
                if (crate.hasRewards(player, rarity)) {
                    rarityMap.put(rarity, rarity.getWeight());
                }
            });
            if (rarityMap.isEmpty()) throw new IllegalStateException("No rewards available!");

            Rarity rarity = Rnd.getByWeight(rarityMap);
            return crate.rollReward(this.opening.getPlayer(), rarity);
        }
        else {
            List<Reward> rewards = crate.getRewards(player);
            rewards.removeIf(reward -> !this.rarities.contains(reward.getRarity()));
            if (rewards.isEmpty()) throw new IllegalStateException("No rewards available!");

            return Rnd.get(rewards);
        }
    }

    @Override
    @NotNull
    public ItemStack createItem(int slot) {
        Reward reward = this.shouldUsePredictedReward(slot) ? this.opening.getRewards().get(this.rewardIndex++) : this.showcaseOrRoll();
        if (reward == null) return new ItemStack(Material.AIR);

        return reward.getPreviewItem();
    }

    /**
     * CatCraft showcase: the crate's ultras pass through the reel in every spin, one every 3 moves,
     * but only while they will scroll out again before the reel stops. They are never placed to land
     * next to the win slot; the result and its neighbours stay random (no staged near-misses).
     */
    @NotNull
    private Reward showcaseOrRoll() {
        if (!this.showcase.isEmpty()) {
            int spinsLeft = Math.toIntExact(this.requiredSpins - this.spinCount);
            if (spinsLeft <= this.slots.length) {
                this.showcase.clear(); // Too late: it would still be on screen when the reel stops.
            }
            else if (this.spinCount % 3 == 1) {
                return this.showcase.poll();
            }
        }
        return this.rollReward(true);
    }

    private boolean shouldUsePredictedReward(int slot) {
        if (this.rewardIndex >= this.opening.getRewards().size()) return false;

        int spinsLeft = Math.toIntExact(this.requiredSpins - this.spinCount);
        SpinMode mode = this.data.getMode();

        if (mode == SpinMode.SYNCRHONIZED) return spinsLeft == 1;
        if (mode == SpinMode.RANDOM || mode == SpinMode.INDEPENDENT) return spinsLeft == 1 && this.isWinSlot(slot);

        if (mode == SpinMode.SEQUENTAL) {
            for (int winSlot : this.winSlots) {
                int index = Lists.indexOf(this.slots, winSlot) + 1;
                if (index > 0 && spinsLeft == index) return true;
            }
        }

        return false;
    }

    @Override
    protected void spinRandom() {
        this.spinIndependent(); // Random mode makes no sense for reward spinners. Also it's not possible to predict reward for it.
    }

    @Override
    protected void onStop() {

    }
}
