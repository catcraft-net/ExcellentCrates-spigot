package su.nightexpress.excellentcrates.pity;

import org.junit.Test;
import su.nightexpress.excellentcrates.crate.pity.PityRoll;
import su.nightexpress.excellentcrates.crate.pity.PitySettings;
import su.nightexpress.excellentcrates.crate.reward.RewardRoll;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * CatCraft: checks of the plain pity rules and the weighted roll the plugin uses in play.
 * The Eldritch 2026 numbers are used: 33 common rewards, Ultra Legendaries 0.7 + 0.6 + 0.6 of 100.
 */
public class PityRollTest {

    record R(String id, double weight, boolean ultra) {}

    private static List<R> eldritch() {
        List<R> list = new ArrayList<>();
        list.add(new R("wings_of_the_abyss", 0.7, true));
        list.add(new R("eye_of_the_abyss", 0.6, true));
        list.add(new R("gaze_of_the_dreamer", 0.6, true));
        double rest = 100 - 1.9;
        for (int i = 0; i < 30; i++) list.add(new R("filler" + i, rest / 30, false));
        return list;
    }

    @Test
    public void ultraTextDetection() {
        assertTrue(PityRoll.isUltraText("<bold><c:#FCC201>   ULTRA-LEGENDARY"));
        assertTrue(PityRoll.isUltraText("&6&lULTRA LEGENDARY"));
        assertTrue(PityRoll.isUltraText("<#6A2FC4>U</#6A2FC4><#7034CB>L</#7034CB><#7739D1>T</#7739D1>RA-LEGENDARY"));
        assertTrue(PityRoll.isUltraText("§x§6§a§2§f§c§4U§x§7§0§3§4§c§bLTRA-LEGENDARY"));
        assertFalse(PityRoll.isUltraText("<gold>LEGENDARY"));
        assertFalse(PityRoll.isUltraText("Ultra rare drop"));
        assertFalse(PityRoll.isUltraDescription(List.of("<white>Shaped like the fins of", "<dark_gray>———")));
    }

    @Test
    public void settingsChances() {
        PitySettings s = new PitySettings("monthly", 120, 150, 0.03);
        assertEquals(0D, s.pityChance(1), 1e-9);
        assertEquals(0D, s.pityChance(119), 1e-9);
        assertEquals(0.03, s.pityChance(120), 1e-9);
        assertEquals(0.06, s.pityChance(121), 1e-9);
        assertEquals(0.90, s.pityChance(149), 1e-9);
        assertEquals(1D, s.pityChance(150), 1e-9);
        assertEquals(1D, s.pityChance(400), 1e-9);
        assertEquals(150, s.keysLeft(0));
        assertEquals(1, s.keysLeft(149));
        assertEquals(0, s.keysLeft(150));
        assertEquals(0, s.keysLeft(999));
        assertFalse(new PitySettings("none", 120, 150, 0.03).isEnabled());
        assertFalse(new PitySettings("", 120, 150, 0.03).isEnabled());
        assertTrue(s.isEnabled());
    }

    @Test
    public void hardCapGuaranteesUltraAndResets() {
        PitySettings s = new PitySettings("monthly", 120, 150, 0.03);
        List<R> rewards = eldritch();
        List<R> ultras = rewards.stream().filter(R::ultra).toList();
        Random random = new Random(1);
        for (int i = 0; i < 1000; i++) {
            PityRoll.Result<R> result = PityRoll.roll(s, 149, ultras, R::weight, R::ultra,
                () -> RewardRoll.weighted(rewards, R::weight, random), random);
            assertTrue(result.ultra());
            assertEquals(PityRoll.Source.PITY_HARD, result.source());
            assertEquals(0, result.newCount());
        }
    }

    @Test
    public void normalZoneDoesNotTouchTheRoll() {
        PitySettings s = new PitySettings("monthly", 120, 150, 0.03);
        List<R> rewards = eldritch();
        List<R> ultras = rewards.stream().filter(R::ultra).toList();
        Random random = new Random(7);
        int n = 2_000_000, ultrasHit = 0;
        for (int i = 0; i < n; i++) {
            PityRoll.Result<R> result = PityRoll.roll(s, 0, ultras, R::weight, R::ultra,
                () -> RewardRoll.weighted(rewards, R::weight, random), random);
            assertEquals(PityRoll.Source.NORMAL, result.source());
            if (result.ultra()) ultrasHit++;
        }
        double rate = 100D * ultrasHit / n;
        System.out.printf("UL rate at counter 0: %.3f%% (config 1.900%%)%n", rate);
        assertEquals(1.9, rate, 0.05);
    }

    @Test
    public void softZoneRaisesTheRate() {
        PitySettings s = new PitySettings("monthly", 120, 150, 0.03);
        List<R> rewards = eldritch();
        List<R> ultras = rewards.stream().filter(R::ultra).toList();
        Random random = new Random(11);
        int n = 1_000_000, ultrasHit = 0;
        for (int i = 0; i < n; i++) {
            PityRoll.Result<R> result = PityRoll.roll(s, 130, ultras, R::weight, R::ultra,
                () -> RewardRoll.weighted(rewards, R::weight, random), random);
            if (result.ultra()) ultrasHit++;
        }
        double rate = 100D * ultrasHit / n;
        // key 131: pity chance 0.03 * 12 = 36%, plus the normal 1.9% on the misses: 36 + 0.64 * 1.9 = 37.2%
        System.out.printf("UL rate at counter 130: %.2f%% (expected 37.2%%)%n", rate);
        assertEquals(37.216, rate, 0.3);
    }

    @Test
    public void weightedMatchesConfigPercentages() {
        List<R> rewards = eldritch();
        Random random = new Random(3);
        int n = 2_000_000, wings = 0;
        for (int i = 0; i < n; i++) {
            if (RewardRoll.weighted(rewards, R::weight, random).id().equals("wings_of_the_abyss")) wings++;
        }
        assertEquals(0.7, 100D * wings / n, 0.03);
    }

    @Test
    public void rarityThenWeight() {
        record RR(String id, String rarity, double weight) {}
        List<RR> rewards = List.of(new RR("a", "common", 1), new RR("b", "common", 1), new RR("c", "legendary", 1));
        java.util.Map<String, Double> rarityWeights = java.util.Map.of("common", 50D, "legendary", 5D);
        Random random = new Random(5);
        int n = 1_000_000, c = 0;
        for (int i = 0; i < n; i++) {
            if (RewardRoll.rarityThenWeight(rewards, RR::rarity, rarityWeights::get, RR::weight, random).id().equals("c")) c++;
        }
        // legendary 5 of 55 = 9.09%, and c is the only legendary reward
        assertEquals(9.09, 100D * c / n, 0.1);
    }
}
