(ns gatomic.constraint
  "Invariants vs desiderata for open game composition.

   Invariants are hard: if violated, the game state is illegal.
   Desiderata are soft: violation lowers utility but doesn't break composition.

   The key insight: d/with lets us check both BEFORE committing.
   Invariants gate the transaction. Desiderata shape the strategy selection."
  (:require [datomic.api :as d]
            [com.rpl.specter :as s]
            [gatomic.db :as db]
            [gatomic.game :as game]))

;; ---------------------------------------------------------------------------
;; Invariant protocol
;; ---------------------------------------------------------------------------

(defrecord Invariant [name check msg])
(defrecord Desideratum [name score weight msg])

(defn invariant
  "Create a hard constraint. check is (db -> bool). Violation = illegal state."
  [name check-fn msg]
  (->Invariant name check-fn msg))

(defn desideratum
  "Create a soft goal. score is (db -> double). Higher = better. Weight for composition."
  [name score-fn weight msg]
  (->Desideratum name score-fn weight msg))

;; ---------------------------------------------------------------------------
;; Core invariants (non-negotiable)
;; ---------------------------------------------------------------------------

(def gf3-conservation
  "GF(3) trit sum must be 0 mod 3. The fundamental conservation law."
  (invariant
   :gf3-conservation
   (fn [db]
     (let [bal (or (d/q '[:find (sum ?t) . :where [_ :tick/s-new ?t]] db) 0)]
       (zero? (mod bal 3))))
   "GF(3) trit balance violated: sum != 0 mod 3"))

(def operadic-type-match
  "Output types must match input types across composed games.
   In datomic terms: every :game/activated-by ref must point to an existing tick."
  (invariant
   :operadic-type-match
   (fn [db]
     (let [dangling (d/q '[:find (count ?ref) .
                           :where
                           [?g :game/activated-by ?ref]
                           (not [?ref :tick/uid _])]
                         db)]
       (or (nil? dangling) (zero? dangling))))
   "Dangling game activation ref: tick entity missing"))

(def fixed-point-69
  "Game #69 (self-fulfilling prophecy) is a structural fixed point.
   If it exists in the DB, its trit must be 0 (ergodic coordinator)."
  (invariant
   :fixed-point-69
   (fn [db]
     (let [g69-trit (d/q '[:find ?t .
                           :where
                           [?e :game/id :G69]
                           [?e :game/balancing-trit ?t]]
                         db)]
       (or (nil? g69-trit) (zero? g69-trit))))
   "Game #69 must be ergodic (trit=0): it's a fixed point"))

(def lem-floor
  "LEM fraction must stay >= 1/3. Below that, the lattice has lost
   too much structure to support open game composition."
  (invariant
   :lem-floor
   (fn [db]
     (>= (game/lem-fraction db) (/ 1.0 3.0)))
   "LEM fraction below 1/3: lattice structure insufficient"))

(def all-invariants
  [gf3-conservation operadic-type-match fixed-point-69 lem-floor])

;; ---------------------------------------------------------------------------
;; Core desiderata (soft goals)
;; ---------------------------------------------------------------------------

(def trit-equipartition
  "Trit counts should be as close to equal as possible (23/23/23 ideal)."
  (desideratum
   :trit-equipartition
   (fn [db]
     (let [counts (d/q '[:find ?t (count ?e)
                         :where [?e :tick/s-new ?t]]
                       db)
           freqs (into {} counts)
           n+ (get freqs 1 0)
           n0 (get freqs 0 0)
           n- (get freqs -1 0)
           total (+ n+ n0 n-)
           ideal (/ total 3.0)
           dev (+ (Math/abs (- n+ ideal))
                  (Math/abs (- n0 ideal))
                  (Math/abs (- n- ideal)))]
       (if (zero? total)
         1.0
         (- 1.0 (/ dev total)))))
   1.0
   "Trit distribution should approach 23/23/23 equipartition"))

