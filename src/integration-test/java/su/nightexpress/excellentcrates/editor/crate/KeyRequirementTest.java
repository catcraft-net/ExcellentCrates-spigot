package su.nightexpress.excellentcrates.editor.crate;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.*;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.api.cost.CostEntry;
import su.nightexpress.excellentcrates.api.event.CrateOpenEvent;
import su.nightexpress.excellentcrates.api.opening.*;
import su.nightexpress.excellentcrates.crate.cost.Cost;
import su.nightexpress.excellentcrates.crate.cost.CostTypeId;
import su.nightexpress.excellentcrates.crate.cost.entry.impl.*;
import su.nightexpress.excellentcrates.crate.cost.type.impl.*;
import su.nightexpress.excellentcrates.crate.impl.*;
import su.nightexpress.excellentcrates.key.CrateKey;
import su.nightexpress.excellentcrates.registry.CratesRegistries;
import su.nightexpress.excellentcrates.user.CrateUser;
import su.nightexpress.excellentcrates.util.ItemHelper;
import su.nightexpress.nightcore.config.FileConfig;

import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.Assert.*;

/** Real manager/cost/key code on Paper. Only the network player and reward animation are test doubles. */
public class KeyRequirementTest {
    private CratesPlugin plugin;
    private Crate crate;
    private Player player;
    private CrateUser user;
    private CrateKey key;
    private boolean oldVirtual, oldAnimation;
    private String oldOpening;
    private List<Cost> oldCosts;
    private final ItemStack[] inventory = new ItemStack[41];
    private int starts, keysAtStart;
    private Runnable onClose = () -> {};
    private Runnable onCreate = () -> {};
    private final Listener listener = new Listener() {};

