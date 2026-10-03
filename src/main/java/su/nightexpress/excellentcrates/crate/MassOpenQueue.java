package su.nightexpress.excellentcrates.crate;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.cost.Cost;
import su.nightexpress.excellentcrates.crate.impl.CrateSource;
import su.nightexpress.excellentcrates.crate.impl.OpenOptions;
import su.nightexpress.excellentcrates.opening.summary.MassOpenSummary;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * CatCraft: one server-wide queue for mass openings. Each tick it opens at most
 * {@code Crate.MassOpening.Global_Per_Tick} crates in total, taking turns between players (up to
 * {@code Per_Tick} each), so many players mass-opening at once can't stack up into a lag spike.
 */
final class MassOpenQueue {

    private static final class Job {
        final Player      player;
        final CrateSource source;
        final OpenOptions options;
        final Cost        cost;
        final boolean     summary;
        int               remaining;

        Job(Player player, CrateSource source, OpenOptions options, Cost cost, int remaining, boolean summary) {
            this.player = player;
            this.source = source;
            this.options = options;
            this.cost = cost;
            this.remaining = remaining;
            this.summary = summary;
        }
    }

    private final CratesPlugin plugin;
    private final CrateManager manager;
    private final Deque<Job>   jobs = new ArrayDeque<>();
    private BukkitTask         task;

    MassOpenQueue(@NotNull CratesPlugin plugin, @NotNull CrateManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    void submit(@NotNull Player player, @NotNull CrateSource source, @NotNull OpenOptions options, @Nullable Cost cost, int openings) {
        boolean summary = Config.MASS_OPENING_SUMMARY.get();
        MassOpenSummary.markStarted(player);
        if (summary) this.plugin.getOpeningManager().startCollecting(player);
        this.jobs.add(new Job(player, source, options, cost, openings, summary));
        if (this.task == null) {
            this.task = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, this::tick, 0L, 1L);
        }
    }

    private void tick() {
        int budget = Math.max(1, Config.MASS_OPENING_GLOBAL_PER_TICK.get());
        int perPlayer = Math.max(1, Config.MASS_OPENING_PER_TICK.get());

        // One round over the players queued at the start of this tick, until the budget is used up.
        for (int turns = this.jobs.size(); turns > 0 && budget > 0 && !this.jobs.isEmpty(); turns--) {
            Job job = this.jobs.poll();
            boolean done = !job.player.isOnline();
            for (int count = 0; count < perPlayer && budget > 0 && !done; count++) {
                budget--;
                if (!this.manager.openCrate(job.player, job.source, job.options, job.cost)) done = true;
                else if (--job.remaining <= 0) done = true;
            }
            if (done) this.finish(job);
            else this.jobs.add(job);
        }

        if (this.jobs.isEmpty() && this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    private void finish(@NotNull Job job) {
        MassOpenSummary.markFinished(job.player);
        if (!job.summary) return;
        List<Reward> won = this.plugin.getOpeningManager().stopCollecting(job.player);
        if (!won.isEmpty() && job.player.isOnline()) {
            new MassOpenSummary(this.plugin, job.player, job.source.getCrate(), won).open();
        }
    }

    /** Ends every queued mass opening (rewards already given stay given). */
    void shutdown() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        while (!this.jobs.isEmpty()) {
            Job job = this.jobs.poll();
            MassOpenSummary.markFinished(job.player);
            if (job.summary) this.plugin.getOpeningManager().stopCollecting(job.player);
        }
    }
}
