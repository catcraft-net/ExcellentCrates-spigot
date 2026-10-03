package su.nightexpress.excellentcrates.opening.inventory;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.crate.impl.Crate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CatCraft: the crate's colours, read from the hex colours in its name (e.g.
 * {@code <gradient:#FF5A00:#8A2BE2>HALLOWEEN CRATE</gradient>}), as the closest stained glass panes.
 * Opening spinner items marked {@code Theme: primary} / {@code secondary} are recoloured with these.
 */
public final class CrateTheme {

    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{6})");

    private final Material primary;
    private final Material secondary;

    private CrateTheme(@Nullable Material primary, @Nullable Material secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @NotNull
    public static CrateTheme of(@NotNull Crate crate) {
        List<Integer> colours = new ArrayList<>();
        Matcher matcher = HEX.matcher(crate.getName());
        while (matcher.find()) colours.add(Integer.parseInt(matcher.group(1), 16));
        if (colours.isEmpty()) return new CrateTheme(null, null);

        Material first = nearestPane(colours.getFirst());
        Material last = nearestPane(colours.getLast());
        // A one-colour (or same-pane) gradient: use a lighter/darker neighbour so the two still differ.
        if (last == first && colours.size() > 1) last = nearestPane(colours.get(colours.size() / 2));
        return new CrateTheme(first, last);
    }

    /** The pane to use for a spinner item's {@code Theme} value, or null to keep the configured item. */
    @Nullable
    public Material pane(@Nullable String theme) {
        if (theme == null) return null;
        return switch (theme.toLowerCase(Locale.ROOT)) {
            case "primary" -> this.primary;
            case "secondary" -> this.secondary != null ? this.secondary : this.primary;
            default -> null;
        };
    }

    @NotNull
    static Material nearestPane(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        DyeColor best = DyeColor.WHITE;
        double bestDistance = Double.MAX_VALUE;
        for (DyeColor dye : DyeColor.values()) {
            org.bukkit.Color colour = dye.getColor();
            // Weighted RGB distance (the eye is most sensitive to green, least to blue).
            double dr = r - colour.getRed(), dg = g - colour.getGreen(), db = b - colour.getBlue();
            double distance = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = dye;
            }
        }
        Material pane = Material.matchMaterial(best.name() + "_STAINED_GLASS_PANE");
        return pane != null ? pane : Material.WHITE_STAINED_GLASS_PANE;
    }
}
