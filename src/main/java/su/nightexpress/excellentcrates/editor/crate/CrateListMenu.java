package su.nightexpress.excellentcrates.editor.crate;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.MenuType;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.config.Lang;
import su.nightexpress.excellentcrates.crate.CrateManager;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.CrateDialogs;
import su.nightexpress.excellentcrates.dialog.DialogRegistry;
import su.nightexpress.nightcore.bridge.dialog.DialogViewer;
import su.nightexpress.nightcore.locale.LangContainer;
import su.nightexpress.nightcore.locale.LangEntry;
import su.nightexpress.nightcore.locale.entry.IconLocale;
import su.nightexpress.nightcore.ui.menu.MenuViewer;
import su.nightexpress.nightcore.ui.dialog.Dialogs;
import su.nightexpress.nightcore.ui.menu.data.Filled;
import su.nightexpress.nightcore.ui.menu.data.MenuFiller;
import su.nightexpress.nightcore.ui.menu.item.MenuItem;
import su.nightexpress.nightcore.ui.menu.type.LinkedMenu;
import su.nightexpress.nightcore.util.bukkit.NightItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static su.nightexpress.excellentcrates.Placeholders.*;
import static su.nightexpress.nightcore.util.text.night.wrapper.TagWrappers.GREEN;

public class CrateListMenu extends LinkedMenu<CratesPlugin, CrateManager> implements Filled<Crate>, LangContainer, Listener {

    private static final IconLocale LOCALE_CRATE = LangEntry.iconBuilder("Editor.Button.Crates.Crate")
        .rawName(CRATE_NAME)
        .appendCurrent("Status", GENERIC_INSPECTION)
        .appendCurrent("ID", CRATE_ID).br()
        .appendClick("Click to open")
        .build();

    private static final IconLocale LOCALE_CREATION = LangEntry.iconBuilder("Editor.Button.Crates.Create")
        .accentColor(GREEN)
        .name("New Crate")
        .appendInfo("Use this button to create", "brand new crates!").br()
        .appendClick("Click to create")
        .build();

    private static final IconLocale LOCALE_SEARCH = LangEntry.iconBuilder("Editor.Button.Crates.Search")
        .name("Search Crates")
        .appendCurrent("Filter", "%search_query%")
        .appendCurrent("Matches", "%search_count%")
        .appendClick("Click to search by name or ID")
        .build();

    private static final IconLocale LOCALE_CLEAR = LangEntry.iconBuilder("Editor.Button.Crates.ClearSearch")
        .name("Clear Search").appendClick("Click to show all crates").build();

    private static final IconLocale LOCALE_EMPTY = LangEntry.iconBuilder("Editor.Button.Crates.NoResults")
        .name("No matching crates").appendInfo("Try a shorter name or clear the search.").build();

    private final DialogRegistry dialogs;
    private final CrateSearchDialog searchDialog;
    private final Map<UUID, String> queries = new HashMap<>();
    private final Map<UUID, Integer> pages = new HashMap<>();
    private final Map<UUID, DialogViewer> searchViewers = new HashMap<>();

    public CrateListMenu(@NotNull CratesPlugin plugin, @NotNull DialogRegistry dialogs) {
        super(plugin, MenuType.GENERIC_9X5, Lang.EDITOR_TITLE_CRATE_LIST.text());
        this.dialogs = dialogs;
        this.searchDialog = new CrateSearchDialog();
        this.plugin.injectLang(this);
        this.plugin.injectLang(this.searchDialog);
        this.plugin.getPluginManager().registerEvents(this, this.plugin);

        this.addItem(MenuItem.buildReturn(this, 40, (viewer, event) -> {
            this.queries.remove(viewer.getPlayer().getUniqueId());
            this.pages.remove(viewer.getPlayer().getUniqueId());
            this.runNextTick(() -> this.plugin.getEditorManager().openEditor(viewer.getPlayer()));
        }));

        this.addItem(MenuItem.buildNextPage(this, 44));
        this.addItem(MenuItem.buildPreviousPage(this, 36));
        this.addItem(MenuItem.background(Material.BLACK_STAINED_GLASS_PANE, IntStream.range(36, 45).toArray()));
        this.addItem(MenuItem.background(Material.GRAY_STAINED_GLASS_PANE, IntStream.range(0, 36).toArray()));

        this.addItem(Material.ANVIL, LOCALE_CREATION, 42, (viewer, event, manager) -> {
            Player player = viewer.getPlayer();
            this.dialogs.show(player, CrateDialogs.CRATE_CREATION, manager, () -> this.flush(player));
        });

        this.addItem(Material.MILK_BUCKET, LOCALE_CLEAR, 39, (viewer, event, manager) -> {
            Player player = viewer.getPlayer();
            this.queries.remove(player.getUniqueId());
            this.pages.remove(player.getUniqueId());
            this.runNextTick(() -> this.open(player, manager, next -> next.setPage(1)));
        });
    }

