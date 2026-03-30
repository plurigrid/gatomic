(ns gatomic.db
  "Datomic database operations for Gay color datoms."
  (:require [datomic.api :as d]
            [gatomic.schema :as schema]
            [gatomic.color :as color]))

;; ---------------------------------------------------------------------------
;; Connection management
;; ---------------------------------------------------------------------------

(defn create-db
  "Create a fresh in-memory Datomic database and return the connection."
  ([] (create-db (str "datomic:mem://gay-" (System/currentTimeMillis))))
  ([uri]
   (d/create-database uri)
   (let [conn (d/connect uri)]
     @(d/transact conn schema/color-schema)
     conn)))

(defn color-uid [seed index]
  (str seed ":" index))

;; ---------------------------------------------------------------------------
;; Transact colors
;; ---------------------------------------------------------------------------

(defn transact-color!
  "Generate and transact a single color datom. Returns the color map."
  [conn seed index]
  (let [c (color/color-at seed index)]
    @(d/transact conn
       [{:color/uid     (color-uid seed index)
         :color/seed    (long seed)
         :color/index   (long index)
         :color/hex     (:hex c)
         :color/r       (long (:r c))
         :color/g       (long (:g c))
         :color/b       (long (:b c))
         :color/trit    (long (:trit c))
         :color/genesis false}])
    c))

(defn transact-colors!
  "Generate and transact n colors starting at index start."
  [conn seed start n]
  (let [datoms (mapv (fn [i]
                       (let [c (color/color-at seed i)]
                         {:color/uid     (color-uid seed i)
                          :color/seed    (long seed)
                          :color/index   (long i)
                          :color/hex     (:hex c)
                          :color/r       (long (:r c))
                          :color/g       (long (:g c))
                          :color/b       (long (:b c))
                          :color/trit    (long (:trit c))
                          :color/genesis false}))
                     (range start (+ start n)))]
    @(d/transact conn datoms)
    datoms))

;; ---------------------------------------------------------------------------
;; Genesis
;; ---------------------------------------------------------------------------

(defn transact-genesis!
  "Transact the canonical 12 genesis colors at seed 1069."
  [conn]
  (let [datoms (mapv (fn [{:keys [index hex trit]}]
                       (let [c (color/color-at color/SEED index)]
                         {:color/uid     (color-uid color/SEED index)
                          :color/seed    (long color/SEED)
                          :color/index   (long index)
                          :color/hex     (:hex c)
                          :color/r       (long (:r c))
                          :color/g       (long (:g c))
                          :color/b       (long (:b c))
                          :color/trit    (long (:trit c))
                          :color/genesis true}))
                     color/genesis-colors)]
    @(d/transact conn datoms)
    :ok))

(defn verify-genesis-datoms
  "Verify genesis colors in the database match canonical values."
  [db]
  (every? (fn [{:keys [index hex trit]}]
            (let [result (d/q '[:find ?h ?t
                                :in $ ?seed ?idx
                                :where
                                [?e :color/seed ?seed]
                                [?e :color/index ?idx]
                                [?e :color/hex ?h]
                                [?e :color/trit ?t]
                                [?e :color/genesis true]]
                              db (long color/SEED) (long index))]
              (and (= 1 (count result))
                   (let [[h t] (first result)]
                     (and (= (clojure.string/upper-case hex)
                             (clojure.string/upper-case h))
                          (= trit t))))))
          color/genesis-colors))

;; ---------------------------------------------------------------------------
;; Reafference
;; ---------------------------------------------------------------------------

(defn reafference-check!
  "Atomic reafference: predict color, compare to DB, record result.
   If color doesn't exist yet, generates and stores it.
   If it exists, verifies prediction matches stored value.
   Returns :match, :mismatch, or :new."
  [conn seed index]
  (let [db        (d/db conn)
        predicted (color/color-at seed index)
        existing  (d/q '[:find ?hex .
                         :in $ ?uid
                         :where
                         [?e :color/uid ?uid]
                         [?e :color/hex ?hex]]
                       db (color-uid seed index))]
    (cond
      ;; First time: store the color
      (nil? existing)
      (do (transact-color! conn seed index)
          @(d/transact conn [{:reafference/seed   (long seed)
                              :reafference/index  (long index)
                              :reafference/result :reafference/new}])
          :new)

      ;; Reafference match
      (= (clojure.string/upper-case (:hex predicted))
         (clojure.string/upper-case existing))
      (do @(d/transact conn [{:reafference/seed   (long seed)
                              :reafference/index  (long index)
                              :reafference/result :reafference/match}])
          :match)

      ;; Exafference detected!
      :else
      (do @(d/transact conn [{:reafference/seed   (long seed)
                              :reafference/index  (long index)
                              :reafference/result :reafference/mismatch}])
          :mismatch))))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn colors-by-trit
  "Find all colors with a given trit value."
  [db trit]
  (d/q '[:find [(pull ?e [:color/hex :color/seed :color/index :color/trit]) ...]
         :in $ ?trit
         :where [?e :color/trit ?trit]]
       db (long trit)))

(defn trit-balance
  "Sum of all trit values in the database. 0 = balanced."
  [db]
  (or (d/q '[:find (sum ?t) .
             :where [_ :color/trit ?t]]
           db)
      0))

(defn colors-as-of
  "All colors as of a given transaction time."
  [db t]
  (d/q '[:find [(pull ?e [:color/hex :color/seed :color/index :color/trit]) ...]
         :where [?e :color/hex _]]
       (d/as-of db t)))

(defn color-history
  "Full history of a color entity (all assertions and retractions)."
  [db seed index]
  (d/q '[:find ?attr ?val ?tx ?added
         :in $ ?uid
         :where
         [?e :color/uid ?uid]
         [?e ?a ?val ?tx ?added]
         [?a :db/ident ?attr]]
       (d/history db) (color-uid seed index)))

;; ---------------------------------------------------------------------------
;; Streams (SPI)
;; ---------------------------------------------------------------------------

(defn split-seed!
  "Split a seed into n child streams and record the lineage."
  [conn parent-seed n]
  (let [children (mapv (fn [i]
                         {:stream/parent-seed (long parent-seed)
                          :stream/index       (long i)
                          :stream/child-seed  (long (color/splitmix64
                                                      (unchecked-add
                                                        (long parent-seed)
                                                        (long i))))})
                       (range n))]
    @(d/transact conn children)
    (mapv :stream/child-seed children)))

(defn stream-children
  "Find all child seeds of a parent."
  [db parent-seed]
  (d/q '[:find [?child ...]
         :in $ ?parent
         :where
         [?e :stream/parent-seed ?parent]
         [?e :stream/child-seed ?child]]
       db (long parent-seed)))

;; ---------------------------------------------------------------------------
;; Fault injection via d/with (speculative)
;; ---------------------------------------------------------------------------

(defn inject-fault
  "Speculatively inject a corrupted color and check if genesis still verifies.
   Returns {:faulted-db <db> :genesis-ok? <bool>}. Does NOT write to DB."
  [db seed index corrupted-hex]
  (let [uid     (color-uid seed index)
        tx-data [[:db/add [:color/uid uid] :color/hex corrupted-hex]]
        result  (d/with db tx-data)
        fdb     (:db-after result)]
    {:faulted-db fdb
     :genesis-ok? (verify-genesis-datoms fdb)}))
