package su.nightexpress.excellentcrates.crate.pity;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.nightcore.config.FileConfig;

/**
 * CatCraft: a crate's pity settings ({@code Pity:} block in the crate file).
 * <ul>
 *   <li>{@code Group}: counter shared by every crate with the same group (monthly crates all use
 *       {@code monthly}). Empty or {@code none} = pity off for this crate; it never touches any counter.</li>
 *   <li>{@code Soft}: from this many dry keys on, each key gets an extra chance of an Ultra Legendary.</li>
 *   <li>{@code Hard}: this many dry keys guarantee an Ultra Legendary on the next key.</li>
 *   <li>{@code Step}: the extra chance per key past Soft, as a fraction (0.03 = 3% at Soft, 6% at Soft+1...).</li>
 * </ul>
 */
public record PitySettings(@NotNull String group, int soft, int hard, double step) {

    public static final String NONE = "none";

    public static final PitySettings OFF = new PitySettings("", 120, 150, 0.03D);

    public PitySettings {
        group = normalizeGroup(group);
        soft = Math.max(1, soft);
        hard = Math.max(soft, hard);
        step = Math.max(0D, step);
    }

    @NotNull
    public static String normalizeGroup(@Nullable String group) {
        if (group == null) return "";
        String trimmed = group.trim().toLowerCase();
        return trimmed.equals(NONE) ? "" : trimmed;
    }

    @NotNull
    public static PitySettings read(@NotNull FileConfig config, @NotNull String path) {
        if (!config.contains(path)) return OFF;

        String group = config.getString(path + ".Group", "");
        int soft = config.getInt(path + ".Soft", OFF.soft());
        int hard = config.getInt(path + ".Hard", OFF.hard());
        double step = config.getDouble(path + ".Step", OFF.step());
        return new PitySettings(group, soft, hard, step);
    }

    public void write(@NotNull FileConfig config, @NotNull String path) {
        config.set(path + ".Group", this.group.isEmpty() ? NONE : this.group);
        config.set(path + ".Soft", this.soft);
        config.set(path + ".Hard", this.hard);
        config.set(path + ".Step", this.step);
    }

    public boolean isEnabled() {
        return !this.group.isEmpty();
    }

    /**
     * The chance (0..1) that key number {@code keyNumber} (counter + 1) is turned into a guaranteed
     * Ultra Legendary before the normal roll: 0 below Soft, {@code Step * (keyNumber - Soft + 1)} from
     * Soft on, and 1 from Hard on.
     */
    public double pityChance(int keyNumber) {
        if (!this.isEnabled()) return 0D;
        if (keyNumber >= this.hard) return 1D;
        if (keyNumber < this.soft) return 0D;
        return Math.min(1D, this.step * (keyNumber - this.soft + 1));
    }

    /**
     * Keys left until the guarantee, for a player whose counter is {@code count}: never below 0. 1 means the
     * next key is the guaranteed one (counter 149 of Hard 150); 0 only when the counter is already at or past the cap.
     */
    public int keysLeft(int count) {
        return Math.max(0, this.hard - count);
    }

    /** Whether the player's next key is already guaranteed (keys left 1 or 0), so "within N keys" must not be shown. */
    public static boolean isNextKey(int keysLeft) {
        return keysLeft <= 1;
    }
}
