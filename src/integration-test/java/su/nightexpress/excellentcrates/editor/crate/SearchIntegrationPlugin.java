package su.nightexpress.excellentcrates.editor.crate;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.runner.JUnitCore;

public class SearchIntegrationPlugin extends JavaPlugin {
    @Override public void onEnable() {
        getServer().getScheduler().runTask(this, () -> {
            var result = JUnitCore.runClasses(CrateSearchDialogTest.class, CrateSearchFixtureTest.class, KeyRequirementTest.class, ItemDataUpgradeTest.class);
            result.getFailures().forEach(failure -> getLogger().severe(failure.getTrace()));
            var crates = ((su.nightexpress.excellentcrates.CratesPlugin) getServer().getPluginManager().getPlugin("ExcellentCrates")).getCrateManager().getCrates();
            getLogger().info("KEY_AUDIT blocked=" + crates.stream().filter(crate -> crate.getFirstCost().isEmpty()).map(crate -> crate.getId()).sorted().toList());
            getLogger().info("SEARCH_INTEGRATION: tests=" + result.getRunCount() + " failures=" + result.getFailureCount());
        });
    }
}
