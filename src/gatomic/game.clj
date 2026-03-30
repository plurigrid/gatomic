(ns gatomic.game
  "Open games on acausal state via Datomic d/with.

   Key insight: d/with gives us speculative databases — the same DB value
   forked into counterfactual worlds without mutation. This IS the acausal
   state that open games need for equilibrium checking.

   An open game G : X → Y with S → R becomes:
     play:    (db, strategy) → db'    via d/with (speculative)
     coplay:  (db', outcome) → utility
     equilibrium: (db, strategy) → bool (best response check)

   The db is never mutated during game evaluation. Only when the game
   is *settled* does gatomic transact the winning strategy."
  (:require [datomic.api :as d]
            [com.rpl.specter :as s]
            [gatomic.color :as color]
            [gatomic.db :as db]
            [gatomic.gorj :as gorj]))

;; ---------------------------------------------------------------------------
;; Open Game protocol
;; ---------------------------------------------------------------------------

(defrecord OpenGame [play coplay equilibrium])

(defn sequential
  "G ; H — output of G feeds into H. Both acausal."
  [g h]
  (->OpenGame
   (fn [db strategy]
     (let [db' ((:play g) db strategy)]
       ((:play h) db' strategy)))
   (fn [db' outcome]
     (let [u-h ((:coplay h) db' outcome)]
       ((:coplay g) db' u-h)))
   (fn [db strategy]
     (and ((:equilibrium g) db strategy)
          ((:equilibrium h) db strategy)))))

(defn parallel
  "G ⊗ H — independent games, utilities combine."
  [g h]
  (->OpenGame
   (fn [db [strat-g strat-h]]
     (let [db-g ((:play g) db strat-g)
           db-h ((:play h) db strat-h)]
       ;; Both are speculative forks of the same db
       {:db-g db-g :db-h db-h}))
   (fn [dbs [out-g out-h]]
     {:g ((:coplay g) (:db-g dbs) out-g)
      :h ((:coplay h) (:db-h dbs) out-h)})
   (fn [db [strat-g strat-h]]
     (and ((:equilibrium g) db strat-g)
          ((:equilibrium h) db strat-h)))))

;; ---------------------------------------------------------------------------
;; Acausal primitives (d/with)
;; ---------------------------------------------------------------------------

(defn speculate
  "Fork db with speculative tx-data. Returns db-after. No side effects."
  [db tx-data]
  (:db-after (d/with db tx-data)))

(defn counterfactual-ticks
  "What if we played a different trit sequence? Returns speculative db."
  [db site ticks]
  (let [tx-data (mapv (fn [{:keys [tick/sweep tick/s-new tick/color] :as tick}]
                         {:tick/uid   (str site ":" sweep)
                          :tick/site  (long site)
                          :tick/sweep (long sweep)
                          :tick/s-new (long s-new)
                          :tick/color color})
                       ticks)]
    (speculate db tx-data)))

;; ---------------------------------------------------------------------------
;; The 6 non-invertible games G1-G6 on {bot,e,top}^2
;; ---------------------------------------------------------------------------
;; These activate at mu=0 trit-ticks (flickering emergence).
;; Each game checks whether a counterfactual strategy would have been better.

(defn trit-balance-in
  "GF(3) trit balance of a speculative db."
  [db]
  (or (d/q '[:find (sum ?t) .
             :where [_ :tick/s-new ?t]]
           db)
      0))

(defn mu-zero-count
  "Count non-invertible transitions in db."
  [db]
  (or (d/q '[:find (count ?e) .
             :where [?e :tick/mu 0]]
           db)
      0))

(defn emergence-game
  "G1: Emergence game. Activates when s-old=0, s-new!=0.
   Strategy: which trit to emerge into (+1 or -1).
   Utility: trit balance closer to 0 is better (ergodic pressure)."
  []
  (->OpenGame
   ;; play: speculatively set the emerging trit
   (fn [db {:keys [site sweep trit]}]
     (speculate db [{:tick/uid   (str site ":" sweep)
                     :tick/site  (long site)
                     :tick/sweep (long sweep)
                     :tick/s-old 0
                     :tick/s-new (long trit)
                     :tick/mu    0}]))
   ;; coplay: utility = how balanced the result is
   (fn [db' _outcome]
     (let [bal (trit-balance-in db')]
       {:balance bal
        :utility (- (Math/abs bal))}))
   ;; equilibrium: trit that minimizes |balance|
   (fn [db {:keys [site sweep trit]}]
     (let [db+1 (speculate db [{:tick/uid (str site ":" sweep)
                                :tick/s-new 1 :tick/s-old 0
                                :tick/site (long site) :tick/sweep (long sweep)
                                :tick/mu 0}])
           db-1 (speculate db [{:tick/uid (str site ":" sweep)
                                :tick/s-new -1 :tick/s-old 0
                                :tick/site (long site) :tick/sweep (long sweep)
                                :tick/mu 0}])
           bal+1 (Math/abs (double (trit-balance-in db+1)))
           bal-1 (Math/abs (double (trit-balance-in db-1)))]
       ;; In equilibrium if chosen trit is weakly better
       (if (= trit 1)
         (<= bal+1 bal-1)
         (<= bal-1 bal+1))))))

(defn collapse-game
  "G2: Collapse game. Activates when s-old!=0, s-new=0.
   The shadow persists. Utility: how many mu=0 transitions exist
   (fewer = more stable)."
  []
  (->OpenGame
   (fn [db {:keys [site sweep s-old]}]
     (speculate db [{:tick/uid   (str site ":" sweep)
                     :tick/site  (long site)
                     :tick/sweep (long sweep)
                     :tick/s-old (long s-old)
                     :tick/s-new 0
                     :tick/mu    0
                     :tick/flicker :collapse}]))
   (fn [db' _]
     (let [n (mu-zero-count db')]
       {:mu-zero-count n
        :utility (- n)}))
   (fn [db {:keys [site sweep s-old]}]
     ;; Collapse is always dominated by staying — check if staying is worse
     (let [db-stay (speculate db [{:tick/uid (str site ":" sweep)
                                   :tick/site (long site) :tick/sweep (long sweep)
                                   :tick/s-old (long s-old) :tick/s-new (long s-old)
                                   :tick/mu (long s-old)}])
           db-collapse (speculate db [{:tick/uid (str site ":" sweep)
                                       :tick/site (long site) :tick/sweep (long sweep)
                                       :tick/s-old (long s-old) :tick/s-new 0
                                       :tick/mu 0}])
           stay-bal (Math/abs (double (trit-balance-in db-stay)))
           coll-bal (Math/abs (double (trit-balance-in db-collapse)))]
       (<= coll-bal stay-bal)))))

(defn flip-game
  "G3: Flip game. s-old and s-new are opposite signs.
   Strategy: flip or stay. Utility: trit balance."
  []
  (->OpenGame
   (fn [db {:keys [site sweep s-old]}]
     (let [s-new (- s-old)]
       (speculate db [{:tick/uid   (str site ":" sweep)
                       :tick/site  (long site)
                       :tick/sweep (long sweep)
                       :tick/s-old (long s-old)
                       :tick/s-new (long s-new)
                       :tick/mu    (long (- s-new))
                       :tick/flicker :flip}])))
   (fn [db' _]
     {:balance (trit-balance-in db')
      :utility (- (Math/abs (double (trit-balance-in db'))))})
   (fn [db {:keys [site sweep s-old]}]
     (let [db-flip (speculate db [{:tick/uid (str site ":" sweep)
                                   :tick/s-old (long s-old) :tick/s-new (long (- s-old))
                                   :tick/site (long site) :tick/sweep (long sweep)
                                   :tick/mu (long s-old)}])
           db-stay (speculate db [{:tick/uid (str site ":" sweep)
                                   :tick/s-old (long s-old) :tick/s-new (long s-old)
                                   :tick/site (long site) :tick/sweep (long sweep)
                                   :tick/mu (long s-old)}])
           flip-bal (Math/abs (double (trit-balance-in db-flip)))
           stay-bal (Math/abs (double (trit-balance-in db-stay)))]
       (<= flip-bal stay-bal)))))

;; ---------------------------------------------------------------------------
;; Bumpus-Kocsis alert: LEM fraction
;; ---------------------------------------------------------------------------

(defn lem-fraction
  "Fraction of ticks where mu != 0 (Law of Excluded Middle holds).
   When this drops below 2/3, the lattice is in a flickering regime."
  [db]
  (let [total (or (d/q '[:find (count ?e) . :where [?e :tick/mu _]] db) 0)
        mu-zero (mu-zero-count db)]
    (if (zero? total)
      1.0
      (double (/ (- total mu-zero) total)))))

(defn bumpus-alert?
  "True when LEM fraction < 2/3 — too many non-invertible transitions."
  [db]
  (< (lem-fraction db) (/ 2.0 3.0)))

;; ---------------------------------------------------------------------------
;; Full game evaluation: gorj ticks → acausal open game → settle
;; ---------------------------------------------------------------------------

(defn evaluate-tick-game
  "Given a tick from gorj, select and evaluate the appropriate open game
   using acausal d/with. Returns the game result without committing."
  [db tick]
  (let [{:keys [tick/s-old tick/s-new tick/site tick/sweep]} tick
        strategy {:site site :sweep sweep :s-old s-old :trit s-new}]
    (cond
      ;; Emergence: 0 → nonzero
      (and (zero? s-old) (not (zero? s-new)))
      (let [g (emergence-game)]
        {:game :G1-emergence
         :db'  ((:play g) db strategy)
         :eq?  ((:equilibrium g) db strategy)})

      ;; Collapse: nonzero → 0
      (and (not (zero? s-old)) (zero? s-new))
      (let [g (collapse-game)]
        {:game :G2-collapse
         :db'  ((:play g) db strategy)
         :eq?  ((:equilibrium g) db strategy)})

      ;; Flip: sign change
      (and (not (zero? s-old)) (not (zero? s-new)) (not= s-old s-new))
      (let [g (flip-game)]
        {:game :G3-flip
         :db'  ((:play g) db strategy)
         :eq?  ((:equilibrium g) db strategy)})

      ;; Steady: no change
      :else
      {:game :steady :db' db :eq? true})))

(defn evaluate-sequence
  "Evaluate a gorj tick sequence as a series of open games.
   All acausal — threads speculative dbs through sequentially.
   Returns vector of game results + final Bumpus alert status."
  [db ticks]
  (let [classified (gorj/flicker-classify ticks)
        results (reduce
                 (fn [{:keys [db results]} tick]
                   (let [r (evaluate-tick-game db tick)
                         next-db (or (:db' r) db)]
                     {:db next-db
                      :results (conj results r)}))
                 {:db db :results []}
                 classified)]
    (assoc results :bumpus-alert? (bumpus-alert? (:db results)))))

(comment
  ;; Generate ticks, evaluate as open games against acausal DB
  ;; (requires a running gatomic DB)
  ;;
  ;; (def conn (db/create-db))
  ;; (db/transact-genesis! conn)
  ;; @(d/transact conn schema/trit-tick-schema)
  ;;
  ;; (def ticks (gorj/generate-ticks 12))
  ;; (def results (evaluate-sequence (d/db conn) ticks))
  ;;
  ;; ;; Which games activated?
  ;; (s/select [s/ALL :game] (:results results))
  ;;
  ;; ;; Which were in equilibrium?
  ;; (s/select [s/ALL (s/selected? :eq? true?) :game] (:results results))
  ;;
  ;; ;; Bumpus alert?
  ;; (:bumpus-alert? results)
  )
