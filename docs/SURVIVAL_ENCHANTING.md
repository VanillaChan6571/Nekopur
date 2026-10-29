# Survival enchanting progression

Nekopur 26.3 supplies configurable enchanting-library power, higher table/anvil
enchantment ranks, crouching armor regeneration, darkness-triggered invisibility, spawner harvesting,
protection combinations, underwater mining changes, and anvil cost-ceiling removal.
Settings are generated in `purpur.yml` under `settings.nekopur.gameplay`.
Existing explicitly configured caps are preserved on upgrade; replace those values
with the defaults below if upgrading from the first survival build.
They apply globally. Set `enabled: false` to restore the upstream behavior of
these changes; existing upstream Purpur settings and plugin overrides still apply.

## Starting configuration

```yaml
settings:
  nekopur:
    gameplay:
      enabled: true
      enchanting:
        max-table-level: 100
        max-shelf-power: 50
        book-power-per-level: 1.0
        max-book-power: 5
        level-spend-multiplier: 1.0
        caps:
          "minecraft:protection": 8
          "minecraft:fire_protection": 8
          "minecraft:blast_protection": 8
          "minecraft:projectile_protection": 8
          "minecraft:feather_falling": 8
          "minecraft:respiration": 8
          "minecraft:aqua_affinity": 3
          "minecraft:thorns": 8
          "minecraft:depth_strider": 5
          "minecraft:frost_walker": 5
          "minecraft:soul_speed": 5
          "minecraft:swift_sneak": 5
          "minecraft:binding_curse": 1
          "minecraft:sharpness": 8
          "minecraft:smite": 8
          "minecraft:bane_of_arthropods": 8
          "minecraft:knockback": 5
          "minecraft:fire_aspect": 5
          "minecraft:looting": 8
          "minecraft:sweeping_edge": 8
          "minecraft:efficiency": 8
          "minecraft:silk_touch": 3
          "minecraft:unbreaking": 8
          "minecraft:fortune": 8
          "minecraft:power": 8
          "minecraft:punch": 5
          "minecraft:flame": 3
          "minecraft:infinity": 1
          "minecraft:luck_of_the_sea": 5
          "minecraft:lure": 5
          "minecraft:loyalty": 5
          "minecraft:impaling": 8
          "minecraft:riptide": 5
          "minecraft:channeling": 3
          "minecraft:multishot": 3
          "minecraft:quick_charge": 5
          "minecraft:piercing": 5
          "minecraft:density": 8
          "minecraft:breach": 8
          "minecraft:wind_burst": 5
          "minecraft:lunge": 5
          "minecraft:mending": 3
          "minecraft:vanishing_curse": 3
      mending:
        repair-multiplier: 0.5
        armor:
          enabled: true
          delay-seconds: 0
          ramp-seconds: 300
          durability-per-second: 1.0
          xp-per-durability: 1
          lifetime-multiplier: 2.5
      vanishing:
        enabled: true
        cooldown-seconds: 60
      anvil:
        remove-cost-limit: true
```

## Libraries and table offers

Ordinary bookshelves contribute at most 15 power, preserving the level-30
baseline. Chiseled bookshelves within the usual bookshelf positions contribute
the strongest stored enchantment rank of each enchanted book, multiplied by
`book-power-per-level` and capped by `max-book-power`. Plain books contribute
nothing; a book with several enchantments is counted once. Books are not consumed.
The normal gap/obstruction rules apply. Total power is floored and capped by
`max-shelf-power`.

At the default settings, 15 ordinary shelves plus seven rank-V books provide
50 power, allowing the third offer to require level 100. Book locations must be
valid: the chiseled shelves cannot occupy the same blocks as ordinary shelves.
Changing books or reloading rules refreshes open offers. A click against stale
offers is rejected before payment, allowing the player to review the new offer.

The displayed offer is the required player level, not the number of levels spent.
The three buttons spend 1/2/3 levels times `level-spend-multiplier`, rounded up,
and always use 1/2/3 lapis. Players must meet both the displayed requirement and
the configured payment. Default strongest offer: require 100, spend 3 levels and
3 lapis. A multiplier of 2 makes it spend 6 levels and 3 lapis.

