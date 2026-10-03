# CatCraft 6.6.1-catcraft.5 (Paper 26.3)

## Changes in .5 (all opt-in per opening file, except /crates fast and the mass-open summary)

- **Result-aware reveals.** Inventory openings can have `Settings.Reveals.<id>` sections: animation
  spinners that start when the reward spinners stop, chosen by what was won. Reveals are checked in
  config order and the first whose `Match` fits a won reward runs. `Match` options: `Broadcast`
  (true/false), `Max_Chance` (%), `Lore_Contains` (text), `Reward_Ids` (list). An empty `Match` always
  fits. Spinner delays count from the tick the reel stops.
- **Crate themes.** Animation spinner items with `Theme: primary` or `Theme: secondary` are recoloured to
  the stained glass pane closest to the first/last hex colour in the crate's name gradient.
- **Ultra showcase.** Reward spinners with `Showcase_Broadcast_Rewards: true` (SEQUENTAL mode) show the
  crate's broadcast rewards passing through the reel in every spin, one every 3 moves, only while they
  will scroll out before the reel stops. They are never placed next to the win slot.
- **Click to skip.** Clicking in an opening window skips the spin (while `Max_Ticks_To_Skip` allows), and
  clicking during the reveal/result closes it immediately.
- **/crates fast** (permission `excellentcrates.command.fast`, default true) toggles a per-player
  preference to open crates without animations (stored on the player).
- **Mass-open summary** (`Crate.MassOpening.Summary`, default true): one read-only results screen after a
  mass opening. Wins fill in from most to least likely, grouped with counts, broadcast (ultra) rewards
  last with a glint and the challenge sound. The per-opening result chat lines are skipped.
- Tested on Paper 26.3 with bots (normal and ultra reveals, themes, showcase, click-skip, click-close,
  fast mode, 10-key mass opening).

# CatCraft candidate 6.6.1-catcraft.4 (Paper 26.3)

## Changes in .4

- Built against nightcore **2.16.6** (Modrinth, lists 26.3). nightcore 2.16.4 fails on Paper 26.3
  (`Could not find asBukkitCopy`), and ExcellentCrates cannot enable without nightcore. Deploy
  `nightcore-2.16.6.jar` together with this jar.
- Item-data upgrade: Minecraft 26.3's data fixer splits `minecraft:swing_animation` into
  `minecraft:attack_animation` + `minecraft:interact_animation` (data version 5007) and removes
  `minecraft:map_color` (5008), but it does not update the names listed in
  `minecraft:tooltip_display` `hidden_components`. The 26.3 item codec rejects such items completely
  ("Unknown registry key ... minecraft:map_color"). Many CatCraft reward items hide every component,
  so their lists contain both names. The upgrade now applies the same rename/removal to that list
  (only when the upgrade crosses those data versions); everything else is unchanged.
- Items stored with an unknown data version (0 or -1) are still not run through the data fixer
  (nightcore decodes them as they are). If such an item no longer decodes on 26.3 and the only
  problem is one of those two names in `hidden_components`, the list is repaired and the version
  is left as it was. Items that decode already, or fail for another reason, are untouched.
- Nothing else changed from .3.

The first 26.3 start upgrades every stored vanilla item (26.2 data version 4903 -> 26.3 data version
5023) and writes one backup per changed file under `plugins/ExcellentCrates/item-data-backups/`.

# Previous notes (6.6.1-catcraft.3)

Based on CatCraft ExcellentCrates revision `d9ba2393a5dc812368379acc907f61a34aabb7bb`.
This candidate retains crate-list search and the default-on strict key safeguard.
The only new behavior since .2 is a backed-up upgrade of stored vanilla item data.
The earlier cooldown/currency migration and asynchronous-save findings remain
follow-up work. Unreadable cost entries are still preserved by the key safeguard.
The upstream GPLv3 license and attribution are retained.

## Search