(def low-mu-zero
  "Fewer mu=0 (non-invertible) transitions is more stable."
  (desideratum
   :low-mu-zero
   (fn [db]
     (let [total (or (d/q '[:find (count ?e) . :where [?e :tick/mu _]] db) 0)
           zeros (game/mu-zero-count db)]
       (if (zero? total)
         1.0
         (- 1.0 (/ (double zeros) total)))))
   0.5
   "Minimize non-invertible transitions for lattice stability"))

(def balance-proximity
  "Absolute trit balance should be close to 0."
  (desideratum
   :balance-proximity
   (fn [db]
     (let [bal (Math/abs (double (or (d/q '[:find (sum ?t) . :where [_ :tick/s-new ?t]] db) 0)))]
       (/ 1.0 (+ 1.0 bal))))
   2.0
   "Trit balance should be near zero"))

(def flicker-diversity
  "All 5 flicker types should be represented (emergence, collapse,
   persistent-shadow, steady, flip). Monoculture is fragile."
  (desideratum
   :flicker-diversity
   (fn [db]
     (let [types (d/q '[:find [?f ...] :where [_ :tick/flicker ?f]] db)
           n (count (set types))]
       (/ (double n) 5.0)))
   0.5
   "All 5 flicker types should be present"))

(def all-desiderata
  [trit-equipartition low-mu-zero balance-proximity flicker-diversity])

;; ---------------------------------------------------------------------------
;; Evaluation engine
;; ---------------------------------------------------------------------------

(defn check-invariants
  "Check all invariants against a (possibly speculative) db.
   Returns {:ok? bool :violations [{:name :msg}]}"
  [db]
  (let [violations (s/select [s/ALL (s/selected? #(not ((:check %) db)))]
                             all-invariants)]
    {:ok? (empty? violations)
     :violations (mapv #(select-keys % [:name :msg]) violations)}))

(defn score-desiderata
  "Score all desiderata against a db. Returns {:total double :scores [{:name :score :weighted}]}"
  [db]
  (let [scores (mapv (fn [d]
                       (let [raw ((:score d) db)
                             weighted (* raw (:weight d))]
                         {:name (:name d)
                          :score raw
                          :weighted weighted}))
                     all-desiderata)
        total (reduce + (map :weighted scores))]
    {:total total :scores scores}))

(defn evaluate-state
  "Full evaluation: invariants gate, desiderata rank.
   If any invariant is violated, the state is illegal regardless of desiderata score."
  [db]
  (let [inv (check-invariants db)
        des (score-desiderata db)]
    {:legal? (:ok? inv)
     :violations (:violations inv)
     :desiderata-total (:total des)
     :desiderata-scores (:scores des)}))

(defn compare-strategies
  "Given a db and two candidate tx-data, evaluate both acausally
   and return the better one (invariant-legal with higher desiderata score).
   Uses d/with — no side effects."
  [db tx-a tx-b]
  (let [db-a (game/speculate db tx-a)
        db-b (game/speculate db tx-b)
        eval-a (evaluate-state db-a)
        eval-b (evaluate-state db-b)]
    (cond
      ;; Both illegal — neither
      (and (not (:legal? eval-a)) (not (:legal? eval-b)))
      {:winner :neither :reason "both violate invariants"
       :a eval-a :b eval-b}

      ;; Only A legal
      (and (:legal? eval-a) (not (:legal? eval-b)))
      {:winner :a :reason "b violates invariants"
       :a eval-a :b eval-b}

      ;; Only B legal
      (and (not (:legal? eval-a)) (:legal? eval-b))
      {:winner :b :reason "a violates invariants"
       :a eval-a :b eval-b}

      ;; Both legal — compare desiderata
      :else
      (let [better (if (>= (:desiderata-total eval-a)
                            (:desiderata-total eval-b))
                     :a :b)]
        {:winner better
         :reason (format "both legal, %s scores %.3f vs %.3f"
                         (name better)
                         (:desiderata-total eval-a)
                         (:desiderata-total eval-b))
         :a eval-a :b eval-b}))))

(comment
  ;; Compare two strategies acausally:
  ;;
  ;; (def conn (db/create-db))
  ;; ;; ... setup ...
  ;;
  ;; (def tx-emerge-plus
  ;;   [{:tick/uid "1069:5" :tick/site 1069 :tick/sweep 5
  ;;     :tick/s-old 0 :tick/s-new 1 :tick/mu 0}])
  ;;
  ;; (def tx-emerge-minus
  ;;   [{:tick/uid "1069:5" :tick/site 1069 :tick/sweep 5
  ;;     :tick/s-old 0 :tick/s-new -1 :tick/mu 0}])
  ;;
  ;; (compare-strategies (d/db conn) tx-emerge-plus tx-emerge-minus)
  ;; => {:winner :a, :reason "both legal, a scores 3.142 vs 2.891", ...}
  )
