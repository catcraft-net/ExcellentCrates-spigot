package su.nightexpress.excellentcrates.command;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.config.Perms;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.util.pos.WorldPos;
import su.nightexpress.nightcore.commands.Commands;
import su.nightexpress.nightcore.commands.builder.HubNodeBuilder;
import su.nightexpress.nightcore.commands.context.CommandContext;
import su.nightexpress.nightcore.commands.context.ParsedArguments;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CatCraft: /crate retire <new> <old> gives the new crate the old crate's block(s) and moves the old crate to the next
 * free ender chest from Crate.Retire.Slots, live (no reload). /crate retire undo puts both back.
 */
public class RetireCommand {

    public static final String NEW_CRATE = "new";
    public static final String OLD_CRATE = "old";

    private record Snapshot(String newId, List<WorldPos> newPositions, String oldId, List<WorldPos> oldPositions) {}

    private final CratesPlugin plugin;
    private Snapshot last;

    public RetireCommand(@NotNull CratesPlugin plugin) {
        this.plugin = plugin;
    }

    public void load(@NotNull HubNodeBuilder nodeBuilder) {
        nodeBuilder.branch(Commands.hub("retire")
            .description("Move a crate to the crates warp and give its spot to a new crate.")
            .permission(Perms.COMMAND_RETIRE)
            .branch(Commands.literal("undo")
                .description("Undo the last /crate retire.")
                .permission(Perms.COMMAND_RETIRE)
                .executes((context, arguments) -> this.undo(context.getSender()))
            )
            .branch(Commands.literal("next")
                .description("Show where the next retired crate would go.")
                .permission(Perms.COMMAND_RETIRE)
                .executes((context, arguments) -> this.showNext(context.getSender()))
            )
            .branch(Commands.literal("move")
                .description("Give the old crate's spot to the new crate and move the old one to the crates warp.")
                .permission(Perms.COMMAND_RETIRE)
                .withArguments(
                    Commands.argument(NEW_CRATE, (context, string) -> this.crate(string)).suggestions((reader, context) -> plugin.getCrateManager().getCrateIds()),
                    Commands.argument(OLD_CRATE, (context, string) -> this.crate(string)).suggestions((reader, context) -> this.placedCrateIds())
                )
                .executes(this::retire)
            )
        );

        // CatCraft: pick a crate's opening from the full list (tab-completes every loaded opening), live.
        nodeBuilder.branch(Commands.literal("opening")
            .description("Set the opening animation a crate uses.")
            .permission(Perms.COMMAND_RETIRE)
            .withArguments(
                Commands.argument(NEW_CRATE, (context, string) -> this.crate(string)).suggestions((reader, context) -> plugin.getCrateManager().getCrateIds()),
                Commands.argument("opening", (context, string) -> {
                    if (!string.equalsIgnoreCase("none") && plugin.getOpeningManager().getProviderById(string) == null)
                        throw su.nightexpress.nightcore.commands.exceptions.CommandSyntaxException.custom(su.nightexpress.excellentcrates.config.Lang.ERROR_COMMAND_INVALID_CRATE_ARGUMENT);
                    return string;
                }).suggestions((reader, context) -> {
                    List<String> ids = new ArrayList<>(plugin.getOpeningManager().getProviderIds());
                    java.util.Collections.sort(ids);
                    ids.add(0, "none");
                    return ids;
                })
            )
            .executes((context, arguments) -> {
                Crate crate = arguments.get(NEW_CRATE, Crate.class);
                String opening = arguments.get("opening", String.class);
                if (opening.equalsIgnoreCase("none")) {
                    crate.setOpeningEnabled(false);
                } else {
                    crate.setOpeningId(opening);
                    crate.setOpeningEnabled(true);
                }
                crate.saveForce();
                context.getSender().sendMessage("§a" + crate.getId() + " §7now opens with §f" + (opening.equalsIgnoreCase("none") ? "no animation" : opening) + "§7.");
                return true;
            })
        );

        // CatCraft: change a crate's particle and effect shape, live, with tab complete.
        nodeBuilder.branch(Commands.literal("particle")
            .description("Set the particle a crate's effect uses.")
            .permission(Perms.COMMAND_RETIRE)
            .withArguments(
                Commands.argument(NEW_CRATE, (context, string) -> this.crate(string)).suggestions((reader, context) -> plugin.getCrateManager().getCrateIds()),
                Commands.argument("particle", (context, string) -> {
                    org.bukkit.Particle particle = this.particle(string);
                    if (particle == null) throw su.nightexpress.nightcore.commands.exceptions.CommandSyntaxException.custom(su.nightexpress.excellentcrates.config.Lang.ERROR_COMMAND_INVALID_CRATE_ARGUMENT);
                    return particle;
                }).suggestions((reader, context) -> this.particleNames())
            )
            .executes((context, arguments) -> {
                Crate crate = arguments.get(NEW_CRATE, Crate.class);
                org.bukkit.Particle particle = arguments.get("particle", org.bukkit.Particle.class);
                crate.setEffectParticle(su.nightexpress.nightcore.util.wrapper.UniParticle.of(particle));
                crate.setEffectEnabled(true);
                if (crate.getEffect().isDummy()) crate.setEffectType(su.nightexpress.excellentcrates.crate.effect.EffectId.HELIX);
                crate.saveForce();
                context.getSender().sendMessage("§a" + crate.getId() + " §7now uses §f" + particle.name().toLowerCase() + " §7particles (§f" + crate.getEffect().getId() + "§7).");
                return true;
            })
        );

        nodeBuilder.branch(Commands.literal("effect")
            .description("Set the shape of a crate's particle effect.")
            .permission(Perms.COMMAND_RETIRE)
            .withArguments(
                Commands.argument(NEW_CRATE, (context, string) -> this.crate(string)).suggestions((reader, context) -> plugin.getCrateManager().getCrateIds()),
                Commands.argument("effect", (context, string) -> {
                    String id = string.toLowerCase();
                    if (!id.equals("none") && su.nightexpress.excellentcrates.registry.CratesRegistries.getEffects().stream().noneMatch(e -> e.getId().equalsIgnoreCase(id)))
                        throw su.nightexpress.nightcore.commands.exceptions.CommandSyntaxException.custom(su.nightexpress.excellentcrates.config.Lang.ERROR_COMMAND_INVALID_CRATE_ARGUMENT);
                    return id;
                }).suggestions((reader, context) -> {
                    List<String> ids = new ArrayList<>();
                    su.nightexpress.excellentcrates.registry.CratesRegistries.getEffects().forEach(e -> { if (!e.isDummy()) ids.add(e.getId()); });
                    java.util.Collections.sort(ids);
                    ids.add(0, "none");
                    return ids;
                })
            )
            .executes((context, arguments) -> {
                Crate crate = arguments.get(NEW_CRATE, Crate.class);
                String id = arguments.get("effect", String.class);
                if (id.equals("none")) {
                    crate.setEffectEnabled(false);
                } else {
                    crate.setEffectType(id);
                    crate.setEffectEnabled(true);
                }
                crate.saveForce();
                context.getSender().sendMessage("§a" + crate.getId() + " §7effect is now §f" + (id.equals("none") ? "off" : id) + "§7.");
                return true;
            })
        );
    }

