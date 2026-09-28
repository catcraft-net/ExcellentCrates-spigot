package su.nightexpress.excellentcrates.editor.crate;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Matches editor queries without touching crate data or Bukkit state. */
public final class CrateSearch {
    private static final Pattern TAGS = Pattern.compile("(?i)</?(?:#[0-9a-f]{6}|black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|grey|dark_gray|dark_grey|blue|green|aqua|red|light_purple|yellow|white|b|bold|i|italic|u|underlined|st|strikethrough|obf|obfuscated|reset|color|colour|gradient|rainbow|transition|font|click|hover|insertion|shadow_color|br|newline)(?::[^>]*)?>");
    private static final Pattern HEX = Pattern.compile("(?i)[&§]x(?:[&§][0-9a-f]){6}|[&§]?#(?:[0-9a-f]{6})");
    private static final Pattern LEGACY = Pattern.compile("(?i)[&§][0-9a-fk-or]");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private CrateSearch() {}

    public static <T> List<T> find(Collection<T> entries, Function<T, String> id,
                                  Function<T, String> name, String query) {
        String needle = normalize(query).replace(" ", "");
        return entries.stream()
            .map(entry -> new Match<>(entry, Math.min(score(id.apply(entry), needle), score(name.apply(entry), needle))))
            .filter(match -> match.score() != Integer.MAX_VALUE)
            .sorted(Comparator.<Match<T>>comparingInt(Match::score).thenComparing(match -> id.apply(match.entry())))
            .map(Match::entry)
            .toList();
    }

    private record Match<T>(T entry, int score) {}

    static String normalize(String text) {
        String plain = TAGS.matcher(text).replaceAll("");
        plain = HEX.matcher(plain).replaceAll("");
        plain = LEGACY.matcher(plain).replaceAll("");
        return SEPARATORS.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private static int score(String text, String needle) {
        if (needle.isEmpty()) return 0;
        String words = normalize(text);
        String compact = words.replace(" ", "");
        if (compact.equals(needle)) return 0;
        if (compact.startsWith(needle)) return 1;
        if (compact.contains(needle)) return 2;

        // Short input is intentionally precise. Years and numbered IDs must not match a different number.
        if (needle.length() < 4 || needle.codePoints().anyMatch(Character::isDigit)) return Integer.MAX_VALUE;
        int limit = needle.length() >= 8 ? 2 : 1;
        int distance = distance(needle, compact, limit);
        for (String word : words.split(" ")) {
            distance = Math.min(distance, distance(needle, word, limit));
        }
        return distance <= limit ? 3 + distance : Integer.MAX_VALUE;
    }

    private static int distance(String left, String right, int limit) {
        if (Math.abs(left.length() - right.length()) > limit) return limit + 1;
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j < previous.length; j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            int best = current[0];
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(previous[j - 1] + cost, Math.min(previous[j] + 1, current[j - 1] + 1));
                best = Math.min(best, current[j]);
            }
            if (best > limit) return limit + 1;
            previous = current;
        }
        return previous[right.length()];
    }
}
