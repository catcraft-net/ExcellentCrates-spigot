package su.nightexpress.excellentcrates.util;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.nightcore.util.Reflex;
import su.nightexpress.nightcore.util.nbt.NbtSerializer;
import su.nightexpress.nightcore.util.nbt.NbtUtil;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

/** Upgrade only stored vanilla item tags; unknown versions and unsuccessful decodes remain untouched. */
public final class ItemDataUpgrade {
    private ItemDataUpgrade() {}

    public record Result(int upgraded, int skipped, int failed) {}

    /** CatCraft: files already checked this server session (by modified time), so /crates reload skips them. */
    private static final java.util.Map<Path, java.nio.file.attribute.FileTime> CHECKED = new java.util.concurrent.ConcurrentHashMap<>();

    public static void run(CratesPlugin plugin) {
        Path root = plugin.getDataFolder().toPath();
        int upgraded = 0, changedFiles = 0, skipped = 0, failed = 0, unchanged = 0;
        for (String directory : List.of("crates", "keys")) {
            Path folder = root.resolve(directory);
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var paths = Files.walk(folder)) {
                for (Path path : paths.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .filter(file -> !folder.relativize(file).toString().contains("backups"))
                    .sorted().toList()) {
                    try {
                        java.nio.file.attribute.FileTime modified = Files.getLastModifiedTime(path);
                        if (modified.equals(CHECKED.get(path))) { unchanged++; continue; }
                        Path backups = root.resolve("item-data-backups").resolve(root.relativize(path).getParent());
                        Result result = upgradeFile(path, backups, plugin::warn);
                        CHECKED.put(path, Files.getLastModifiedTime(path));
                        upgraded += result.upgraded();
                        if (result.upgraded() > 0) changedFiles++;
                        skipped += result.skipped();
                        failed += result.failed();
                    }
                    catch (Exception exception) {
                        failed++;
                        plugin.warn("Item data upgrade left '" + path + "' unchanged: " + exception.getMessage());
                    }
                }
            }
            catch (IOException exception) {
                plugin.warn("Could not scan item data in '" + folder + "': " + exception.getMessage());
            }
        }
        plugin.info("Item data upgrade: " + upgraded + " items in " + changedFiles + " files; "
            + skipped + " unknown/newer versions left unchanged; " + failed + " failures. Target data version: "
            + Bukkit.getUnsafe().getDataVersion() + "." + (unchanged > 0 ? " Skipped " + unchanged + " files already checked." : ""));
    }

    public static Result upgradeFile(Path file, Path backupDirectory, Consumer<String> warning) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file");
        byte[] original = Files.readAllBytes(file);
        YamlConfiguration config = new YamlConfiguration();
        config.options().parseComments(true);
        config.loadFromString(new String(original, StandardCharsets.UTF_8));
        int target = Bukkit.getUnsafe().getDataVersion();
        if (target <= 0) throw new IllegalStateException("Server data version unavailable");
        int upgraded = 0, skipped = 0, failed = 0;
        for (String path : new ArrayList<>(config.getKeys(true))) {
            ConfigurationSection section = config.getConfigurationSection(path);
            if (section == null || !"vanilla".equalsIgnoreCase(section.getString("Provider"))) continue;
            String data = path + ".Data";
            int source = config.getInt(data + ".DataVersion", -1);
            if (source == target) continue;
            if (source <= 0 && target >= HiddenComponentsFix.MAP_COLOR_REMOVAL) {
                // Unknown version: nightcore decodes these without the data fixer. Only repair hidden_components names
                // that 26.3 no longer knows, and only if that is what makes the item decodable. The version stays as is.
                try {
                    String repaired = repairUnknownVersion(config.getString(data + ".Value"), target);
                    if (repaired != null) {
                        config.set(data + ".Value", repaired);
                        upgraded++;
                        continue;
                    }
                }
                catch (Exception exception) {
                    warning.accept("Item data repair skipped '" + file + "' at " + data + ": " + exception.getMessage());
                }
            }
            if (source <= 0 || source > target) { skipped++; continue; }
            String tag = config.getString(data + ".Value");
            try {
                String updated = convert(tag, source, target);
                config.set(data + ".Value", updated);
                config.set(data + ".DataVersion", target);
                upgraded++;
            }
            catch (Exception exception) {
                failed++;
                warning.accept("Item data upgrade skipped '" + file + "' at " + data + ": " + exception.getMessage());
            }
        }
        if (upgraded > 0) {
            // Validate the serialized document before creating the backup or replacing its source.
            String serialized = config.saveToString();
            YamlConfiguration check = new YamlConfiguration();
            check.loadFromString(serialized);
            if (!leaves(config).equals(leaves(check))) throw new IOException("YAML round-trip changed values");
            replaceWithBackup(file, backupDirectory, original, serialized.getBytes(StandardCharsets.UTF_8));
        }
        return new Result(upgraded, skipped, failed);
    }

    private static Map<String, Object> leaves(YamlConfiguration config) {
        Map<String, Object> values = new LinkedHashMap<>();
        config.getValues(true).forEach((key, value) -> {
            if (!(value instanceof ConfigurationSection)) values.put(key, value);
        });
        return values;
    }

    private static String convert(String tag, int source, int target) throws Exception {
        if (tag == null || tag.isBlank()) throw new IllegalArgumentException("Missing item tag");
        Object original = NbtUtil.tagFromString(tag);
        if (original == null) throw new IllegalArgumentException("Invalid item tag");
        Object updated = Codec.update(original, source, target);
        HiddenComponentsFix.apply(updated, source, target);
        ItemStack before = Codec.decode(updated);
        if (before.getType().isAir() || before.getAmount() <= 0) throw new IllegalArgumentException("Empty item");
        String serialized = updated.toString();
        Object reparsed = NbtUtil.tagFromString(serialized);
        if (!updated.equals(reparsed) || !before.equals(Codec.decode(reparsed))) {
            throw new IllegalStateException("Item round-trip changed its contents");
        }
        // Store the complete upgraded tag, not a Bukkit re-serialization that could omit extra fields.
        return serialized;
    }

    /** Returns the repaired tag, or null if the item already decodes or has nothing to repair. */
    private static String repairUnknownVersion(String tag, int target) throws Exception {
        if (tag == null || tag.isBlank()) return null;
        Object original = NbtUtil.tagFromString(tag);
        if (original == null) return null;
        try {
            Codec.decode(original);
            return null; // decodes as it is
        }
        catch (Exception ignored) {}
        // Treat the unknown version as older than both 26.3 component changes.
        if (!HiddenComponentsFix.apply(original, 0, target)) return null;
        ItemStack item = Codec.decode(original);
        if (item.getType().isAir() || item.getAmount() <= 0) throw new IllegalArgumentException("Empty item");
        String serialized = original.toString();
        Object reparsed = NbtUtil.tagFromString(serialized);
        if (!original.equals(reparsed) || !item.equals(Codec.decode(reparsed))) {
            throw new IllegalStateException("Item round-trip changed its contents");
        }
        return serialized;
    }

    private static void replaceWithBackup(Path file, Path directory, byte[] original, byte[] updated) throws Exception {
        Files.createDirectories(directory);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        Path backup = directory.resolve(file.getFileName() + "." + hash + ".bak");
        try {
            writeForced(backup, original, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        catch (FileAlreadyExistsException ignored) {
            if (!Arrays.equals(Files.readAllBytes(backup), original)) throw new IOException("Existing backup differs from source");
        }
        Path temporary = Files.createTempFile(file.getParent(), ".item-upgrade-", ".tmp");
        try {
            writeForced(temporary, updated, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            PosixFileAttributeView sourceAttributes = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (sourceAttributes != null) {
                var attributes = sourceAttributes.readAttributes();
                PosixFileAttributeView destination = Files.getFileAttributeView(temporary, PosixFileAttributeView.class);
                destination.setPermissions(attributes.permissions());
                if (!destination.readAttributes().group().equals(attributes.group())) destination.setGroup(attributes.group());
                if (!destination.readAttributes().owner().equals(attributes.owner())) destination.setOwner(attributes.owner());
            }
            if (!Arrays.equals(Files.readAllBytes(file), original)) throw new IOException("Source changed during upgrade");
            // Refuse a non-atomic fallback: a failed upgrade must leave the original intact.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally { Files.deleteIfExists(temporary); }
    }

    private static void writeForced(Path file, byte[] bytes, OpenOption... options) throws IOException {
        try (FileChannel channel = FileChannel.open(file, options)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    /**
     * Minecraft 26.3's data fixer renames/removes the swing_animation (data version 5007) and map_color (5008) components,
     * but does not update names listed in "minecraft:tooltip_display" hidden_components. The strict item codec then rejects
     * the whole item. Apply the same rename/removal to that list; anything else is left for the codec to judge.
     */
    static final class HiddenComponentsFix {
        static final int SWING_ANIMATION_SPLIT = 5007;
        static final int MAP_COLOR_REMOVAL = 5008;

        private HiddenComponentsFix() {}

        static boolean crosses(int source, int target, int version) {
            return source < version && target >= version;
        }

        /** Returns the fixed list of names, or null when nothing changes. */
        static List<String> fix(List<String> names, int source, int target) {
            boolean split = crosses(source, target, SWING_ANIMATION_SPLIT);
            boolean removeMapColor = crosses(source, target, MAP_COLOR_REMOVAL);
            if (!split && !removeMapColor) return null;
            LinkedHashSet<String> result = new LinkedHashSet<>();
            boolean changed = false;
            for (String name : names) {
                String id = name.indexOf(':') < 0 ? "minecraft:" + name : name;
                if (split && id.equals("minecraft:swing_animation")) {
                    result.add("minecraft:attack_animation");
                    result.add("minecraft:interact_animation");
                    changed = true;
                }
                else if (removeMapColor && id.equals("minecraft:map_color")) changed = true;
                else result.add(name);
            }
            return changed ? new ArrayList<>(result) : null;
        }

        static boolean apply(Object tag, int source, int target) throws Exception {
            if (!crosses(source, target, SWING_ANIMATION_SPLIT) && !crosses(source, target, MAP_COLOR_REMOVAL)) return false;
            Class<?> compound = Class.forName("net.minecraft.nbt.CompoundTag");
            Class<?> stringTag = Class.forName("net.minecraft.nbt.StringTag");
            Class<?> tagClass = Class.forName("net.minecraft.nbt.Tag");
            Method get = compound.getMethod("get", String.class);
            Object components = compound.isInstance(tag) ? get.invoke(tag, "components") : null;
            Object display = compound.isInstance(components) ? get.invoke(components, "minecraft:tooltip_display") : null;
            Object hidden = compound.isInstance(display) ? get.invoke(display, "hidden_components") : null;
            if (!(hidden instanceof List<?> list)) return false;
            Method value = stringTag.getMethod("value");
            List<String> names = new ArrayList<>();
            for (Object element : list) {
                if (!stringTag.isInstance(element)) return false; // unexpected shape: leave it to the codec
                names.add((String) value.invoke(element));
            }
            List<String> fixed = fix(names, source, target);
            if (fixed == null) return false;
            Method valueOf = stringTag.getMethod("valueOf", String.class);
            Class<?> listTag = Class.forName("net.minecraft.nbt.ListTag");
            @SuppressWarnings("unchecked")
            List<Object> replacement = (List<Object>) listTag.getConstructor().newInstance();
            for (String name : fixed) replacement.add(valueOf.invoke(null, name));
            compound.getMethod("put", String.class, tagClass).invoke(display, "hidden_components", replacement);
            return true;
        }
    }

    /** Uses the same native codec/data fixer as nightcore, but rejects partial results and uses the actual server version. */
    private static final class Codec {
        private static final Object FIXER = Reflex.invokeMethod(Reflex.safeMethod(
            Reflex.safeClass("net.minecraft.util.datafix", "DataFixers", "DataConverterRegistry"), "getDataFixer", "a"), null);
        private static final Object REFERENCE = Reflex.getFieldValue(Reflex.safeClass(
            "net.minecraft.util.datafix.fixes", "References", "DataConverterTypes"), "ITEM_STACK", "u");
        private static final Object ITEM_CODEC = Reflex.getFieldValue(NbtUtil.CLS_NMS_ITEM_STACK, "CODEC", "b");

        private static Object update(Object tag, int source, int target) throws Exception {
            Class<?> ops = Class.forName("com.mojang.serialization.DynamicOps");
            Class<?> dynamic = Class.forName("com.mojang.serialization.Dynamic");
            Object nbtOps = Class.forName("net.minecraft.nbt.NbtOps").getField("INSTANCE").get(null);
            Constructor<?> constructor = dynamic.getConstructor(ops, Object.class);
            Method update = Class.forName("com.mojang.datafixers.DataFixer").getMethod("update",
                Class.forName("com.mojang.datafixers.DSL$TypeReference"), dynamic, int.class, int.class);
            Object fixed = update.invoke(FIXER, REFERENCE, constructor.newInstance(nbtOps, tag), source, target);
            return dynamic.getMethod("getValue").invoke(fixed);
        }

        private static ItemStack decode(Object tag) throws Exception {
            Object result = Class.forName("com.mojang.serialization.Decoder").getMethod("parse",
                Class.forName("com.mojang.serialization.DynamicOps"), Object.class)
                .invoke(ITEM_CODEC, NbtSerializer.createSerializationContext(), tag);
            Optional<?> complete = (Optional<?>) Class.forName("com.mojang.serialization.DataResult").getMethod("result").invoke(result);
            if (complete.isEmpty()) throw new IllegalArgumentException("Item codec rejected data (partial results are not saved)");
            return (ItemStack) NbtUtil.AS_BUKKIT_COPY.invoke(null, complete.get());
        }
    }
}
