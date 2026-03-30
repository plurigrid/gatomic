(ns gatomic.schema
  "Gatomic schema: Gay color datoms + trit-ticks + topos integration.

   gay:    deterministic color from SplitMix64
   atomic: the 30 sub-atomics as entity attributes
   datomic: immutable facts, time-travel, speculative d/with

   #41C58F trit=0 (ergodic coordinator)")

(def color-schema
  [;; Color entity attributes
   {:db/ident       :color/seed
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Seed used to generate this color"}

   {:db/ident       :color/index
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Index in the color stream"}

   {:db/ident       :color/hex
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Hex color string e.g. #E67F86"}

   {:db/ident       :color/r
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :color/g
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :color/b
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :color/trit
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "GF(3) trit: -1, 0, or +1"}

   {:db/ident       :color/genesis
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc         "True if this is a genesis chain color"}

   ;; Reafference check results
   {:db/ident       :reafference/seed
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :reafference/index
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :reafference/result
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":reafference/match or :reafference/mismatch"}

   ;; Stream lineage
   {:db/ident       :stream/parent-seed
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :stream/child-seed
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :stream/index
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   ;; Unique identity for color lookup
   {:db/ident       :color/uid
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "seed:index compound key for upsert"}])

;; ═══ TRIT-TICK SCHEMA ═══
;; Each gorj repl_eval is a trit-tick datom.
;; The datom [entity attribute value tx] IS the trit-tick [site atomic trit sweep].

(def trit-tick-schema
  [{:db/ident       :tick/uid
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "site:sweep compound key"}

   {:db/ident       :tick/site
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Port / lattice position"}

   {:db/ident       :tick/sweep
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Monotonic eval counter"}

   {:db/ident       :tick/s-old
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Previous trit {-1, 0, +1}"}

   {:db/ident       :tick/s-new
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "New trit {-1, 0, +1}"}

   {:db/ident       :tick/mu
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         "Moebius value {-1, 0, +1}. 0 = non-invertible (flickering emergence)"}

   {:db/ident       :tick/flicker
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/doc         ":emergence | :collapse | :persistent-shadow | :steady | :flip"}

   {:db/ident       :tick/color
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "#RRGGBB of the transition (not the state)"}

   {:db/ident       :tick/tau
    :db/valueType   :db.type/double
    :db/cardinality :db.cardinality/one
    :db/doc         "Running lag-1 autocorrelation at this sweep"}

   {:db/ident       :tick/drand-round
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "drand beacon round for this tick's entropy"}

   ;; Entropy sources folded into this tick
   {:db/ident       :tick/entropy-sources
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc         "Tags: :drand :battery :urandom :jitter etc"}])

;; ═══ OPEN GAME SCHEMA ═══
;; The 6 non-invertible open games on {bot,e,top}^2

(def open-game-schema
  [{:db/ident       :game/id
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         ":G1 through :G6"}

   {:db/ident       :game/color
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :game/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":prisoner-dilemma :coordination :stag-hunt :matching-pennies"}

   {:db/ident       :game/player-a
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Product lattice element e.g. (bot,top)"}

   {:db/ident       :game/player-b
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :game/balancing-trit
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident       :game/ped-atom
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         ":synergy :unique :redundancy (Varley PED classification)"}

   ;; Reference to the tick that activated this game
   {:db/ident       :game/activated-by
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "Trit-ticks where this game was active (mu=0)"}])

;; ═══ TOPOS SCHEMA ═══
;; CatColab DOTS integration: things, sub-things, glue

(def topos-schema
  [{:db/ident       :topos/theory
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Double theory name: :blume-capel :heyting :flowmaps etc"}

   {:db/ident       :topos/model
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc         "References to entities that are models of this theory"}

   {:db/ident       :topos/composition
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Glue instruction: one of the 9 natural transformations"}

   {:db/ident       :topos/source
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Source of a composition morphism"}

   {:db/ident       :topos/target
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc         "Target of a composition morphism"}

   ;; The 9 natural transformations as enum values
   {:db/ident :nat/bind}
   {:db/ident :nat/certify}
   {:db/ident :nat/compare}
   {:db/ident :nat/emit}
   {:db/ident :nat/filter}
   {:db/ident :nat/publish}
   {:db/ident :nat/select}
   {:db/ident :nat/stage}
   {:db/ident :nat/transform}])

(def full-schema
  (vec (concat color-schema trit-tick-schema open-game-schema topos-schema)))
