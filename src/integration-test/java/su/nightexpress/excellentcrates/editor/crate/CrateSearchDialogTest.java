package su.nightexpress.excellentcrates.editor.crate;

import org.bukkit.entity.Player;
import org.junit.Test;
import su.nightexpress.nightcore.bridge.common.NightNbtHolder;
import su.nightexpress.nightcore.bridge.dialog.DialogViewer;
import su.nightexpress.nightcore.bridge.dialog.wrap.WrappedDialog;
import su.nightexpress.nightcore.ui.dialog.build.DialogActions;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class CrateSearchDialogTest {
    private static final class Viewer implements DialogViewer {
        private final WrappedDialog dialog;
        private final List<String> events;
        Viewer(WrappedDialog dialog, List<String> events) { this.dialog = dialog; this.events = events; }
        public void close() {}
        public void closeFully() {}
        public void callback() { events.add("return"); }
        public Player getPlayer() { return null; }
        public WrappedDialog getDialog() { return dialog; }
        public Runnable getCallback() { return this::callback; }
    }

    @Test public void submitAppliesQueryBeforeReturningToList() {
        List<String> events = new ArrayList<>();
        WrappedDialog dialog = new CrateSearchDialog().create(null,
            new CrateSearchDialog.Data("old", query -> events.add("apply:" + query)));
        dialog.responseHandlers().get(DialogActions.OK).handle(new Viewer(dialog, events), null,
            NightNbtHolder.builder().put("query", "sum").build());
        assertEquals(List.of("apply:sum", "return"), events);
    }

    @Test public void backDoesNotOverwriteThePreviousQuery() {
        List<String> events = new ArrayList<>();
        WrappedDialog dialog = new CrateSearchDialog().create(null,
            new CrateSearchDialog.Data("old", query -> events.add("apply:" + query)));
        dialog.responseHandlers().get(DialogActions.BACK).handle(new Viewer(dialog, events), null, null);
        assertEquals(List.of("return"), events);
    }

    @Test public void missingPayloadReturnsWithoutChangingTheFilter() {
        List<String> events = new ArrayList<>();
        WrappedDialog dialog = new CrateSearchDialog().create(null,
            new CrateSearchDialog.Data("old", query -> events.add("apply:" + query)));
        dialog.responseHandlers().get(DialogActions.OK).handle(new Viewer(dialog, events), null, null);
        assertEquals(List.of("return"), events);
    }
}
