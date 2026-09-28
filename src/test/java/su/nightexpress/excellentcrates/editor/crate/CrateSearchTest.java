package su.nightexpress.excellentcrates.editor.crate;

import org.junit.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

public class CrateSearchTest {
    private record Entry(String id, String name) {}

    private static final List<Entry> CRATES = List.of(
        new Entry("summer2026", "<gradient:#123456:#abcdef><b>Summer Crate 2026</b></gradient>"),
        new Entry("halloween2024", "&6&lHalloween Crate"),
        new Entry("summer_2024", "#ffe212&lS#cae71e&lu#88e12e&lm#4fd948&lm#3ec789&le#2cb5ca&lr"),
        new Entry("winter2023", "§x§1§2§3§4§5§6Winter Crate"),
        new Entry("anime_crate_2023", "&x&f&f&0&0&0&0Anime Crate")
    );

    private List<String> find(String query) {
        return CrateSearch.find(CRATES, Entry::id, Entry::name, query).stream().map(Entry::id).toList();
    }

    @Test public void partialNameFindsOnlyMatchingCrates() {
        assertEquals(List.of("summer2026", "summer_2024"), find("SUM"));
    }

    @Test public void spacesAndPunctuationDoNotPreventIdMatches() {
        assertEquals(List.of("summer_2024"), find("summer-2024"));
        assertEquals(List.of("anime_crate_2023"), find("anime crate 2023"));
    }

    @Test public void legacyAndHexColoursAreNotSearchableText() {
        assertEquals(List.of("winter2023"), find("winter"));
        assertEquals(List.of("anime_crate_2023"), find("anime"));
        assertEquals(List.of(), find("abcdef"));
        assertEquals(List.of(), find("ffe212"));
    }

    @Test public void aLongerQueryCanHaveOneMissingLetter() {
        assertEquals(List.of("halloween2024"), find("hallowen"));
    }

    @Test public void shortQueriesDoNotUseTypoMatching() {
        assertEquals(List.of(), find("zum"));
    }

    @Test public void blankAndFormattingOnlyQueriesRestoreTheFullSortedList() {
        List<String> all = List.of("anime_crate_2023", "halloween2024", "summer2026", "summer_2024", "winter2023");
        assertEquals(all, find("   "));
        assertEquals(all, find("<red>&l"));
    }

    @Test public void exactThenPrefixThenSubstringThenFuzzyResultsAreRanked() {
        List<Entry> entries = List.of(new Entry("d", "X Summer X"), new Entry("c", "Summer 2026"),
            new Entry("b", "Sumer"), new Entry("a", "Summer"));
        assertEquals(List.of("a", "c", "d", "b"),
            CrateSearch.find(entries, Entry::id, Entry::name, "summer").stream().map(Entry::id).toList());
    }

    @Test public void matchingDoesNotDependOnServerLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(List.of("winter2023"), find("WINTER"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test public void unrelatedQueryHasNoResults() {
        assertEquals(List.of(), find("completelyunrelated"));
    }

    @Test public void bracketsAroundOrdinaryWordsArePunctuationNotFormatting() {
        assertEquals(List.of("summer2026", "summer_2024"), find("<sum>"));
    }
}
