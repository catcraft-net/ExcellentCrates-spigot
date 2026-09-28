package su.nightexpress.excellentcrates.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HiddenComponentsFixTest {
    private static final int V26_2 = 4903;
    private static final int V26_3 = 5023;

    @Test
    public void renamesSwingAnimationAndDropsMapColor() {
        List<String> fixed = ItemDataUpgrade.HiddenComponentsFix.fix(
            List.of("minecraft:max_damage", "minecraft:swing_animation", "minecraft:map_color", "minecraft:trim"), V26_2, V26_3);
        assertEquals(List.of("minecraft:max_damage", "minecraft:attack_animation", "minecraft:interact_animation", "minecraft:trim"), fixed);
    }

    @Test
    public void acceptsNamesWithoutNamespace() {
        assertEquals(List.of("attack_range"),
            ItemDataUpgrade.HiddenComponentsFix.fix(List.of("attack_range", "map_color"), V26_2, V26_3));
    }

    @Test
    public void doesNotDuplicateExistingAnimationNames() {
        assertEquals(List.of("minecraft:attack_animation", "minecraft:interact_animation"),
            ItemDataUpgrade.HiddenComponentsFix.fix(List.of("minecraft:attack_animation", "minecraft:swing_animation"), V26_2, V26_3));
    }

    @Test
    public void leavesUnaffectedListsAndVersionsAlone() {
        assertNull(ItemDataUpgrade.HiddenComponentsFix.fix(List.of("minecraft:trim"), V26_2, V26_3));
        assertNull(ItemDataUpgrade.HiddenComponentsFix.fix(List.of("minecraft:map_color"), 4189, V26_2));
        assertNull(ItemDataUpgrade.HiddenComponentsFix.fix(List.of("minecraft:swing_animation"), V26_3, V26_3));
    }

    @Test
    public void appliesOnlyTheStepsThatAreCrossed() {
        // Between the two fixes: swing_animation was already split, map_color still exists.
        assertEquals(List.of("minecraft:swing_animation"),
            ItemDataUpgrade.HiddenComponentsFix.fix(List.of("minecraft:swing_animation", "minecraft:map_color"), 5007, 5008));
    }
}