    @Nullable
    private org.bukkit.Particle particle(@NotNull String name) {
        for (org.bukkit.Particle particle : org.bukkit.Particle.values()) {
            if (particle.name().equalsIgnoreCase(name) && this.isPlainParticle(particle)) return particle;
        }
        return null;
    }

    private boolean isPlainParticle(@NotNull org.bukkit.Particle particle) {
        return su.nightexpress.excellentcrates.util.CrateUtils.isSupportedParticle(particle) && particle.getDataType() == Void.class;
    }

    @NotNull
    private List<String> particleNames() {
        List<String> names = new ArrayList<>();
        for (org.bukkit.Particle particle : org.bukkit.Particle.values()) {
            if (this.isPlainParticle(particle)) names.add(particle.name().toLowerCase());
        }
        java.util.Collections.sort(names);
        return names;
    }

    @NotNull
    private Crate crate(@NotNull String id) throws su.nightexpress.nightcore.commands.exceptions.CommandSyntaxException {
        Crate crate = plugin.getCrateManager().getCrateById(id);
        if (crate == null) throw su.nightexpress.nightcore.commands.exceptions.CommandSyntaxException.custom(su.nightexpress.excellentcrates.config.Lang.ERROR_COMMAND_INVALID_CRATE_ARGUMENT);
        return crate;
    }

    @NotNull
    private List<String> placedCrateIds() {
        List<String> ids = new ArrayList<>();
        plugin.getCrateManager().getCrates().forEach(crate -> {
            if (!crate.getBlockPositions().isEmpty()) ids.add(crate.getId());
        });
        return ids;
    }