Open the crate editor and click the compass. Enter part of a crate name or ID,
then confirm. `sum` finds the six summer-related crates in the supplied archive;
`hallowen` finds Halloween crates. Exact, prefix and substring matches rank before
conservative typo matches. Queries shorter than four characters, or containing
digits, do not use typo matching, so years stay precise. Known color formatting
is ignored. Clear Search or a blank query restores all crates.

Each administrator has a separate filter. Returning from crate options retains
the filter and page; a changed filter starts on page one. Returning to the main
editor clears the filter. A no-results icon explains an empty list. Search
does not modify crate definitions. Disconnect and shutdown release search state.
New UI text uses locale entries under `Editor.Button.Crates` and `Dialog.Crate.Search`.

## Key safeguard

`Crate.Opening.RequireKey` defaults to `true`, including when absent from an
existing configuration. Every opening must select an enabled cost belonging to
that crate, containing a valid physical or virtual key. All its entries must be
valid. Missing/deleted key references, disabled costs, empty costs, keyless
currency options, and stale/foreign cost objects cannot become free openings.
Admin force commands cannot bypass this safeguard. Previewing remains available.

Players must have enough matching keys; repeated entries for one key are added
together. Checks run in the shared opening method for clicks, commands, and bulk
openings, then again after event/inventory/provider callbacks immediately before
payment. Keys and the crate item are charged before an opening can award rewards.
Cancelled or rejected openings do not charge keys. Unknown/unreadable cost entries
are retained through save/reload and block the cost until its configuration is
repaired; they are not silently dropped.

Players see an unavailable message for broken configuration, or the existing
insufficient-cost message when they lack keys. Console diagnostics name the crate
and configured key IDs, limited to one blocked-opening warning per crate per load.
The existing problem inspector also flags crates with no eligible key cost.
New message: `Crate.Open.Error.KeyConfiguration`.

Archive audit: 100 of the 102 crates have an eligible key cost. Two are blocked by
this policy: `catgods_mythology_essentials` and `crate_editor`. Configure a valid
key cost for those crates if they should be openable. No key association is guessed
and original crate files were not changed. Setting RequireKey to false deliberately
restores support for keyless openings and cost bypasses; keep it true for CatCraft's
requested protection.

## One-time saved item-data upgrade

Before loading crates/keys, the plugin scans their YAML files for stored vanilla
item wrappers (`Provider: vanilla`, `Data.Value`, `Data.DataVersion`). For known
older versions it uses Minecraft's data fixer with the server's actual data
version, then requires a complete codec decode and a lossless tag/item round trip.
The full converted tag is saved, including custom data. It does not re-serialize
through Bukkit, which could discard extra fields. Custom-provider references,
unknown/missing versions, current/newer versions and unsuccessful/partial decodes
are left unchanged. No item names, quantities, rewards, key associations, or other
configuration values are intentionally edited.

Each changed file is backed up exactly as bytes under
`plugins/ExcellentCrates/item-data-backups/crates/` or `.../keys/`.
Backup names contain the original filename and its SHA-256 hash; an existing
backup is checked and never overwritten. The new file is validated and flushed,
POSIX ownership/group/permissions are preserved, and replacement is atomic. A
backup/write/validation failure leaves the source unchanged. The scan excludes
symlink files and backup folders. On later starts current item tags are skipped;
there is no global marker that would accidentally skip newly added old items.

To restore a file, stop the server and copy its chosen `.bak` back to its matching
crate/key filename. That restores the entire file at the time of conversion,
including the settings from that time. Retain backups when upgrading the server.

The archive test upgraded 3,760 records across 206 files, preserved the loaded
items and all unrelated values, and left 61 unknown-version records unchanged.
No conversion failures occurred. The next startup converted zero records. Local
plugin-enable timings were 15.802 s for .2, 19.374 s during the first .3 conversion,
and 4.404 s on the next .3 startup. These single runs on copied files/generated
configuration are not a production benchmark or a guaranteed speedup.