Enchantment ranks use individual caps, with vanilla caps for unlisted identifiers.
Rank caps accept integers 1 through 255. Rank 100 is configurable but is not the
same as a level-100 table: vanilla minimum-cost curves still determine which
ranks the table can offer. Anvils can combine toward the configured cap. At high
library power, capped enchantments remain eligible rather than disappearing
when their vanilla maximum-cost window is exceeded. Protection pairing uses the survival exception described below; other exclusivity and
item applicability remain enforced. Loot-provider rank limits and command defaults
are unchanged. The Protection compatibility exception applies globally, including
loot-provider selection. Bukkit's enchantment registry max-level metadata remains upstream.

## Mending

XP orbs no longer repair armor, including armor held in either hand. Tools and
weapons retain XP-orb mending at `repair-multiplier: 0.5`. Whole durability budgets
round down; partial repairs charge XP rounded up. Cancelled mend events preserve
XP. Disabling the gameplay feature restores upstream behavior.
Setting `mending.armor.enabled: false` disables crouching regeneration and restores
armor eligibility for XP-orb repair at the configured XP repair multiplier.

Crouching while wearing damaged Mending armor spends XP from the player's bar to repair it, up to
30%/40%/50% of maximum remaining durability at ranks I/II/III. These percentages
are a remaining-durability ceiling, not an additional repair allowance per session.
Above that ceiling no regeneration occurs. Standing pauses regeneration. Each
piece repairs independently; there is no extra combat pause. Each durability
point restored costs one raw XP point by default (`xp-per-durability`, range
1â€“1000). The cost applies only to actual whole repairs. An empty bar or too few
points for one repair pauses repair, and all equipped armor shares the available
XP. Current levels and bar progress determine the balance, rather than the
historical `XpTotal` counter. Fractional time credit does not consume XP.

Default repair speed starts at 10% of one durability point per second and ramps
to one point per second over five minutes spent damaged while worn and online.
There is no initial delay by default. Fractional repair credit accumulates until a
whole durability point can be repaired. Inventory storage and offline time do not
advance damage age. Full repair resets the damage-age timer.

Each item stores a UUID and lifetime regeneration counter in custom data. Once
regeneration has repaired 2.5 times the item's maximum durability, it stops.
A netherite chestplate with 592 maximum durability exhausts after 1,480 repair
points. Actual anvil material repair or combining armor to improve durability
resets the output's counter and timer. Renaming or adding an enchanted book does
not reset them. Moving the item between players preserves its state.

## Curse of Vanishing

The curse no longer prevents its item from dropping on death. Wearing or holding
an enchanted item automatically grants 10/20/30 seconds of ordinary invisibility
at ranks I/II/III when the local light level is strictly below 1/3/5. The strongest
equipped rank is used. Multiple items do not stack durations. The 60-second
cooldown starts at activation and persists through logout. Existing invisibility
is not overwritten; potion effect cancellation is respected. Ordinary invisibility
still displays equipped armor and held items to other players.
Holding Shift does not bypass the darkness requirement or the cooldown. At
rank I, the local light must be zero; ranks II/III allow light 0â€“2/0â€“4.
Check the inventory's Invisibility effect or use another player's view to
confirm activation; seeing equipped armor does not mean the effect failed.

## Silk Touch, Protection, and Aqua Affinity

- Silk Touch I retains ordinary block harvesting. Silk Touch II has a 50% chance
  of recovering a spawner; Silk Touch III guarantees recovery. Recovered spawners
  retain their mob type. The survival mechanic does not require Purpur's separate
  silk-spawner permission/feature switch. Creative and cancelled/no-drop breaks
  must not duplicate spawners. Silk Touch/Fortune remains incompatible.
  Silk Touch II/III spawner attempts yield no default spawner XP, including a
  failed rank-II collection roll.
- Protection can combine with one secondary type: Fire Protection, Blast
  Protection, or Projectile Protection. Secondary protection types still conflict
  with each other. The upstream total damage-reduction ceiling remains in effect.
