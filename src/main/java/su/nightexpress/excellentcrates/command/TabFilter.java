package su.nightexpress.excellentcrates.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CatCraft: nightcore sends every subcommand / suggestion no matter what was typed ("/crate par" got all 15
 * subcommands), so the client never narrows down. Keep only the suggestions that start with the word being typed.
 */
public class TabFilter implements Listener {

    private final Plugin plugin;

    public TabFilter(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTab(TabCompleteEvent event) {
        String buffer = event.getBuffer();
        if (!buffer.startsWith("/") && event.getSender() instanceof org.bukkit.entity.Player) return;
        String line = buffer.startsWith("/") ? buffer.substring(1) : buffer;
        int space = line.indexOf(' ');
        if (space < 0) return;

        String label = line.substring(0, space).toLowerCase(Locale.ROOT);
        Command command = Bukkit.getCommandMap().getCommand(label);
        if (!(command instanceof PluginIdentifiableCommand owned) || owned.getPlugin() != this.plugin) return;

        String typed = line.substring(line.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT);
        if (typed.isEmpty()) return;

        List<String> kept = new ArrayList<>();
        for (String completion : event.getCompletions()) {
            if (completion.toLowerCase(Locale.ROOT).startsWith(typed)) kept.add(completion);
        }
        event.setCompletions(kept);
    }
}
