package su.nightexpress.excellentcrates.opening.summary;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.nightcore.util.text.night.NightMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CatCraft: one results screen for a mass opening instead of N separate results. Wins fill in from
 * the most common to the rarest, each with a rising note; broadcast (ultra) wins come last, glowing,
 * with a fanfare. Display only: the rewards were already given by the normal openings.
 */
public final class MassOpenSummary implements InventoryHolder {

    private static final int FILL_INTERVAL = 3;

    private final CratesPlugin plugin;
    private final Player       player;
    private final Inventory    inventory;
    private final List<Entry>  entries;

    private record Entry(Reward reward, int count) {}

    public MassOpenSummary(@NotNull CratesPlugin plugin, @NotNull Player player, @NotNull Crate crate, @NotNull List<Reward> rewards) {
        this.plugin = plugin;
        this.player = player;

        // One slot per win (rows grow with the number of keys); only group when it can't fit.
        if (rewards.size() <= 54) {
            this.entries = new ArrayList<>(rewards.stream().map(reward -> new Entry(reward, 1)).toList());
        }
        else {
            Map<String, Entry> grouped = new LinkedHashMap<>();
            for (Reward reward : rewards) {
                grouped.merge(reward.getId(), new Entry(reward, 1), (a, b) -> new Entry(a.reward(), a.count() + 1));
            }
            this.entries = new ArrayList<>(grouped.values());
        }
        // Most likely first, rarest last; ultras (broadcast) after everything else.
        this.entries.sort(Comparator.comparing((Entry entry) -> entry.reward().isBroadcast())
            .thenComparing(entry -> -entry.reward().getRollChance()));

        int rows = Math.clamp((this.entries.size() + 8) / 9, 1, 6);
        String title = NightMessage.asLegacy(crate.getName() + "<dark_gray> × " + rewards.size() + "</dark_gray>");
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    @NotNull
    public Inventory getInventory() {
        return this.inventory;
    }

    public void open() {
        this.player.openInventory(this.inventory);
        int shown = Math.min(this.entries.size(), this.inventory.getSize());

        new BukkitRunnable() {
            int index = 0;

            @Override
            public void run() {
                if (index >= shown || player.getOpenInventory().getTopInventory() != inventory) {
                    this.cancel();
                    return;
                }
                Entry entry = entries.get(index);
                inventory.setItem(index, icon(entry));
                if (entry.reward().isBroadcast()) {
                    player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1F, 1F);
                }
                else {
                    float pitch = 0.6F + 1.4F * index / Math.max(1, shown - 1);
                    player.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7F, pitch);
                }
                index++;
            }
        }.runTaskTimer(this.plugin, 10L, FILL_INTERVAL);
    }

    @NotNull
    private static ItemStack icon(@NotNull Entry entry) {
        ItemStack item = entry.reward().getPreviewItem().clone();
        if (entry.count() > 1) item.setAmount(Math.clamp(entry.count(), 1, item.getMaxStackSize()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
            if (entry.count() > 1) {
                lore.add("");
                lore.add(NightMessage.asLegacy("<gray>Won " + entry.count() + "×</gray>"));
            }
            meta.setLore(lore);
            if (entry.reward().isBroadcast()) meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static boolean guardRegistered;

    /** Registers the read-only guard once (managers are re-created on /crates reload). */
    public static void registerGuard(@NotNull CratesPlugin plugin) {
        if (guardRegistered) return;
        guardRegistered = true;
        plugin.getPluginManager().registerEvents(new Guard(), plugin);
    }

    /** Keeps the summary read-only. */
    public static final class Guard implements Listener {
        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (event.getInventory().getHolder() instanceof MassOpenSummary) event.setCancelled(true);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (event.getInventory().getHolder() instanceof MassOpenSummary) event.setCancelled(true);
        }
    }

}