- Aqua Affinity I/II/III reduces the submerged mining penalty to 3.5x/2x/1x.
  Other mining modifiers, including the separate penalty for not standing on a
  block, remain in effect.

Higher caps alone do not redesign every enchantment's effects. For example,
Channeling and Flame retain their upstream effect definitions. The explicit
reworks above supply the requested additional behavior.
## Anvils

`remove-cost-limit: true` removes the normal 40-level rejection, including
repeated-work costs, while still charging the actual computed cost. Cumulative
repair penalties are not disabled; Purpur's existing cumulative-cost setting
controls them independently. Silk Touch/Fortune and other conflicts remain
enforced unless existing Purpur settings or plugins explicitly override them.
Normal rank combinations respect the configured caps; explicit upstream unsafe
rank overrides retain their behavior.

Vanilla clients hardcode the "Too Expensive" display at 40. The menu therefore
shows at most 39 while the action bar reports the actual higher cost. The server
checks and charges that actual cost, and Bukkit sees the actual value. This needs
real-client acceptance testing; it is not a client modification. An open result
from an older configuration is recomputed and its first pickup rejected.

For configured ranks above X, an optional resource pack may be needed for clean
client enchantment-level names. Server gameplay caps do not add client translations.

## Verification and manual acceptance

Run with JDK 25:

```powershell
.\gradlew.bat :purpur-server:test --tests org.nekopur.gameplay.SurvivalGameplayTestSuite --no-daemon
.\gradlew.bat :nekopur-server:createNekopurMojmapBundlerJar --no-daemon
```

Before deployment, use an unmodified release-26.3 client to verify:

- A normal library, a level-100 library, blocked gaps, and removal of stored books
  while the table is open. Confirm preview ranks agree with applied ranks.
- Configured XP spending, insufficient player XP, lapis payment, and creative mode.
- High-cost anvil combining, normal conflicts, caps, insufficient XP, exact payment,
  shift-click, and reopening the menu. Confirm the action bar explains the real cost.
- Mending with several damaged items, small/large XP orbs, cancelled mend events,
  plugin-edited repair amounts, and disabled rules.
- Plugin cancellation/replacement of enchanting and anvil results.
- Crouching armor regeneration at each rank, standing to pause, transferring an
  exhausted item, restarting, and actual anvil repair versus rename/book-only work.
- Vanishing at light 0/2/4 and the excluded boundaries 1/3/5, cooldown after
  relogging, existing invisibility, effect cancellation, and item drops on death.
- Silk Touch I/II/III spawner breaks, cancelled/no-drop and creative breaks,
  recovered mob type after placement, and normal block harvesting.
- Protection plus each secondary type, rejection of two secondary types, and
  Aqua Affinity submerged mining while grounded and while swimming.

Minecraft edits are exported into `purpur-server/minecraft-patches/sources`;
the generated Java edits alone are not the deliverable.

## Local validation

All 43 gameplay regression tests passed. Coverage includes real anvil shift-click
transfer/reset, actual XP-orb repairs, armor regeneration and persistent state,
configuration round trips, Protection compatibility, Aqua attribute synchronization,
and Vanishing activation/cancellation, thresholds, cooldown, and death-loss removal.

The Minecraft source patches were exported and successfully reapplied (232 source
patches total). All seven regenerated Minecraft files match the tested sources
after newline normalization. The runnable
`nekopur-server/build/libs/nekopur-bundler-26.3.local-SNAPSHOT-mojmap.jar` built
successfully and contains all six gameplay helpers. Its packaged server identifies
release 26.3 and protocol 777. SHA-256:
`ca05cbb179c9995ed0a3a59f2d87eaaca1081bf0a8263c3f9b5af357fb53c0e6`.

Real-client acceptance checks remain required. Logs are kept locally under
`.gradle/survival-reworks-validation`. On Windows, the loopback workaround is in
[the port notes](26.3_PORT.md#build-and-verification). For Git long-path failures,
set process-local `GIT_CONFIG_COUNT=1`, `GIT_CONFIG_KEY_0=core.longpaths`, and
`GIT_CONFIG_VALUE_0=true` when invoking Gradle.
