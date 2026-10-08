package su.nightexpress.excellentcrates.opening.inventory.spinner.impl;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.opening.inventory.InventoryOpening;
import su.nightexpress.excellentcrates.opening.inventory.spinner.AbstractSpinner;
import su.nightexpress.excellentcrates.opening.inventory.spinner.SpinnerData;
import su.nightexpress.nightcore.util.bukkit.NightItem;
import su.nightexpress.nightcore.util.random.Rnd;
import su.nightexpress.nightcore.util.random.WeightedItem;

import java.util.ArrayList;
import java.util.List;

public class AnimationSpinner extends AbstractSpinner {

    private final List<WeightedItem<NightItem>> items;
    private final java.util.Map<NightItem, String> themeByItem;

    public AnimationSpinner(@NotNull SpinnerData data, @NotNull InventoryOpening opening, @NotNull List<WeightedItem<NightItem>> items) {
        this(data, opening, items, java.util.Map.of());
    }

    public AnimationSpinner(@NotNull SpinnerData data, @NotNull InventoryOpening opening, @NotNull List<WeightedItem<NightItem>> items,
                            @NotNull java.util.Map<NightItem, String> themeByItem) {
        super(data, opening);
        this.items = items;
        this.themeByItem = themeByItem;
    }

    @Override
    protected void onStop() {

    }

    @Override
    @NotNull
    public ItemStack createItem(int slot) {
        if (this.items.isEmpty()) return new ItemStack(Material.AIR);
        NightItem item = Rnd.getByWeight(new ArrayList<>(this.items));
        ItemStack stack = item.getItemStack();
        // CatCraft: "Theme: primary/secondary" items take the crate's own colours.
        Material pane = this.opening.getTheme().pane(this.themeByItem.get(item));
        if (pane != null) stack.setType(pane);
        return stack;
    }
}
