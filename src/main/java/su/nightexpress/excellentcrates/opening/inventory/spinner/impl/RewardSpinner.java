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
    private int showcased;
    private int showcaseTotal;
    private int rewardIndex;

    public RewardSpinner(@NotNull SpinnerData data, @NotNull InventoryOpening opening, @NotNull Set<Rarity> rarities) {
        this(data, opening, rarities, false);
    }

    public RewardSpinner(@NotNull SpinnerData data, @NotNull InventoryOpening opening, @NotNull Set<Rarity> rarities, boolean showcase) {
        super(data, opening);
        this.rarities = rarities;
        opening.getCrate().getRewards(opening.getPlayer()).forEach(RewardSpinner::preview);
        if (showcase && data.getMode() == SpinMode.SEQUENTAL) {
            List<Reward> ultras = new java.util.ArrayList<>(opening.getCrate().getRewards(opening.getPlayer()));
            ultras.removeIf(reward -> !reward.isBroadcast() || !rarities.contains(reward.getRarity()));
            java.util.Collections.shuffle(ultras);
            this.showcase.addAll(ultras.subList(0, Math.min(3, ultras.size())));
            this.showcaseTotal = this.showcase.size();
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

        return preview(reward);
    }

    /**
     * CatCraft: reward previews are built once and reused (building an ExecutableItems item every reel
     * move cost ~3 ms each and made the start of the reel stutter on busy servers). Reward objects are
     * recreated on /crates reload, so the weak keys drop stale entries.
     */
    private static final java.util.Map<Reward, ItemStack> PREVIEWS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    @NotNull
    private static ItemStack preview(@NotNull Reward reward) {
        return PREVIEWS.computeIfAbsent(reward, Reward::getPreviewItem).clone();
    }

    /**
     * CatCraft showcase: the crate's ultras pass through the reel in every spin as late as possible
     * while staying honest. The last one enters so that it leaves the reel exactly when the real
     * prize enters for the final creep (more ultras: 3 moves earlier each, up to 3), so they cross the
     * spotlight at a readable speed but never sit next to the prize while it settles. The result and
     * its neighbours stay random (no staged near-misses).
     */
    @NotNull
    private Reward showcaseOrRoll() {
        if (!this.showcase.isEmpty()) {
            int spinsLeft = Math.toIntExact(this.requiredSpins - this.spinCount);
            // Insertion points, earliest first: latest + 3*(total-1), ..., latest + 3, latest.
            int next = this.latestShowcaseSpin() + 3 * (this.showcaseTotal - 1 - this.showcased);
            if (spinsLeft == next) {
                this.showcased++;
                return this.showcase.poll();
            }
        }
        return this.rollReward(true);
    }

    /** Spins left at which an item put in now leaves the reel just as the prize enters it. */
    private int latestShowcaseSpin() {
        int enters = Integer.MAX_VALUE;
        for (int winSlot : this.winSlots) {
            int index = Lists.indexOf(this.slots, winSlot);
            if (index >= 0) enters = Math.min(enters, index + 1);
        }
        if (enters == Integer.MAX_VALUE) enters = 1;
        return this.slots.length + enters;
    }

    /**
     * CatCraft: an instant opening (skip, /crates fast, mass opening) doesn't need the reel's display
     * items: the rewards were rolled when the spinner was created. Jump to the end instead of building
     * every filler item (custom items like ExecutableItems are expensive to build).
     */
    @Override
    public void tickAll() {
        if (!this.running) return;
        this.spinCount = Math.max(this.spinCount, this.requiredSpins);
        this.steps.clear();
        this.currentStep = null;
        this.rewardIndex = this.opening.getRewards().size();
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
