package su.nightexpress.excellentcrates.editor.crate;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.Test;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.nightcore.ui.menu.MenuViewer;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/** Run on a disposable Paper server with the supplied 102-crate archive copied into its plugin folder. */
public class CrateSearchFixtureTest {
    private CratesPlugin plugin() { return (CratesPlugin) Bukkit.getPluginManager().getPlugin("ExcellentCrates"); }

    private List<Crate> find(String query) {
        return CrateSearch.find(plugin().getCrateManager().getCrates(), Crate::getId, Crate::getName, query);
    }

    @Test public void archiveLoadsAllCratesAndSummerSearchFindsTheExpectedSet() {
        assertEquals(102, find("").size());
        assertEquals(Set.of("summer2022", "summer_2023", "summer_2024", "summer2025", "summer2026", "seashell_summer"),
            find("sum").stream().map(Crate::getId).collect(Collectors.toSet()));
    }

    @Test public void actualGradientAndPerLetterColourNamesAreSearchable() {
        assertEquals(List.of("animecrate2026"), find("anime crate 2026").stream().map(Crate::getId).toList());
        assertEquals(List.of("summer_2024"), find("summer 2024").stream().map(Crate::getId).toList());
        assertTrue(find("hallowen").stream().anyMatch(crate -> crate.getId().equals("halloween2024")));
    }

    @SuppressWarnings("unchecked")
    @Test public void realMenuFillerSeparatesPlayersClampsPagesAndShowsNoResults() throws Exception {
        var field = plugin().getEditorManager().getClass().getDeclaredField("crateListMenu");
        field.setAccessible(true);
        CrateListMenu menu = (CrateListMenu) field.get(plugin().getEditorManager());
        var queriesField = CrateListMenu.class.getDeclaredField("queries");
        queriesField.setAccessible(true);
        Map<UUID, String> queries = (Map<UUID, String>) queriesField.get(menu);
        Player first = player("First");
        Player second = player("Second");
        try {
            menu.getCache().set(first, plugin().getCrateManager());
            menu.getCache().set(second, plugin().getCrateManager());
            queries.put(first.getUniqueId(), "sum");
            MenuViewer a = new MenuViewer(menu, first);
            a.setPage(3);
            menu.createFiller(a).addItems(a);
            assertEquals(1, a.getPage());
            assertEquals(6, a.getItems().size());
            MenuViewer b = new MenuViewer(menu, second);
            menu.createFiller(b).addItems(b);
            assertEquals(3, b.getPages());
            assertEquals(36, b.getItems().size());
            queries.put(first.getUniqueId(), "zzzzunrelated");
            a.removeItems();
            var prepare = CrateListMenu.class.getDeclaredMethod("onPrepare", MenuViewer.class, org.bukkit.inventory.InventoryView.class);
            prepare.setAccessible(true);
            prepare.invoke(menu, a, null);
            assertTrue(a.getItems().stream().anyMatch(item -> item.getItem().getItemStack().getType() == Material.BARRIER));
            assertEquals(1, a.getPages());
        } finally {
            queries.remove(first.getUniqueId());
            queries.remove(second.getUniqueId());
            menu.getCache().clear(first);
            menu.getCache().clear(second);
        }
    }

    private static Player player(String name) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName", "toString" -> name;
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }
}
