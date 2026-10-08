package su.nightexpress.excellentcrates.util;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.nightcore.bridge.item.AdaptedItem;
import su.nightexpress.nightcore.bridge.item.ItemAdapter;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.integration.item.ItemBridge;
import su.nightexpress.nightcore.integration.item.adapter.IdentifiableItemAdapter;
import su.nightexpress.nightcore.integration.item.data.ItemIdData;
import su.nightexpress.nightcore.integration.item.impl.AdaptedCustomStack;
import su.nightexpress.nightcore.integration.item.impl.AdaptedItemStack;
import su.nightexpress.nightcore.integration.item.impl.AdaptedVanillaStack;
import su.nightexpress.nightcore.util.ItemTag;
import su.nightexpress.nightcore.util.Version;

import java.util.Optional;

public class ItemHelper {

    @NotNull
    public static AdaptedItem readOrPlaceholder(@NotNull FileConfig config, @NotNull String path) {
        return read(config, path).orElseGet(DecodedItemCache::question); // CatCraft: was built eagerly for every item
    }

    @NotNull
    public static Optional<AdaptedItem> read(@NotNull FileConfig config, @NotNull String path) {
        String oldType = config.getString(path + ".Type");
        if (oldType != null) {
            AdaptedItem adaptedItem = null;

            if (oldType.equalsIgnoreCase("vanilla")) {
                String itemTag = config.getString(path + ".ItemTag");
                if (itemTag != null) {
                    ItemTag tag = new ItemTag(itemTag, Version.MC_1_21.getDataVersion());
                    config.set(path + ".Tag", tag);
                    config.remove(path + ".ItemTag");
                }

                if (config.contains(path + ".Tag")) {
                    ItemTag tag = ItemTag.read(config, path + ".Tag");
                    adaptedItem = new AdaptedVanillaStack(tag);
                    config.remove(path + ".Tag");
                }
            }
            else if (oldType.equalsIgnoreCase("custom")) {
                String handlerName = config.getString(path + ".Handler", "null");
                String itemId = config.getString(path + ".ItemId", "null");
                int amount = config.getInt(path + ".Amount");

                config.remove(path + ".Handler");
                config.remove(path + ".ItemId");
                config.remove(path + ".Amount");

                ItemAdapter<?> adapter = ItemBridge.getAdapter(handlerName);
                if (adapter instanceof IdentifiableItemAdapter identifiableItemAdapter) {
                    adaptedItem = new AdaptedCustomStack(identifiableItemAdapter, new ItemIdData(itemId, amount));
                }
                else if (adapter == null) {
                    config.set(path + ".Provider", handlerName);
                    config.set(path + ".Data.ID", itemId);
                    config.set(path + ".Data.Amount", amount);
                }
            }

            config.remove(path + ".Type");

            if (adaptedItem != null) {
                config.set(path, adaptedItem);
            }
        }

        // CatCraft: vanilla items are decoded once and reused across reloads (see DecodedItemCache).
        if ("vanilla".equalsIgnoreCase(config.getString(path + ".Provider"))) {
            ItemTag tag = ItemTag.read(config, path + ".Data");
            if (tag != null && !tag.isEmpty()) return Optional.of(DecodedItemCache.vanilla(tag));
        }
        return Optional.ofNullable(AdaptedItemStack.read(config, path));
    }

    @NotNull
    public static ItemStack toItemStack(@NotNull AdaptedItem item) {
        return item.itemStack().orElse(CrateUtils.getQuestionStack());
    }

    public static boolean isCustom(@NotNull ItemStack itemStack) {
        ItemAdapter<?> adapter = ItemBridge.getAdapter(itemStack);
        return adapter != null && !adapter.isVanilla();
    }

    @NotNull
    public static AdaptedItem vanilla(@NotNull ItemStack itemStack) {
        return AdaptedVanillaStack.of(itemStack);
    }

    @NotNull
    public static AdaptedItem adapt(@NotNull ItemStack itemStack) {
        ItemAdapter<?> adapter = ItemBridge.getAdapterOrVanilla(itemStack);
        AdaptedItem item = adapter.adapt(itemStack).orElse(null);
        return item == null ? vanilla(itemStack) : item;
    }

    @NotNull
    public static AdaptedItem adapt(@NotNull ItemStack itemStack, boolean allowCustoms) {
        return allowCustoms ? adapt(itemStack) : vanilla(itemStack);
    }
}
