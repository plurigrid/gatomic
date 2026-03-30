# gatomic

gay + datomic + atomic = `#41C58F` (trit 0, ergodic coordinator)

Immutable fact store for trit-ticks. Every gorj `repl_eval` becomes a datom.
Every datom has a gay color. Time travel via `d/as-of`. Speculative MC via `d/with`.

## Schema (4 layers)

1. **color-schema**: Gay.jl SplitMix64 colors as datoms (seed, index, hex, trit)
2. **trit-tick-schema**: REPL eval transitions (site, sweep, s-old, s-new, mu, flicker, color, tau)
3. **open-game-schema**: The 6 non-invertible games G1-G6 on {bot,e,top}^2
4. **topos-schema**: CatColab DOTS integration (theory, model, composition, 9 natural transformations)

## Triad

```
gorj (+1 generate) → gatomic (0 relay) → topos (-1 verify)
```

gorj produces trit-ticks. gatomic stores them. topos checks composition.

## gorj + Specter (Red Planet Labs / nathanmarz)

gorj uses [Specter](https://github.com/redplanetlabs/specter) (`com.rpl/specter`) for bidirectional
navigation of trit-tick data. Specter's `ALL`, `MAP-VALS`, `select`, `transform` replace
hand-written recursive traversals when generating and shaping datoms.

The `:gorj` deps alias activates Specter.

## Invariants vs Desiderata (69 Steps)

Invariants are **hard gates** — violate one and the state is illegal, `d/with` rejects it.
Desiderata are **soft scores** — violation lowers utility but composition still holds.

| Invariant | What |
|-----------|------|
| `gf3-conservation` | trit sum = 0 mod 3 |
| `operadic-type-match` | no dangling game→tick refs |
| `fixed-point-69` | game #69 must be trit=0 (structural fixed point) |
| `lem-floor` | LEM fraction >= 1/3 |

| Desideratum | Weight | What |
|-------------|--------|------|
| `trit-equipartition` | 1.0 | 23/23/23 distribution |
| `low-mu-zero` | 0.5 | fewer non-invertible transitions |
| `balance-proximity` | 2.0 | |balance| → 0 |
| `flicker-diversity` | 0.5 | all 5 flicker types present |

`compare-strategies` evaluates two candidate tx-data acausally via `d/with`:
invariants gate, desiderata rank. See `gatomic.constraint`.

## Bumpus-Kocsis Alert

```clojure
;; Fires when LEM fraction drops below 2/3
[:find (count ?e) .
 :where [?e :tick/mu 0]]
```

## Shell Policy

Use gorj MCP tools for REPL interaction. gatomic runs as a JVM Clojure process (the `c` REPL).
