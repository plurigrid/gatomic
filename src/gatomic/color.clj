(ns gatomic.color
  "Deterministic color generation via SplitMix64.
   Matches portal/src/lib/engine/passport.ts (the live plurigrid.com implementation).

   Three pipelines coexist:
     1. passport.ts (STATE MACHINE): state += GOLDEN, mix64, hue-from-bits, HSL->RGB
        - Used by passport.gay identity system on plurigrid.com
        - Trit from hue: <120 -> +1, 120-240 -> 0, >=240 -> -1
     2. color-stream (BIJECTION): splitmix64(seed XOR index), hue via golden angle
        - Used by ColorStream fallback on homepage
     3. Gay MCP (OPAQUE): hosted service, canonical genesis #E67F86 etc.
        - Algorithm unknown, not reproducible from source

   This module implements pipeline 1 (passport) as primary, with pipeline 2
   available as `color-at-stream` for ColorStream compatibility."
  (:require [clojure.string :as str]))

(set! *unchecked-math* true)

;; SplitMix64 constants (Steele-Lea-Moler, identical across all implementations)
(def ^:const ^long GOLDEN (unchecked-long 0x9e3779b97f4a7c15))
(def ^:const ^long MIX1   (unchecked-long 0xbf58476d1ce4e5b9))
(def ^:const ^long MIX2   (unchecked-long 0x94d049bb133111eb))
(def ^:const ^long SEED   1069)
(def ^:const GOLDEN_ANGLE 137.508)

;; ---------------------------------------------------------------------------
;; Core SplitMix64
;; ---------------------------------------------------------------------------

(defn mix64
  "SplitMix64 output function (finalizer only, no state advance)."
  ^long [^long z]
  (let [z (long (* (bit-xor z (unsigned-bit-shift-right z 30)) MIX1))
        z (long (* (bit-xor z (unsigned-bit-shift-right z 27)) MIX2))]
    (bit-xor z (unsigned-bit-shift-right z 31))))

(defn splitmix64
  "Full SplitMix64 bijection: state += GOLDEN, then mix."
  ^long [^long x]
  (mix64 (long (+ x GOLDEN))))

;; ---------------------------------------------------------------------------
;; Pipeline 1: passport.ts (STATE MACHINE) -- the live algorithm on plurigrid.com
;; Matches portal/src/lib/engine/splitmix64.ts nextU64 + passport.ts resolveDidGay
;; ---------------------------------------------------------------------------

(defn next-u64
  "Advance SplitMix64 state by GOLDEN, return [output, new-state].
   Matches portal nextU64(s): state += gamma, z = mix64(state)."
  [^long state]
  (let [new-state (long (+ state GOLDEN))]
    [(mix64 new-state) new-state]))

(defn state-to-hue
  "Extract hue from u64 via golden angle. Matches portal stateToHue.
   bits[16:31] * 137.508 mod 360."
  ^double [^long value]
  (let [bits (bit-and (unsigned-bit-shift-right value 16) 0xFFFF)]
    (rem (* (double bits) GOLDEN_ANGLE) 360.0)))

(defn hue-to-trit
  "GF(3) trit from hue. Matches portal hueToTrit.
   <120 -> +1, 120-240 -> 0, >=240 -> -1."
  ^long [^double hue]
  (cond (< hue 120.0) 1
        (< hue 240.0) 0
        :else -1))

(defn hsl-to-rgb
  "HSL to RGB. Matches portal hueToHex with s=0.85 l=0.55."
  [^double h ^double s ^double l]
  (let [a (* s (Math/min l (- 1.0 l)))
        f (fn [^double n]
            (let [k (rem (+ n (/ h 30.0)) 12.0)]
              (- l (* a (Math/max (Math/min (Math/min (- k 3.0) (- 9.0 k)) 1.0) -1.0)))))]
    [(f 0.0) (f 8.0) (f 4.0)]))

