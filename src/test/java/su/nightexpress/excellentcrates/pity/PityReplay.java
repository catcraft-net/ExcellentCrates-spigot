package su.nightexpress.excellentcrates.pity;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import su.nightexpress.excellentcrates.crate.pity.PityRoll;
import su.nightexpress.excellentcrates.crate.pity.PitySettings;
import su.nightexpress.excellentcrates.crate.reward.RewardRoll;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * CatCraft: replays a realm's ExcellentCrates openings.log through the plugin's own roll code with the pity
 * rules switched on, to see what pity would have changed.
 * <p>
 * No Bukkit server is needed. The crate files are read with SnakeYAML to get each reward's Weight, Rarity
 * and Ultra Legendary status (the same text test the plugin uses, {@link PityRoll#isUltraDescription}, on
 * the reward's Description or on the lore of its ExecutableItems / vanilla preview item), and the realm's
 * config.yml for the rarity weights. For every logged key, player by player in log order, the harness calls
 * <ul>
 *   <li>{@link PityRoll#roll} – the plugin's pity rules (PityService calls this in play), which on a pity
 *       hit picks a UL with {@link RewardRoll#weighted} and otherwise calls</li>
 *   <li>{@link RewardRoll#rarityThenWeight} – the plugin's normal roll (Crate#rollReward calls this in play).</li>
 * </ul>
 * Only the pity decision and the pity-picked reward are simulated; a key the pity rules leave alone keeps the
 * reward the log says the player really got, so the natural UL numbers are the real ones. Each run uses a
 * seeded {@link java.util.SplittableRandom}; results are averaged over N runs.
 * <p>
 * Usage: {@code PityReplay <realm name> <openings.log> <crates dir> <config.yml> <ei items dir|-> [runs] [soft,hard,step ...]}
 */
public class PityReplay {

    // [04/11/2023 21:54:26] aboody123 won x1 Power V Book (ID: d4936365-..., Weight: 1.0) from Cat Crate (ID: cat)
    private static final Pattern LINE = Pattern.compile("^\\[(\\d\\d)/(\\d\\d)/(\\d{4}) (\\d\\d):(\\d\\d):(\\d\\d)\\] (\\S+) won .*\\(ID: ([^,)]+), Weight: ([-0-9.Ee]+)\\) from .* \\(ID: ([^)]+)\\)\\s*$");
    private static final Pattern SNBT_TEXT = Pattern.compile("text\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Crates that have a UL but are not monthly themed crates (vote/cat/god/dragon style): pity off. */
    private static final Set<String> NOT_MONTHLY = Set.of(
        "vote", "cat", "dragon", "dragongear", "event", "crate_editor", "easy", "medium", "hard",
        "abasi", "aisha", "ammon", "anubis", "anukis", "bastet", "freyja", "horus", "lotus", "musa", "osiris", "ra", "sefu", "zalika", // CatGod crates
        "staff_crate_2024", "catgods_mythology_essentials", "echoesoftheend", "aztec_dungeon", "piratesupplies", "pirateisland_supplies",
        "aloha_resort_coin", "sand_dollar_crate", "seashell_summer", "raretreasure", "the_wild_crate", "crab", "pirate", "pirate2"
    );

    record RewardDef(String id, String crateId, double weight, String rarity, boolean ultra, String ultraSource) {}

    record CrateDef(String id, String name, List<RewardDef> rewards, Map<String, Double> rarityWeights, boolean monthly) {
        List<RewardDef> ultras() { return rewards.stream().filter(RewardDef::ultra).toList(); }
        List<RewardDef> rollable() { return rewards.stream().filter(r -> r.weight() > 0).toList(); }
        double ultraPercent() {
            // Same formula as AbstractReward#getRollChance, summed over the ULs.
            double sum = 0;
            double raritySum = rewards.stream().map(RewardDef::rarity).distinct().mapToDouble(r -> rarityWeights.getOrDefault(r, 0D)).sum();
            for (RewardDef ul : ultras()) {
                double inRarity = rewards.stream().filter(r -> r.rarity().equals(ul.rarity())).mapToDouble(RewardDef::weight).sum();
                double rarityChance = raritySum == 0 ? 0 : rarityWeights.getOrDefault(ul.rarity(), 0D) / raritySum;
                sum += inRarity == 0 ? 0 : (ul.weight() / inRarity) * rarityChance * 100D;
            }
            return sum;
        }
    }

    record Key(long time, String player, String crateId, String rewardId) {}

    public static void main(String[] args) throws IOException {
        if (args.length < 5) {
            System.err.println("Usage: PityReplay <realm> <openings.log> <crates dir> <config.yml> <ei items dir|-> [runs] [soft,hard,step ...]");
            System.exit(2);
        }
        String realm = args[0];
        Path log = Path.of(args[1]);
        Path cratesDir = Path.of(args[2]);
        Path config = Path.of(args[3]);
        List<Path> eiDirs = args[4].equals("-") ? List.of() : Arrays.stream(args[4].split(",")).map(Path::of).toList();
        int runs = args.length > 5 ? Integer.parseInt(args[5]) : 200;
        List<PitySettings> variants = new ArrayList<>();
        for (int i = 6; i < args.length; i++) {
            String[] p = args[i].split(",");
            variants.add(new PitySettings("monthly", Integer.parseInt(p[0]), Integer.parseInt(p[1]), Double.parseDouble(p[2])));
        }
        if (variants.isEmpty()) {
            variants.add(new PitySettings("monthly", 120, 150, 0.03));
            variants.add(new PitySettings("monthly", 90, 120, 0.03));
            variants.add(new PitySettings("monthly", 150, 150, 0D)); // hard only
        }

        Map<String, Double> rarityWeights = loadRarities(config);
        Map<String, List<String>> eiLore = new HashMap<>();
        for (Path eiDir : eiDirs) loadEiLore(eiDir).forEach(eiLore::putIfAbsent); // first dir (this realm) wins
        Map<String, CrateDef> crates = loadCrates(cratesDir, rarityWeights, eiLore);
        List<Key> keys = loadLog(log);

        System.out.println("# Pity replay for " + realm);
        System.out.println("Roll code used: su.nightexpress.excellentcrates.crate.pity.PityRoll#roll (pity rules, as PityService calls it in play)");
        System.out.println("                su.nightexpress.excellentcrates.crate.reward.RewardRoll#rarityThenWeight / #weighted (the plugin's normal roll, as Crate#rollReward calls it)");
        System.out.println("Log: " + log + " (" + keys.size() + " keys with a known crate, " + keys.stream().map(Key::player).distinct().count() + " players)");
        System.out.println("Crates read from " + cratesDir + " (weights as they are now), rarities from " + config + " = " + rarityWeights);
        System.out.println("ExecutableItems lore read from " + eiDirs + " (" + eiLore.size() + " items)" + (EI_PARSE_FAILURES.isEmpty() ? "" : "; " + EI_PARSE_FAILURES.size() + " item files could not be parsed: " + EI_PARSE_FAILURES));
        if (!EI_MISSING.isEmpty()) System.out.println("Rewards whose ExecutableItems item was not found in any EI folder (counted as not UL): " + EI_MISSING);
        System.out.println();

        // Crate classification
        List<CrateDef> monthly = crates.values().stream().filter(CrateDef::monthly).sorted(Comparator.comparing(CrateDef::id)).toList();
        System.out.println("## Crates in the monthly pity group (" + monthly.size() + ") – has at least one Ultra Legendary and is a themed crate");
        System.out.println();
        System.out.println("| crate | ULs (weight) | UL chance per key | UL detected by |");
        System.out.println("|---|---|---|---|");
        for (CrateDef crate : monthly) {
            String uls = crate.ultras().stream().map(u -> u.id() + " (" + u.weight() + ")").reduce((a, b) -> a + ", " + b).orElse("");
            String how = crate.ultras().stream().map(RewardDef::ultraSource).distinct().reduce((a, b) -> a + ", " + b).orElse("");
            System.out.printf("| %s | %s | %.2f%% | %s |%n", crate.id(), uls, crate.ultraPercent(), how);
        }
        System.out.println();
        List<String> skipped = crates.values().stream().filter(c -> !c.monthly() && !c.ultras().isEmpty()).map(CrateDef::id).sorted().toList();
        System.out.println("Crates with a UL left out of the group (vote/cat/god/dragon style): " + skipped);
        List<String> noUltra = crates.values().stream().filter(c -> c.ultras().isEmpty()).map(CrateDef::id).sorted().toList();
        System.out.println("Crates with no UL found (pity off): " + noUltra);
        Set<String> unknownCrates = new TreeSet<>();
        keys.forEach(k -> { if (!crates.containsKey(k.crateId())) unknownCrates.add(k.crateId()); });
        System.out.println("Crate IDs in the log with no crate file now (their keys are counted as 'other crates', they never touch the counter): " + unknownCrates);
        System.out.println();

        // Actual numbers from the log (no simulation)
        Map<String, long[]> actual = new TreeMap<>(); // crateId -> {keys, naturalULs}
        Map<String, Set<String>> playersPerCrate = new TreeMap<>();
        for (Key key : keys) {
            CrateDef crate = crates.get(key.crateId());
            if (crate == null || !crate.monthly()) continue;
            long[] a = actual.computeIfAbsent(key.crateId(), k -> new long[2]);
            a[0]++;
            if (crate.rewards().stream().anyMatch(r -> r.id().equalsIgnoreCase(key.rewardId()) && r.ultra())) a[1]++;
            playersPerCrate.computeIfAbsent(key.crateId(), k -> new HashSet<>()).add(key.player());
        }

        for (PitySettings settings : variants) {
            replay(settings, keys, crates, actual, playersPerCrate, runs);
        }
    }

    private static void replay(PitySettings settings, List<Key> keys, Map<String, CrateDef> crates, Map<String, long[]> actual,
                               Map<String, Set<String>> playersPerCrate, int runs) {
        System.out.println("## Rules Soft " + settings.soft() + " / Hard " + settings.hard() + " / Step " + settings.step() + "  (" + runs + " seeded runs, averaged)");
        System.out.println();

        Map<String, double[]> added = new TreeMap<>(); // crateId -> {soft hits, hard hits} summed over runs
        double[] totalAdded = new double[2];
        // Dry-streak reach is independent of the random seed only in the no-pity world; with pity on, who reaches
        // Soft/Hard changes per run, so these are averaged too.
        double reachedSoft = 0, reachedHard = 0, reachedSoftCameBack = 0, reachedHardCameBack = 0;
        double reachedSoftNoPity = 0, reachedHardNoPity = 0, reachedSoftNoPityCameBack = 0, reachedHardNoPityCameBack = 0;

        // Which players opened keys on a later (different) monthly crate after a given key index: precompute the
        // last monthly crate id per player and the set of crates they used after each key.
        Map<String, List<Integer>> keyIndexByPlayer = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            CrateDef crate = crates.get(keys.get(i).crateId());
            if (crate != null && crate.monthly()) keyIndexByPlayer.computeIfAbsent(keys.get(i).player(), k -> new ArrayList<>()).add(i);
        }

        for (int run = 0; run < runs; run++) {
            RandomGenerator random = new SplittableRandom(1000L + run);
            Map<String, Integer> counter = new HashMap<>();
            Map<String, Integer> counterNoPity = new HashMap<>();
            Set<String> softReached = new HashSet<>(), hardReached = new HashSet<>();
            Set<String> softReachedNoPity = new HashSet<>(), hardReachedNoPity = new HashSet<>();
            Map<String, Integer> softReachedAt = new HashMap<>(), hardReachedAt = new HashMap<>();
            Map<String, Integer> softReachedAtNoPity = new HashMap<>(), hardReachedAtNoPity = new HashMap<>();

            for (int i = 0; i < keys.size(); i++) {
                Key key = keys.get(i);
                CrateDef crate = crates.get(key.crateId());
                if (crate == null || !crate.monthly()) continue;

                List<RewardDef> ultras = crate.ultras();
                boolean naturalUltra = ultras.stream().anyMatch(u -> u.id().equalsIgnoreCase(key.rewardId()));

                // No-pity world: the real outcome.
                int cNo = counterNoPity.getOrDefault(key.player(), 0);
                if (naturalUltra) counterNoPity.put(key.player(), 0);
                else {
                    counterNoPity.put(key.player(), cNo + 1);
                    if (cNo + 1 >= settings.soft() && softReachedNoPity.add(key.player())) softReachedAtNoPity.put(key.player(), i);
                    if (cNo + 1 >= settings.hard() && hardReachedNoPity.add(key.player())) hardReachedAtNoPity.put(key.player(), i);
                }

                // Pity world: the plugin's roll. The normal roll keeps the logged reward (real outcome).
                int count = counter.getOrDefault(key.player(), 0);
                final RewardDef logged = new RewardDef(key.rewardId(), crate.id(), 0, "", naturalUltra, "log");
                PityRoll.Result<RewardDef> result = PityRoll.roll(settings, count, ultras, RewardDef::weight, RewardDef::ultra,
                    () -> logged, random);
                counter.put(key.player(), result.newCount());
                if (result.source() == PityRoll.Source.PITY_SOFT) added.computeIfAbsent(crate.id(), k -> new double[2])[0]++;
                if (result.source() == PityRoll.Source.PITY_HARD) added.computeIfAbsent(crate.id(), k -> new double[2])[1]++;
                if (result.source() != PityRoll.Source.NORMAL) totalAdded[result.source() == PityRoll.Source.PITY_SOFT ? 0 : 1]++;
                if (!result.ultra()) {
                    if (result.newCount() >= settings.soft() && softReached.add(key.player())) softReachedAt.put(key.player(), i);
                    if (result.newCount() >= settings.hard() && hardReached.add(key.player())) hardReachedAt.put(key.player(), i);
                }
            }

            reachedSoft += softReached.size();
            reachedHard += hardReached.size();
            reachedSoftNoPity += softReachedNoPity.size();
            reachedHardNoPity += hardReachedNoPity.size();
            reachedSoftCameBack += cameBack(softReachedAt, keys, keyIndexByPlayer);
            reachedHardCameBack += cameBack(hardReachedAt, keys, keyIndexByPlayer);
            reachedSoftNoPityCameBack += cameBack(softReachedAtNoPity, keys, keyIndexByPlayer);
            reachedHardNoPityCameBack += cameBack(hardReachedAtNoPity, keys, keyIndexByPlayer);
        }

        long totalKeys = actual.values().stream().mapToLong(a -> a[0]).sum();
        long totalNatural = actual.values().stream().mapToLong(a -> a[1]).sum();
        System.out.println("| crate | keys | players | natural ULs | UL rate | pity ULs added (soft + hard) | UL rate with pity |");
        System.out.println("|---|---|---|---|---|---|---|");
        for (var entry : actual.entrySet()) {
            String crateId = entry.getKey();
            long[] a = entry.getValue();
            double[] add = added.getOrDefault(crateId, new double[2]);
            double soft = add[0] / runs, hard = add[1] / runs;
            System.out.printf("| %s | %d | %d | %d | %.2f%% | %.1f (%.1f + %.1f) | %.2f%% |%n", crateId, a[0], playersPerCrate.get(crateId).size(), a[1],
                a[0] == 0 ? 0 : 100D * a[1] / a[0], soft + hard, soft, hard, a[0] == 0 ? 0 : 100D * (a[1] + soft + hard) / a[0]);
        }
        double addSoft = totalAdded[0] / runs, addHard = totalAdded[1] / runs;
        System.out.printf("| **all monthly crates** | %d | %d | %d | %.2f%% | %.1f (%.1f + %.1f) | %.2f%% |%n", totalKeys,
            playersPerCrate.values().stream().flatMap(Set::stream).distinct().count(), totalNatural,
            totalKeys == 0 ? 0 : 100D * totalNatural / totalKeys, addSoft + addHard, addSoft, addHard,
            totalKeys == 0 ? 0 : 100D * (totalNatural + addSoft + addHard) / totalKeys);
        System.out.println();
        System.out.println("Dry streaks (keys since the last UL, counter shared across the monthly crates):");
        System.out.println();
        System.out.println("| | players who reached " + settings.soft() + " dry keys | of them opened a later key on another monthly crate | never came back | reached " + settings.hard() + " dry keys | came back | never came back |");
        System.out.println("|---|---|---|---|---|---|---|");
        System.out.printf("| as it really happened (no pity) | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f |%n", reachedSoftNoPity / runs, reachedSoftNoPityCameBack / runs,
            (reachedSoftNoPity - reachedSoftNoPityCameBack) / runs, reachedHardNoPity / runs, reachedHardNoPityCameBack / runs, (reachedHardNoPity - reachedHardNoPityCameBack) / runs);
        System.out.printf("| with these pity rules | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f |%n", reachedSoft / runs, reachedSoftCameBack / runs,
            (reachedSoft - reachedSoftCameBack) / runs, reachedHard / runs, reachedHardCameBack / runs, (reachedHard - reachedHardCameBack) / runs);
        System.out.println();
        System.out.println("(\"came back\" = after the key that put them at that streak, they opened at least one more key on a different monthly crate. The pity rows are averages over the runs, so they can be fractions.)");
        System.out.println();
    }

    private static int cameBack(Map<String, Integer> reachedAt, List<Key> keys, Map<String, List<Integer>> keyIndexByPlayer) {
        int back = 0;
        for (var entry : reachedAt.entrySet()) {
            String crateAt = keys.get(entry.getValue()).crateId();
            for (int idx : keyIndexByPlayer.getOrDefault(entry.getKey(), List.of())) {
                if (idx > entry.getValue() && !keys.get(idx).crateId().equals(crateAt)) { back++; break; }
            }
        }
        return back;
    }

    // ------------------------------------------------------------------------------------------------

    static List<Key> loadLog(Path log) throws IOException {
        List<Key> keys = new ArrayList<>();
        int bad = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(log), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = LINE.matcher(line);
                if (!m.matches()) { if (!line.isBlank()) bad++; continue; }
                long time = Long.parseLong(m.group(3)) * 100000000L + Long.parseLong(m.group(2)) * 1000000L + Long.parseLong(m.group(1)) * 10000L
                    + Long.parseLong(m.group(4)) * 100L + Long.parseLong(m.group(5)); // yyyyMMddHHmm, enough to keep order with a stable sort
                keys.add(new Key(time, m.group(7), m.group(10).toLowerCase(), m.group(8).toLowerCase()));
            }
        }
        if (bad > 0) System.err.println("Skipped " + bad + " log lines that did not match the format");
        // The file is appended in time order; keep that order (stable sort only fixes odd out-of-order lines).
        keys.sort(Comparator.comparingLong(Key::time));
        return keys;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Double> loadRarities(Path config) throws IOException {
        Map<String, Object> root = yaml().load(Files.readString(config));
        Map<String, Object> rewards = (Map<String, Object>) root.get("Rewards");
        Map<String, Object> rarities = (Map<String, Object>) rewards.get("Rarities");
        Map<String, Double> out = new LinkedHashMap<>();
        rarities.forEach((id, section) -> {
            Map<String, Object> s = (Map<String, Object>) section;
            Object w = s.containsKey("Weight") ? s.get("Weight") : s.get("Chance");
            out.put(id.toLowerCase(), ((Number) w).doubleValue());
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    static final List<String> EI_PARSE_FAILURES = new ArrayList<>();
    static final Set<String> EI_MISSING = new TreeSet<>();

    static Map<String, List<String>> loadEiLore(Path dir) throws IOException {
        Map<String, List<String>> lore = new HashMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                try {
                    Object root = yaml().load(Files.readString(file));
                    if (!(root instanceof Map<?, ?> map)) continue;
                    Object l = map.get("lore");
                    String id = file.getFileName().toString().replaceFirst("\\.yml$", "");
                    if (l instanceof List<?> list) lore.put(id.toLowerCase(), list.stream().map(String::valueOf).toList());
                    else lore.put(id.toLowerCase(), List.of());
                }
                catch (Exception e) { EI_PARSE_FAILURES.add(file.getFileName().toString()); }
            }
        }
        return lore;
    }

    @SuppressWarnings("unchecked")
    static Map<String, CrateDef> loadCrates(Path dir, Map<String, Double> rarityWeights, Map<String, List<String>> eiLore) throws IOException {
        Map<String, CrateDef> crates = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                String crateId = file.getFileName().toString().replaceFirst("\\.yml$", "").toLowerCase();
                Map<String, Object> root;
                try { root = yaml().load(Files.readString(file)); }
                catch (Exception e) { System.err.println("Cannot read " + file + ": " + e.getMessage()); continue; }
                if (root == null) continue;
                String name = String.valueOf(root.getOrDefault("Name", crateId));
                List<RewardDef> rewards = new ArrayList<>();
                Map<String, Object> rewardsRoot = (Map<String, Object>) root.get("Rewards");
                Map<String, Object> list = rewardsRoot == null ? null : (Map<String, Object>) rewardsRoot.get("List");
                if (list != null) {
                    list.forEach((rid, section) -> {
                        Map<String, Object> r = (Map<String, Object>) section;
                        double weight = r.get("Weight") == null ? 10D : ((Number) r.get("Weight")).doubleValue();
                        String rarity = String.valueOf(r.getOrDefault("Rarity", "common")).toLowerCase();
                        String[] ultra = ultraOf(r, eiLore);
                        rewards.add(new RewardDef(rid.toLowerCase(), crateId, Math.max(0, weight), rarity, ultra != null, ultra == null ? "" : ultra[0]));
                    });
                }
                boolean hasUltra = rewards.stream().anyMatch(RewardDef::ultra);
                boolean monthly = hasUltra && !NOT_MONTHLY.contains(crateId);
                crates.put(crateId, new CrateDef(crateId, name, rewards, rarityWeights, monthly));
            }
        }
        return crates;
    }

    /** Returns {how} when the reward is an Ultra Legendary (same tests the plugin's Reward#isUltraLegendary runs), else null. */
    @SuppressWarnings("unchecked")
    static String[] ultraOf(Map<String, Object> r, Map<String, List<String>> eiLore) {
        if (r.get("Pity_Reward") instanceof Boolean b) return b ? new String[]{"Pity_Reward"} : null;

        String type = String.valueOf(r.getOrDefault("Type", "ITEM"));
        if (type.equalsIgnoreCase("COMMAND")) {
            Object desc = r.get("Description");
            List<String> lines = desc instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
            return PityRoll.isUltraDescription(lines) ? new String[]{"Description"} : null;
        }

        // ITEM reward: the preview is the first item unless Custom_Preview is on or there are no items (ItemReward#getPreview).
        boolean customPreview = Boolean.TRUE.equals(r.get("Custom_Preview"));
        Map<String, Object> items = r.get("ItemsData") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        Map<String, Object> provider = null;
        if (!customPreview && items != null && !items.isEmpty()) {
            Object first = items.values().iterator().next();
            if (first instanceof Map<?, ?> m) provider = (Map<String, Object>) m;
        }
        if (provider == null && r.get("PreviewData") instanceof Map<?, ?> m) provider = (Map<String, Object>) m;
        if (provider == null) return null;

        String providerId = String.valueOf(provider.getOrDefault("Provider", "vanilla"));
        Map<String, Object> data = provider.get("Data") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (providerId.equalsIgnoreCase("executableitems")) {
            String id = String.valueOf(data.get("ID")).toLowerCase();
            List<String> lore = eiLore.get(id);
            if (lore == null) { EI_MISSING.add(id); return null; } // EI item not found: cannot tell (reported as not UL)
            return PityRoll.isUltraDescription(lore) ? new String[]{"EI lore"} : null;
        }
        if (providerId.equalsIgnoreCase("vanilla")) {
            String value = String.valueOf(data.get("Value"));
            int at = value.indexOf("minecraft:lore");
            if (at < 0) return null;
            // Join every text fragment of the lore component (per-letter gradients become one word).
            String loreBlob = value.substring(at);
            int end = loreBlob.indexOf("]"); // first list end: lore lines are a list of components
            // The lore list contains nested arrays (extra:[...]), so take a generous slice instead.
            String slice = loreBlob.substring(0, Math.min(loreBlob.length(), 20000));
            StringBuilder joined = new StringBuilder();
            Matcher m = SNBT_TEXT.matcher(slice);
            while (m.find()) joined.append(m.group(1));
            return PityRoll.isUltraText(joined.toString()) ? new String[]{"item lore"} : null;
        }
        return null;
    }

    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(64 * 1024 * 1024);
        options.setMaxAliasesForCollections(1000);
        return new Yaml(options);
    }
}
