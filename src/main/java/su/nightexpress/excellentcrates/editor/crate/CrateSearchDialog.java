package su.nightexpress.excellentcrates.editor.crate;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.dialog.Dialog;
import su.nightexpress.nightcore.bridge.dialog.wrap.WrappedDialog;
import su.nightexpress.nightcore.locale.LangEntry;
import su.nightexpress.nightcore.locale.entry.TextLocale;
import su.nightexpress.nightcore.ui.dialog.Dialogs;
import su.nightexpress.nightcore.ui.dialog.build.*;

import java.util.function.Consumer;

public class CrateSearchDialog extends Dialog<CrateSearchDialog.Data> {
    private static final TextLocale TITLE = LangEntry.builder("Dialog.Crate.Search.Title").text("Search Crates");
    private static final TextLocale INPUT = LangEntry.builder("Dialog.Crate.Search.Input").text("Crate name or ID");
    private static final TextLocale BODY = LangEntry.builder("Dialog.Crate.Search.Body")
        .text("Type a few letters of a crate name or ID. Small typos are accepted for longer names. Leave blank to show all crates.");

    public record Data(String query, Consumer<String> apply) {}

    @Override
    @NotNull
    public WrappedDialog create(@NotNull Player player, @NotNull Data data) {
        return Dialogs.create(builder -> {
            builder.base(DialogBases.builder(TITLE)
                .body(DialogBodies.plainMessage(BODY))
                .inputs(DialogInputs.text("query", INPUT).initial(data.query()).maxLength(128).build())
                .build());
            builder.type(DialogTypes.multiAction(DialogButtons.ok()).exitAction(DialogButtons.back()).build());
            builder.handleResponse(DialogActions.OK, (viewer, identifier, holder) -> {
                if (holder != null) data.apply().accept(holder.getText("query", data.query()));
                viewer.callback();
            });
            builder.handleResponse(DialogActions.BACK, (viewer, identifier, holder) -> viewer.callback());
        });
    }
}