## Build

Use Maven and JDK 21 or newer. The code targets Java 21. The original build's
nightcore `main:2.10.0` could not be resolved from the configured repositories.
This candidate compiles against nightcore 2.16.6, which is not published to
repo.nightexpressdev.com (it stops at 2.16.4). Install the Modrinth jar locally first:
https://cdn.modrinth.com/data/Y4NRwMW5/versions/AIeSQerQ/nightcore-2.16.6.jar
(SHA-256 `9c82a7d2e76277c0cf490e7c85b2ce827d953ec0a7977337c25690c56b472c5a`).

```sh
mvn -Dmaven.repo.local=./.m2 org.apache.maven.plugins:maven-install-plugin:3.1.3:install-file \
  -Dfile=/path/to/nightcore-2.16.6.jar \
  -DgroupId=su.nightexpress.nightcore -DartifactId=main -Dversion=2.16.6 \
  -Dpackaging=jar -DgeneratePom=true
mvn -Dmaven.repo.local=./.m2 clean verify
```

Expected artifact: `target/ExcellentCrates-6.6.1-catcraft.4.jar` (built with JDK 25, class files target Java 21).
The 6.6.1-catcraft.3 jar was built with JDK 21 against nightcore 2.16.4.
`verify` runs ten matcher tests and five hidden-components fix tests. No JUnit or test classes are shipped in that JAR.
The supporting nightcore JAR SHA-256 is
`0449d8700b41f13a458caedb68f9959db35f89d01ea05ef0d0907812484aa8ab`.

## Disposable runtime tests

Thirty-three additional tests require real Bukkit registries and run separately from
Maven's unit tests. Build their test-only plugin after `verify`:

```sh
python3 tools/build-search-integration.py \
  --nightcore /path/to/nightcore-2.16.4.jar --maven-repo ./.m2
```

On a disposable Paper 26.2 server with Java 25, install copies of the candidate,
nightcore 2.16.4, and `target/SearchIntegration.jar`. Copy the supplied archive's
crates/keys/data into that disposable server's ExcellentCrates data folder. Also
copy the original crates/keys into `plugins/SearchIntegration/original-fixtures/`
for the independent item-preservation comparison. Never place this test harness
on a live server; it deliberately mutates temporary in-memory test state.
The fixture assertions expect the supplied 102 crates and their IDs. The test
plugin runs after enable and logs `SEARCH_INTEGRATION: tests=33 failures=0` on
success. It checks actual fixture matching, per-player menu filtering, page
clamping, an empty-result icon, and confirm/back/null-response dialog callbacks.
Twenty key-safety tests exercise the actual managers and key/cost code with player
and opening-animation test doubles: rejection paths, physical/virtual consumption,
bulk exhaustion, duplicate amounts, event/close/provider mutations, canceled
openings, preserved unreadable costs, and the deliberate configuration opt-out.
Seven item-upgrade tests cover startup persistence, backups, item/quantity
preservation, no-op repeat runs, file permissions, skipped versions/providers,
partial-decode rejection, backup failure, and all 3,760 converted archive records.
It is a test harness, not a client: it does not click through a rendered GUI.

Validation here: ten unit tests and all 33 runtime tests passed on Paper 26.2
with nightcore 2.16.4. All 102 crates and 108 keys loaded. The archive contains
2,033 rewards. Generated defaults were used for missing plugin configuration,
and production worlds/custom-item integrations were absent. This is not a full
reproduction of the UniverseSpigot server in the supplied log.

Before rollout, test the client GUI on Dev: search, confirm, cancel, clear,
no results, pagination, return from crate options, two administrators, disconnect,
and disable while a search dialog is open. Verify normal crate/reward behavior
with the actual server's integration plugins. Check a missing/deleted key, wrong key,
valid key, bulk opening, and forced admin opening. The test animation checks the
reward-start boundary; no actual reward grants to a connected client were tested.
No live deployment was performed.