(defn color-at
  "Deterministic color at (seed, index). Matches portal resolveDidGay.
   Advances SplitMix64 state (index+1) times from seed, extracts hue,
   converts to HSL(hue, 0.85, 0.55) -> RGB -> hex."
  ([^long index] (color-at SEED index))
  ([^long seed ^long index]
   (let [;; Advance state (index+1) times (portal: for i=0..index, nextU64)
         [val _] (loop [state (long seed) i 0 val 0]
                   (let [[v s] (next-u64 state)]
                     (if (= i index)
                       [v s]
                       (recur s (inc i) v))))
         hue  (state-to-hue val)
         trit (hue-to-trit hue)
         [rf gf bf] (hsl-to-rgb hue 0.85 0.55)
         r (Math/round (* rf 255.0))
         g (Math/round (* gf 255.0))
         b (Math/round (* bf 255.0))]
     {:r r :g g :b b
      :hex (format "#%02X%02X%02X" (int r) (int g) (int b))
      :hue (Math/round (* hue 10.0)) ;; store as hue*10 for precision
      :trit trit})))

;; ---------------------------------------------------------------------------
;; Pipeline 2: color-stream (BIJECTION) -- homepage fallback
;; Matches portal color-stream.svelte.ts computeHue
;; ---------------------------------------------------------------------------

(defn color-at-stream
  "ColorStream fallback algorithm: splitmix64(seed XOR index), golden angle hue."
  ([^long index] (color-at-stream SEED index))
  ([^long seed ^long index]
   (let [mixed (splitmix64 (bit-xor (long seed) (long index)))
         normalized (rem (Math/abs mixed) 1000)
         hue (rem (* (double normalized) GOLDEN_ANGLE) 360.0)
         trit (hue-to-trit hue)
         sat (Math/min 1.0 (+ 0.5 (/ (double (rem (Math/abs mixed) 256)) 853.0)))
         lit (Math/min 0.75 (Math/max 0.35 (+ 0.4 (/ (double (rem (Math/abs mixed) 40)) 100.0))))
         [rf gf bf] (hsl-to-rgb hue sat lit)
         r (Math/round (* rf 255.0))
         g (Math/round (* gf 255.0))
         b (Math/round (* bf 255.0))]
     {:r r :g g :b b
      :hex (format "#%02X%02X%02X" (int r) (int g) (int b))
      :hue (Math/round (* hue 10.0))
      :trit trit})))

(defn colors-at
  "Generate n colors starting at index start."
  ([n] (colors-at SEED 0 n))
  ([seed start n]
   (mapv #(color-at seed %) (range start (+ start n)))))

(defn palette
  "Generate a palette of n colors."
  ([n] (palette SEED n))
  ([seed n] (colors-at seed 0 n)))

;; Genesis chain: first 12 colors from passport algorithm (seed=1069, indices 0-11)
(def genesis-colors
  (mapv #(assoc (color-at SEED %) :index %)
        (range 0 12)))

;; MCP canonical genesis colors (from Gay MCP server, opaque algorithm)
(def mcp-genesis-colors
  [{:index 1  :hex "#E67F86" :trit  1}
   {:index 2  :hex "#D06546" :trit  0}
   {:index 3  :hex "#1316BB" :trit -1}
   {:index 4  :hex "#BA2645" :trit  1}
   {:index 5  :hex "#49EE54" :trit  1}
   {:index 6  :hex "#11C710" :trit  0}
   {:index 7  :hex "#76B0F0" :trit -1}
   {:index 8  :hex "#E59798" :trit  0}
   {:index 9  :hex "#5333D9" :trit -1}
   {:index 10 :hex "#7E90EB" :trit  0}
   {:index 11 :hex "#1D9E7E" :trit  0}
   {:index 12 :hex "#DD7CB0" :trit  1}])

(defn verify-genesis
  "Verify this implementation produces canonical genesis colors (self-consistency)."
  []
  (every? (fn [{:keys [index hex trit]}]
            (let [c (color-at SEED index)]
              (and (= (str/upper-case hex) (str/upper-case (:hex c)))
                   (= trit (:trit c)))))
          genesis-colors))