    @Override
    public boolean open(@NotNull Player player, @NotNull CrateManager manager) {
        return super.open(player, manager, viewer -> viewer.setPage(this.pages.getOrDefault(player.getUniqueId(), 1)));
    }

    private String query(Player player) {
        return this.queries.getOrDefault(player.getUniqueId(), "");
    }

    private List<Crate> matches(MenuViewer viewer) {
        return CrateSearch.find(this.getLink(viewer).getCrates(), Crate::getId, Crate::getName, this.query(viewer.getPlayer()));
    }

    private void search(MenuViewer viewer) {
        Player player = viewer.getPlayer();
        CrateManager manager = this.getLink(viewer);
        int oldPage = viewer.getPage();
        String oldQuery = this.query(player);
        this.runNextTick(() -> {
            if (!player.isOnline() || !this.plugin.isEnabled()) return;
            this.closeSearch(player);
            player.closeInventory();
            this.searchDialog.show(player, new CrateSearchDialog.Data(oldQuery, input -> {
                if (player.isOnline() && this.plugin.isEnabled()) {
                    String bounded = input.substring(0, Math.min(input.length(), 128));
                    this.queries.put(player.getUniqueId(), CrateSearch.normalize(bounded));
                }
            }), () -> {
                this.searchViewers.remove(player.getUniqueId());
                if (!player.isOnline() || !this.plugin.isEnabled()) return;
                int page = oldQuery.equals(this.query(player)) ? oldPage : 1;
                this.pages.put(player.getUniqueId(), page);
                this.runNextTick(() -> {
                    if (player.isOnline() && this.plugin.isEnabled()) {
                        this.open(player, manager, next -> next.setPage(page));
                    }
                });
            });
            DialogViewer dialogViewer = Dialogs.getViewer(player);
            if (dialogViewer != null) this.searchViewers.put(player.getUniqueId(), dialogViewer);
        });
    }

    private void closeSearch(Player player) {
        DialogViewer owned = this.searchViewers.remove(player.getUniqueId());
        // Another plugin or editor may have replaced our dialog; only close the viewer we own.
        if (owned != null && Dialogs.getViewer(player) == owned) Dialogs.exitDialog(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.queries.remove(event.getPlayer().getUniqueId());
        this.pages.remove(event.getPlayer().getUniqueId());
        this.closeSearch(event.getPlayer());
    }

    @Override
    public void clear() {
        List.copyOf(this.searchViewers.values()).forEach(viewer -> this.closeSearch(viewer.getPlayer()));
        super.clear();
        this.queries.clear();
        this.pages.clear();
        HandlerList.unregisterAll(this);
    }

    @Override
    @NotNull
    public MenuFiller<Crate> createFiller(@NotNull MenuViewer viewer) {
        var autoFill = MenuFiller.builder(this);

        autoFill.setSlots(IntStream.range(0, 36).toArray());
        autoFill.setItems(this.matches(viewer));
        autoFill.setItemCreator(crate -> {
            return NightItem.fromItemStack(crate.getRawItemStack())
                .localized(LOCALE_CRATE)
                .replacement(replacer -> replacer
                    .replace(GENERIC_INSPECTION, () -> Lang.inspection(Lang.INSPECTIONS_GENERIC_OVERVIEW, !crate.hasProblems()))
                    .replace(crate.replacePlaceholders())
                );
        });
        autoFill.setItemClick(crate -> (viewer1, event) -> {
            this.pages.put(viewer1.getPlayer().getUniqueId(), viewer1.getPage());
            this.runNextTick(() -> this.plugin.getEditorManager().openOptionsMenu(viewer1.getPlayer(), crate));
        });

        return autoFill.build();
    }

    @Override
    protected void onPrepare(@NotNull MenuViewer viewer, @NotNull InventoryView view) {
        this.autoFill(viewer);
        List<Crate> matches = this.matches(viewer);
        String query = this.query(viewer.getPlayer());
        viewer.addItem(NightItem.fromType(Material.COMPASS).localized(LOCALE_SEARCH)
            .replacement(replacer -> replacer.replace("%search_query%", query.isEmpty() ? "*" : query)
                .replace("%search_count%", String.valueOf(matches.size())))
            .toMenuItem().setPriority(MenuItem.HIGH_PRIORITY).setSlots(38)
            .setHandler((next, event) -> this.search(next)).build());
        if (matches.isEmpty()) {
            viewer.addItem(NightItem.fromType(Material.BARRIER).localized(LOCALE_EMPTY)
                .toMenuItem().setPriority(MenuItem.HIGH_PRIORITY).setSlots(13).build());
        }
    }

    @Override
    protected void onReady(@NotNull MenuViewer viewer, @NotNull Inventory inventory) {

    }
}