    @Before public void setup() {
        plugin = (CratesPlugin) Bukkit.getPluginManager().getPlugin("ExcellentCrates");
        crate = plugin.getCrateManager().getCrateById("summer2026");
        oldCosts = crate.getCosts();
        oldAnimation = crate.isOpeningEnabled();
        oldOpening = crate.getOpeningId();
        crate.getCostMap().clear();
        key = plugin.getKeyManager().getKeys().iterator().next();
        oldVirtual = key.isVirtual();
        key.setVirtual(false);
        UUID id = UUID.randomUUID();
        PlayerInventory items = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
            new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getContents" -> inventory;
                case "firstEmpty" -> 1;
                default -> throw new UnsupportedOperationException("Inventory: " + method.getName());
            });
        player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName", "toString", "getDisplayName" -> "KeyTest";
                case "getInventory" -> items;
                case "getLevel" -> 10;
                case "hasPermission", "isPermissionSet", "isOnline" -> true;
                case "isSneaking" -> false;
                case "closeInventory" -> { onClose.run(); yield null; }
                case "sendMessage", "sendTitle", "playSound", "setLevel" -> null;
                case "spigot" -> new Player.Spigot() {
                    @Override public void sendMessage(net.md_5.bungee.api.chat.BaseComponent... components) {}
                    @Override public void sendMessage(net.md_5.bungee.api.ChatMessageType type, net.md_5.bungee.api.chat.BaseComponent... components) {}
                };
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException("Player: " + method.getName());
            });
        user = plugin.getUserManager().create(id, "KeyTest");
        plugin.getUserManager().cachePermanent(user);
        plugin.getOpeningManager().getProviderByIdMap().put("key_test", new OpeningProvider() {
            public void load(FileConfig config) {}
            public String getId() { return "key_test"; }
            public Opening createOpening(Player who, CrateSource source, Cost cost) {
                onCreate.run();
                return (Opening) Proxy.newProxyInstance(Opening.class.getClassLoader(), new Class<?>[]{Opening.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "start" -> {
                            starts++;
                            keysAtStart = plugin.getKeyManager().getKeysAmount(player, key);
                            plugin.getOpeningManager().removeOpening(player);
                            yield null;
                        }
                        case "instaRoll", "stop" -> null;
                        case "getPlayer" -> player;
                        default -> throw new UnsupportedOperationException("Opening: " + method.getName());
                    });
            }
        });
        crate.setOpeningEnabled(true);
        crate.setOpeningId("key_test");
        assertFalse("Fixture must have usable rewards", crate.getRewards(player).isEmpty());
    }

    @After public void cleanup() {
        HandlerList.unregisterAll(listener);
        plugin.getOpeningManager().removeOpening(player);
        plugin.getOpeningManager().getProviderByIdMap().remove("key_test");
        crate.getCostMap().clear();
        oldCosts.forEach(crate::addCost);
        crate.setOpeningEnabled(oldAnimation);
        crate.setOpeningId(oldOpening);
        key.setVirtual(oldVirtual);
        plugin.getUserManager().getLoadedByIdMap().remove(player.getUniqueId());
        plugin.getUserManager().getLoadedByNameMap().remove("keytest");
    }

    private KeyCostEntry keyEntry(String id, int amount) {
        KeyCostEntry entry = ((KeyCostType) CratesRegistries.getCostType(CostTypeId.KEY)).createEmpty();
        entry.setKeyId(id);
        entry.setAmount(amount);
        return entry;
    }

    private Cost cost(CostEntry... entries) {
        Cost cost = new Cost("test", true, "Test", ItemHelper.vanilla(new ItemStack(Material.TRIAL_KEY)), new ArrayList<>(List.of(entries)));
        crate.addCost(cost);
        return cost;
    }

    private void give(int amount) { inventory[0] = key.getItemStack(); inventory[0].setAmount(amount); }
    private boolean open(Cost cost) { return plugin.getCrateManager().openCrate(player, new CrateSource(crate), OpenOptions.empty(), cost); }
    private void event(java.util.function.Consumer<CrateOpenEvent> handler) {
        Bukkit.getPluginManager().registerEvent(CrateOpenEvent.class, listener, EventPriority.NORMAL,
            (ignored, event) -> handler.accept((CrateOpenEvent) event), Bukkit.getPluginManager().getPlugin("SearchIntegration"));
    }

    @Test public void missingCostsNeverMeanFreeOpening() {
        assertFalse(open(null));
        assertEquals(0, starts);
    }

    @Test public void invalidMigratedKeyIsBlockedOnNormalClickPath() {
        cost(keyEntry("deleted_legacy_key", 1));
        plugin.getCrateManager().preOpenCrate(player, new CrateSource(crate));
        assertEquals(0, starts);
    }

    @Test public void emptyAndDisabledCostsAreBlocked() {
        assertFalse(open(cost()));
        Cost disabled = cost(keyEntry(key.getId(), 1));
        disabled.setEnabled(false);
        give(2);
        assertFalse(open(disabled));
        assertEquals(2, plugin.getKeyManager().getKeysAmount(player, key));
    }

    @Test public void foreignCostCannotPayForThisCrate() {
        Cost foreign = cost(keyEntry(key.getId(), 1));
        crate.getCostMap().clear();
        give(2);
        assertFalse(open(foreign));
    }

    @Test public void forceCannotBypassKeyRequirement() {
        Cost cost = cost(keyEntry(key.getId(), 1));
        give(2);
        assertFalse(plugin.getCrateManager().openCrate(player, new CrateSource(crate), OpenOptions.ignoreRestrictions(), cost));
        assertEquals(0, starts);
    }

    @Test public void ordinaryItemsAndKeysForOtherCratesDoNotCount() {
        Cost cost = cost(keyEntry(key.getId(), 1));
        inventory[0] = new ItemStack(Material.TRIAL_KEY, 8);
        assertFalse(open(cost));
        CrateKey other = plugin.getKeyManager().getKeys().stream().filter(candidate -> candidate != key).findFirst().orElseThrow();
        inventory[0] = other.getItemStack();
        assertFalse(open(cost));
        assertEquals(0, starts);
    }

    @Test public void physicalKeysAreConsumedBeforeAnimationOrRewardsStart() {
        Cost cost = cost(keyEntry(key.getId(), 2));
        give(3);
        assertTrue(open(cost));
        assertEquals(1, keysAtStart);
        assertEquals(1, plugin.getKeyManager().getKeysAmount(player, key));
    }

    @Test public void virtualKeysAreRequiredAndConsumed() {
        key.setVirtual(true);
        Cost cost = cost(keyEntry(key.getId(), 2));
        user.setKeys(key.getId(), 1);
        assertFalse(open(cost));
        user.setKeys(key.getId(), 3);
        assertTrue(open(cost));
        assertEquals(1, keysAtStart);
        assertEquals(1, user.countKeys(key));
    }

    @Test public void duplicateKeyEntriesRequireTheirCombinedAmount() {
        Cost cost = cost(keyEntry(key.getId(), 2), keyEntry(key.getId(), 2));
        give(3);
        assertFalse(open(cost));
        assertEquals(3, plugin.getKeyManager().getKeysAmount(player, key));
        give(5);
        assertTrue(open(cost));
        assertEquals(1, keysAtStart);
    }

    @Test public void bulkOpeningStopsWhenKeysRunOut() {
        Cost cost = cost(keyEntry(key.getId(), 1));
        give(2);
        plugin.getCrateManager().multiOpenCrate(player, new CrateSource(crate), OpenOptions.empty(), cost, 5);
        assertEquals(2, starts);
        assertEquals(0, plugin.getKeyManager().getKeysAmount(player, key));
    }

    @Test public void eventRemovingKeysCannotLeaveAnUnpaidOpening() {
        Cost cost = cost(keyEntry(key.getId(), 1));
        give(1);
        event(ignored -> inventory[0].setAmount(0));
        assertFalse(open(cost));
        assertEquals(0, starts);
    }

    @Test public void eventDisablingCostIsRechecked() {
        Cost cost = cost(keyEntry(key.getId(), 1));
        give(1);
        event(ignored -> cost.setEnabled(false));
        assertFalse(open(cost));
        assertEquals(1, plugin.getKeyManager().getKeysAmount(player, key));
    }

    @Test public void cancelledOpeningDoesNotConsumeKeys() {
        Cost cost = cost(keyEntry(key.getId(), 1));
        give(1);
        event(event -> event.setCancelled(true));
        assertFalse(open(cost));
        assertEquals(1, plugin.getKeyManager().getKeysAmount(player, key));
    }

    @Test public void currencyOnlyOptionIsNotAKeyPayment() {
        EcoCostEntry money = ((EcoCostType) CratesRegistries.getCostType(CostTypeId.CURRENCY)).createEmpty();
        money.setCurrencyId("xp_level");
        money.setAmount(1);
        assertTrue(money.isValid());
        Cost cost = cost(money);
        assertTrue(crate.getFirstCost().isEmpty());
        assertFalse(open(cost));
    }

    @Test public void inventoryCloseCannotRemoveKeyAfterValidation() {
        Cost cost = cost(keyEntry(key.getId(), 1));
        give(1);
        onClose = () -> inventory[0].setAmount(0);
        assertFalse(open(cost));
        assertEquals(0, starts);
    }

    @Test public void openingProviderCannotInvalidateCostAfterValidation() {
        Cost cost = cost(keyEntry(key.getId(), 1));
        give(1);
        onCreate = () -> cost.setEnabled(false);
        assertFalse(open(cost));
        assertEquals(0, starts);
    }

    @Test public void disablingSafeguardDeliberatelyRestoresKeylessOpenings() {
        try {
            Config.OPENING_REQUIRE_KEY.set(false);
            assertTrue(open(null));
            assertEquals(1, starts);
        }
        finally { Config.OPENING_REQUIRE_KEY.set(true); }
    }

    @Test public void missingGlobalSettingDefaultsToProtection() {
        FileConfig config = new FileConfig(plugin.getDataFolder().toPath().resolve("unused-test-defaults.yml").toFile());
        Config.OPENING_REQUIRE_KEY.read(config);
        assertFalse(open(null));
    }

    @Test public void anInvalidEntryAlongsideAValidKeyBlocksTheWholeCost() {
        Cost cost = cost(keyEntry(key.getId(), 1), keyEntry("deleted_legacy_key", 1));
        give(2);
        assertTrue(crate.getFirstCost().isEmpty());
        assertFalse(open(cost));
    }

    @Test public void unknownEntryCannotDisappearLeavingAnApparentlyValidCost() {
        FileConfig config = new FileConfig(plugin.getDataFolder().toPath().resolve("unused-test-cost.yml").toFile());
        config.set("Cost.Enabled", true);
        config.set("Cost.Entries.0", keyEntry(key.getId(), 1));
        config.set("Cost.Entries.1.Type", "missing_after_migration");
        Cost damaged = Cost.read(config, "Cost", "test");
        crate.addCost(damaged);
        give(1);
        assertFalse(open(damaged));
        // A routine save and subsequent load must not discard the damaged requirement.
        damaged.write(config, "Saved");
        assertEquals("missing_after_migration", config.getString("Saved.Entries.1.Type"));
        Cost reloaded = Cost.read(config, "Saved", "test");
        crate.addCost(reloaded);
        assertFalse(open(reloaded));
    }
}