    private boolean retire(@NotNull CommandContext context, @NotNull ParsedArguments arguments) {
        CommandSender sender = context.getSender();
        Crate newCrate = arguments.get(NEW_CRATE, Crate.class);
        Crate oldCrate = arguments.get(OLD_CRATE, Crate.class);

        if (newCrate == oldCrate) return this.fail(sender, "Pick two different crates.");
        if (oldCrate.getBlockPositions().isEmpty()) return this.fail(sender, oldCrate.getId() + " isn't placed anywhere.");
        if (!newCrate.getBlockPositions().isEmpty()) return this.fail(sender, newCrate.getId() + " is already placed at " + this.describe(newCrate.getBlockPositions()) + ".");

        WorldPos slot = this.nextSlot(sender);
        if (slot == null) return false;

        List<WorldPos> oldPositions = new ArrayList<>(oldCrate.getBlockPositions());
        this.last = new Snapshot(newCrate.getId(), List.of(), oldCrate.getId(), oldPositions);

        this.place(oldCrate, List.of(slot));
        this.place(newCrate, oldPositions);

        sender.sendMessage("§a" + newCrate.getId() + " §7now sits at §f" + this.describe(oldPositions) + "§7; §a" + oldCrate.getId() + " §7moved to §f" + this.describe(List.of(slot)) + "§7. §8(/crate retire undo)");
        return true;
    }

    private boolean undo(@NotNull CommandSender sender) {
        Snapshot snap = this.last;
        if (snap == null) return this.fail(sender, "Nothing to undo since the last restart.");

        Crate newCrate = plugin.getCrateManager().getCrateById(snap.newId());
        Crate oldCrate = plugin.getCrateManager().getCrateById(snap.oldId());
        if (newCrate == null || oldCrate == null) return this.fail(sender, "One of the crates no longer exists.");

        this.place(newCrate, snap.newPositions());
        this.place(oldCrate, snap.oldPositions());
        this.last = null;

        sender.sendMessage("§a" + oldCrate.getId() + " §7is back at §f" + this.describe(snap.oldPositions()) + "§7; §a" + newCrate.getId() + " §7is no longer placed.");
        return true;
    }

    private boolean showNext(@NotNull CommandSender sender) {
        WorldPos slot = this.nextSlot(sender);
        if (slot == null) return false;
        sender.sendMessage("§7Next free crates-warp chest: §f" + this.describe(List.of(slot)));
        return true;
    }

    private void place(@NotNull Crate crate, @NotNull List<WorldPos> positions) {
        crate.removeHologram();
        crate.clearBlockPositions();
        for (WorldPos pos : positions) {
            Location location = pos.toLocation();
            if (location != null) crate.addBlockPosition(location);
        }
        crate.saveForce();
        if (!positions.isEmpty()) crate.createHologram();
    }

    /** First slot from Crate.Retire.Slots that no crate uses and that holds an ender chest. */
    @Nullable
    private WorldPos nextSlot(@NotNull CommandSender sender) {
        List<String> slots = Config.CRATE_RETIRE_SLOTS.get();
        if (slots.isEmpty()) {
            this.fail(sender, "No crates-warp slots set: add them to Crate.Retire.Slots in config.yml (\"x,y,z,world\", in fill order).");
            return null;
        }

        Set<WorldPos> used = new HashSet<>();
        plugin.getCrateManager().getCrates().forEach(crate -> used.addAll(crate.getBlockPositions()));

        for (String raw : slots) {
            WorldPos pos = WorldPos.deserialize(raw);
            if (used.contains(pos)) continue;
            Block block = pos.toBlock();
            if (block == null || block.getType() != Material.ENDER_CHEST) continue;
            return pos;
        }
        this.fail(sender, "Every slot in Crate.Retire.Slots is taken or isn't an ender chest.");
        return null;
    }

    @NotNull
    private String describe(@NotNull Iterable<WorldPos> positions) {
        List<String> parts = new ArrayList<>();
        positions.forEach(pos -> parts.add(pos.getX() + " " + pos.getY() + " " + pos.getZ() + " (" + pos.getWorldName() + ")"));
        return String.join(", ", parts);
    }

    private boolean fail(@NotNull CommandSender sender, @NotNull String message) {
        sender.sendMessage("§c" + message);
        return false;
    }
}
