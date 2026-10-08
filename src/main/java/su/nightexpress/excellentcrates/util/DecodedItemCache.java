package su.nightexpress.excellentcrates.util;

import org.jetbrains.annotations.NotNull;
import su.nightexpress.nightcore.bridge.item.AdaptedItem;
import su.nightexpress.nightcore.integration.item.impl.AdaptedVanillaStack;
import su.nightexpress.nightcore.util.ItemTag;

import java.util.HashMap;
import java.util.Map;

/**
 * CatCraft: decoded vanilla items, reused across /crates reload. Decoding the ~4,500 item tags of a big crate
 * collection was over half of the reload's main-thread freeze, and nearly all of them are unchanged between reloads.
 * Decoded items are immutable (getItemStack() hands out copies), so sharing them is safe. Entries not used by the
 * latest load are dropped when it finishes, so the cache never holds more than the loaded crates and keys.
 */
public final class DecodedItemCache {

    private static Map<String, AdaptedVanillaStack> current = new HashMap<>();
    private static Map<String, AdaptedVanillaStack> previous = new HashMap<>();
    private static AdaptedItem question;

    private DecodedItemCache() {}

    /** Called when the plugin starts (re)loading its crates and keys. */
    public static void beginLoad() {
        previous = current;
        current = new HashMap<>();
        question = null;
    }

    /** Called when loading is done: anything the new load didn't use is let go. */
    public static void endLoad() {
        previous = new HashMap<>();
    }

    @NotNull
    public static AdaptedVanillaStack vanilla(@NotNull ItemTag tag) {
        String key = tag.getDataVersion() + ":" + tag.getTag();
        AdaptedVanillaStack item = current.get(key);
        if (item == null) {
            item = previous.remove(key);
            if (item == null) item = new AdaptedVanillaStack(tag);
            current.put(key, item);
        }
        return item;
    }

    /** The "?" placeholder every reward starts with before its real preview is read (built once per load). */
    @NotNull
    public static AdaptedItem question() {
        if (question == null) question = ItemHelper.vanilla(CrateUtils.getQuestionStack());
        return question;
    }
}
