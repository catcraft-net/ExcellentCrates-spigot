package su.nightexpress.excellentcrates.editor.crate;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import su.nightexpress.excellentcrates.util.ItemDataUpgrade;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.util.ItemTag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

import static org.junit.Assert.*;

public class ItemDataUpgradeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void startupPersistsUpgradedLegacyKeyData() {
        Path folder = Bukkit.getPluginManager().getPlugin("ExcellentCrates").getDataFolder().toPath();
        FileConfig config = FileConfig.load(folder.resolve("keys/summer_2023.yml"));
        assertEquals("Legacy item data must be persisted at the server's actual data version",
            Bukkit.getUnsafe().getDataVersion(), config.getInt("ItemData.Data.DataVersion"));
    }

    private Path file(String yaml) throws Exception {
        Path file = temporary.newFile().toPath();
        Files.writeString(file, yaml);
        return file;
    }

    private String item(String path, int version, String tag) {
        YamlConfiguration config = new YamlConfiguration();
        config.set(path + ".Provider", "vanilla");
        config.set(path + ".Data.DataVersion", version);
        config.set(path + ".Data.Value", tag);
        return config.saveToString();
    }

    @Test public void conversionBacksUpExactBytesPreservesQuantityAndIsIdempotent() throws Exception {
        String tag = "{id:\"minecraft:diamond_sword\",count:3,components:{\"minecraft:custom_data\":{catcraft:\"keep\",migration_version:1},\"minecraft:custom_name\":'{\"text\":\"Old Sword\"}',\"minecraft:enchantments\":{levels:{\"minecraft:sharpness\":5}}}}";
        Path file = file(item("Reward", 4189, tag) + "Unrelated: keep\n");
        Path backups = temporary.newFolder().toPath();
        byte[] original = Files.readAllBytes(file);
        ItemStack expected = new ItemTag(tag, 4189).getItemStack();
        assertNotNull(expected);
        var result = ItemDataUpgrade.upgradeFile(file, backups, ignored -> {});
        assertEquals(1, result.upgraded());
        FileConfig after = FileConfig.load(file);
        assertEquals(expected, ItemTag.read(after, "Reward.Data").getItemStack());
        assertEquals("keep", after.getString("Unrelated"));
        try (var files = Files.list(backups)) {
            List<Path> saved = files.toList();
            assertEquals(1, saved.size());
            assertArrayEquals(original, Files.readAllBytes(saved.getFirst()));
        }
        byte[] upgraded = Files.readAllBytes(file);
        var timestamp = Files.getLastModifiedTime(file);
        assertEquals(0, ItemDataUpgrade.upgradeFile(file, backups, ignored -> {}).upgraded());
        assertArrayEquals(upgraded, Files.readAllBytes(file));
        assertEquals(timestamp, Files.getLastModifiedTime(file));
    }

    @Test public void currentFutureUnknownAndCustomProviderDataAreUntouched() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        int target = Bukkit.getUnsafe().getDataVersion();
        int[] versions = {target, target + 1, 0, -1};
        for (int i = 0; i < versions.length; i++) {
            config.set("Items." + i + ".Provider", "vanilla");
            config.set("Items." + i + ".Data.DataVersion", versions[i]);
            config.set("Items." + i + ".Data.Value", "{id:\"minecraft:diamond\",count:7}");
        }
        config.set("Custom.Provider", "nexo");
        config.set("Custom.Data.DataVersion", 4189);
        config.set("Custom.Data.ID", "special_hat");
        Path file = file(config.saveToString());
        byte[] original = Files.readAllBytes(file);
        assertEquals(0, ItemDataUpgrade.upgradeFile(file, temporary.newFolder().toPath(), ignored -> {}).upgraded());
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test public void invalidAndPartialItemsAreNotSaved() throws Exception {
        Path file = file(item("Bad", 4189, "{id:\"minecraft:diamond\",count:1,components:{\"minecraft:damage\":\"not_a_number\"}}"));
        byte[] original = Files.readAllBytes(file);
        List<String> warnings = new ArrayList<>();
        var result = ItemDataUpgrade.upgradeFile(file, temporary.newFolder().toPath(), warnings::add);
        assertEquals(0, result.upgraded());
        assertEquals(1, result.failed());
        assertFalse(warnings.isEmpty());
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test public void backupFailureNeverReplacesOriginal() throws Exception {
        Path file = file(item("Item", 4189, "{id:\"minecraft:diamond\",count:7}"));
        Path notDirectory = temporary.newFile().toPath();
        byte[] original = Files.readAllBytes(file);
        assertThrows(Exception.class, () -> ItemDataUpgrade.upgradeFile(file, notDirectory, ignored -> {}));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test public void conversionPreservesFilePermissions() throws Exception {
        Path file = file(item("Item", 4189, "{id:\"minecraft:diamond\",count:7}"));
        if (!Files.getFileStore(file).supportsFileAttributeView("posix")) return;
        var permissions = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(file, permissions);
        ItemDataUpgrade.upgradeFile(file, temporary.newFolder().toPath(), ignored -> {});
        assertEquals(permissions, Files.getPosixFilePermissions(file));
    }

    @Test public void suppliedArchiveKeepsRuntimeItemsAndAllUnrelatedValues() throws Exception {
        Path originals = Bukkit.getPluginManager().getPlugin("SearchIntegration").getDataFolder().toPath().resolve("original-fixtures");
        assertTrue("Copy the original supplied crates/keys into original-fixtures", Files.isDirectory(originals));
        int checked = 0;
        try (var files = Files.walk(originals)) {
            for (Path source : files.filter(path -> path.toString().endsWith(".yml")).toList()) {
                Path copy = temporary.newFile().toPath();
                Files.copy(source, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                FileConfig before = FileConfig.load(copy);
                ItemDataUpgrade.upgradeFile(copy, temporary.newFolder().toPath(), ignored -> {});
                FileConfig after = FileConfig.load(copy);
                Map<String, Object> oldValues = leaves(before);
                Map<String, Object> newValues = leaves(after);
                for (String path : before.getKeys(true)) {
                    if (!"vanilla".equalsIgnoreCase(before.getString(path + ".Provider"))) continue;
                    String data = path + ".Data";
                    if (before.getInt(data + ".DataVersion") == after.getInt(data + ".DataVersion")) continue;
                    ItemStack oldItem = ItemTag.read(before, data).getItemStack();
                    ItemStack newItem = ItemTag.read(after, data).getItemStack();
                    assertNotNull(source + ":" + path, oldItem);
                    assertEquals(source + ":" + path, oldItem, newItem);
                    oldValues.remove(data + ".Value"); newValues.remove(data + ".Value");
                    oldValues.remove(data + ".DataVersion"); newValues.remove(data + ".DataVersion");
                    checked++;
                }
                assertEquals("Unrelated settings changed in " + source, oldValues, newValues);
            }
        }
        assertTrue("Must verify the actual large fixture, not just one item", checked > 3000);
        Bukkit.getLogger().info("ITEM_UPGRADE_FIXTURE: compared=" + checked);
    }

    private Map<String, Object> leaves(FileConfig config) {
        Map<String, Object> result = new LinkedHashMap<>();
        config.getValues(true).forEach((key, value) -> { if (!(value instanceof ConfigurationSection)) result.put(key, value); });
        return result;
    }
}
