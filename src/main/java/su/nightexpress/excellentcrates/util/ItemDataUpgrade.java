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

    public static void run(CratesPlugin plugin) {
        Path root = plugin.getDataFolder().toPath();
        int upgraded = 0, changedFiles = 0, skipped = 0, failed = 0;
        for (String directory : List.of("crates", "keys")) {
            Path folder = root.resolve(directory);
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var paths = Files.walk(folder)) {
                for (Path path : paths.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .filter(file -> !folder.relativize(file).toString().contains("backups"))
                    .sorted().toList()) {
                    try {
                        Path backups = root.resolve("item-data-backups").resolve(root.relativize(path).getParent());
                        Result result = upgradeFile(path, backups, plugin::warn);
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
            + Bukkit.getUnsafe().getDataVersion() + ".");
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
