(ns gatomic.gorj
  "gorj (+1 generate): Specter-powered trit-tick generation.

   Uses nathanmarz's Specter (Red Planet Labs) for bidirectional
   navigation of nested trit-tick structures. Each repl_eval becomes
   a trit-tick datom routed through Specter navigators."
  (:require [com.rpl.specter :as s]
            [gatomic.color :as color]))

;; ---------------------------------------------------------------------------
;; Specter navigators for trit-tick data
;; ---------------------------------------------------------------------------

(def TRIT
  "Navigate to the :trit field of any color/tick map."
  (s/path :trit))

(def COLOR-HEX
  "Navigate to the :hex field."
  (s/path :hex))

(def POSITIVE-TRIT
  "Select items with trit = +1."
  (s/filterer :trit #(= 1 %)))

(def NEGATIVE-TRIT
  "Select items with trit = -1."
  (s/filterer :trit #(= -1 %)))

(def ERGODIC-TRIT
  "Select items with trit = 0."
  (s/filterer :trit #(= 0 %)))

;; ---------------------------------------------------------------------------
;; Trit-tick generation
;; ---------------------------------------------------------------------------

(defn generate-ticks
  "Generate n trit-ticks from seed starting at index.
   Returns a vector of tick maps ready for gatomic transact."
  ([n] (generate-ticks color/SEED 0 n))
  ([seed start n]
   (mapv (fn [i]
           (let [c (color/color-at seed i)]
             {:tick/site  seed
              :tick/sweep i
              :tick/s-old (if (pos? i) (:trit (color/color-at seed (dec i))) 0)
              :tick/s-new (:trit c)
              :tick/color (:hex c)
              :tick/mu    (let [s-old (if (pos? i) (:trit (color/color-at seed (dec i))) 0)
                                s-new (:trit c)]
                            (cond
                              (= s-old s-new) s-new
                              (zero? s-old)   s-new
                              (zero? s-new)   0
                              :else           (- s-new)))}))
         (range start (+ start n)))))

(defn trit-balance
  "GF(3) balance of a tick sequence via Specter."
  [ticks]
  (reduce + (s/select [s/ALL :tick/s-new] ticks)))

(defn partition-by-trit
  "Partition ticks into {:plus [...] :zero [...] :minus [...]} via Specter."
  [ticks]
  {:plus  (s/select [s/ALL (s/selected? :tick/s-new #(= 1 %))] ticks)
   :zero  (s/select [s/ALL (s/selected? :tick/s-new #(= 0 %))] ticks)
   :minus (s/select [s/ALL (s/selected? :tick/s-new #(= -1 %))] ticks)})

(defn flicker-classify
  "Classify each tick's flicker type based on s-old -> s-new transition.
   Uses Specter transform to annotate in-place."
  [ticks]
  (s/transform [s/ALL]
               (fn [tick]
                 (let [s-old (:tick/s-old tick)
                       s-new (:tick/s-new tick)]
                   (assoc tick :tick/flicker
                          (cond
                            (and (zero? s-old) (not (zero? s-new))) :emergence
                            (and (not (zero? s-old)) (zero? s-new)) :collapse
                            (and (zero? s-old) (zero? s-new))       :persistent-shadow
                            (= s-old s-new)                         :steady
                            :else                                    :flip))))
               ticks))

(defn extract-colors
  "Pull all hex colors from a tick sequence via Specter."
  [ticks]
  (s/select [s/ALL :tick/color] ticks))

(defn mu-zero-ticks
  "Find non-invertible (mu=0) ticks -- where open games activate."
  [ticks]
  (s/select [s/ALL (s/selected? :tick/mu zero?)] ticks))

;; ---------------------------------------------------------------------------
;; SPI-race fingerprint (matches zig-syrup/src/wlc_reservoir.zig)
;; ---------------------------------------------------------------------------

(def ^:const ^long TRIT_BITS_MINUS (unchecked-long 0x5555555555555555))
(def ^:const ^long TRIT_BITS_ZERO  (unchecked-long 0xAAAAAAAAAAAAAAAA))
(def ^:const ^long TRIT_BITS_PLUS  (unchecked-long 0xFFFFFFFFFFFFFFFF))

(defn trit->bits ^long [^long t]
  (case t -1 TRIT_BITS_MINUS
          0  TRIT_BITS_ZERO
          1  TRIT_BITS_PLUS
          0))

(defn xor-fingerprint
  "XOR fingerprint of a trit sequence. Commutative + associative.
   Returns 0 iff the sequence is GF(3)-balanced over complete cycles.
   Matches wlc_reservoir.zig xorFingerprint exactly."
  ^long [trits]
  (reduce (fn ^long [^long acc t] (bit-xor acc (trit->bits (long t)))) 0 trits))

(defn spi-verify-ticks
  "SPI integrity check on a tick sequence. Returns fingerprint + diagnostics."
  [ticks]
  (let [trits (s/select [s/ALL :tick/s-new] ticks)
        fp (xor-fingerprint trits)
        n (count trits)]
    {:fingerprint fp
     :hex (format "%016x" fp)
     :balanced? (zero? fp)
     :visits n
     :complete-cycles? (zero? (mod n 3))}))

;; ---------------------------------------------------------------------------
;; WLC reservoir bridge (zig-syrup wlc/tick → gorj trit-tick)
;; ---------------------------------------------------------------------------

(defn wlc-tick->tick
  "Convert a WLC reservoir JSON-RPC notification to a gorj trit-tick.
   The saddle index becomes the site, transition_count becomes sweep,
   trit becomes s-new, residence_time becomes tau."
  [{:keys [saddle trit residence_time transition_count]}
   & [{:keys [prev-trit] :or {prev-trit 0}}]]
  (let [s-old (long prev-trit)
        s-new (long trit)]
    {:tick/site  (long saddle)
     :tick/sweep (long transition_count)
     :tick/s-old s-old
     :tick/s-new s-new
     :tick/color (get-in (color/color-at color/SEED saddle) [:hex])
     :tick/tau   (double residence_time)
     :tick/mu    (cond
                   (= s-old s-new) s-new
                   (zero? s-old)   s-new
                   (zero? s-new)   0
                   :else           (- s-new))}))

(defn wlc-sequence->ticks
  "Convert a sequence of WLC reservoir outputs to gorj trit-ticks.
   Threads the previous trit through for s-old computation."
  [wlc-outputs]
  (loop [outputs wlc-outputs
         prev-trit 0
         acc []]
    (if (empty? outputs)
      acc
      (let [tick (wlc-tick->tick (first outputs) {:prev-trit prev-trit})]
        (recur (rest outputs)
               (:tick/s-new tick)
               (conj acc tick))))))

(comment
  ;; Generate 12 ticks from genesis seed
  (def ticks (generate-ticks 12))

  ;; GF(3) balance
  (trit-balance ticks)

  ;; Partition by trit
  (partition-by-trit ticks)

  ;; Classify flicker types
  (flicker-classify ticks)

  ;; Extract just the colors
  (extract-colors ticks)

  ;; Non-invertible transitions (open game activation points)
  (mu-zero-ticks ticks)

  ;; Specter transform: shift all trits by +1 mod 3 (speculative)
  (s/transform [s/ALL :tick/s-new]
               (fn [t] (case t -1 0, 0 1, 1 -1))
               ticks))
