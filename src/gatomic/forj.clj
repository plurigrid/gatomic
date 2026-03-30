(ns gatomic.forj
  "forj: 26 gorj terminals (a-z), each a time-traveling trit-tick stream.

   Each terminal is an independent gorj instance with:
   - Its own seed (derived from letter index via SplitMix64)
   - Its own Datomic database (time travel via d/as-of, d/since, d/history)
   - Its own sonification voice (Overtone bus + pan position in the stereo field)
   - MCP-addressable as forj/a through forj/z

   Time travel sonification: scrub any terminal backward/forward through its
   transaction history, hearing the trit-tick melody change. Fork timelines
   with d/with to hear counterfactual futures. Compare two terminals by
   superimposing their streams.

   Architecture:
     forj is to gorj what tmux is to a shell — a terminal multiplexer
     for trit-tick streams, where each terminal has its own past."
  (:require [datomic.api :as d]
            [gatomic.gorj :as gorj]
            [gatomic.db :as db]
            [gatomic.sonify :as sonify]
            [gatomic.color :as color]
            [gatomic.schema :as schema]
            [com.rpl.specter :as s]))

;; ---------------------------------------------------------------------------
;; Terminal registry: 26 gorj instances, a-z
;; ---------------------------------------------------------------------------

(def LETTERS (mapv char (range (int \a) (inc (int \z)))))

(defn letter->seed
  "Derive a deterministic seed for letter index 0-25.
   Each terminal gets a unique SplitMix64-derived seed from the genesis seed."
  [letter-idx]
  (let [base (unchecked-add (long color/SEED) (long (* letter-idx 7919)))]
    (color/splitmix64 base)))

(def terminal-seeds
  "Map of letter char -> seed. Computed once."
  (into {} (map-indexed (fn [i c] [c (letter->seed i)]) LETTERS)))

(defonce ^:private terminals (atom {}))

(defn- make-terminal
  "Create a single forj terminal for letter c."
  [c]
  (let [seed (get terminal-seeds c)
        uri  (str "datomic:mem://forj-" c "-" (System/currentTimeMillis))
        _    (d/create-database uri)
        conn (d/connect uri)]
    ;; Install schemas
    @(d/transact conn schema/color-schema)
    {:letter c
     :seed   seed
     :uri    uri
     :conn   conn
     :cursor (atom 0)      ; current sweep position
     :ticks  (atom [])     ; accumulated ticks
     :muted? (atom false)}))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Initialize all 26 terminals. Idempotent."
  []
  (doseq [c LETTERS]
    (when-not (get @terminals c)
      (swap! terminals assoc c (make-terminal c))))
  (println (str "forj: " (count @terminals) " terminals initialized (a-z)"))
  (keys @terminals))

(defn terminal
  "Get terminal by letter (char or keyword or string)."
  [id]
  (let [c (cond
            (char? id) id
            (keyword? id) (first (name id))
            (string? id)  (first id)
            :else (char id))]
    (get @terminals c)))

(defn all-terminals
  "Return all 26 terminals as a sorted seq."
  []
  (sort-by :letter (vals @terminals)))

;; ---------------------------------------------------------------------------
;; Tick generation & storage per terminal
;; ---------------------------------------------------------------------------

(defn advance!
  "Generate n trit-ticks on terminal id and store them in its Datomic DB.
   Returns the new ticks."
  [id n]
  (let [{:keys [seed conn cursor ticks]} (terminal id)
        start @cursor
        new-ticks (-> (gorj/generate-ticks seed start n)
                      gorj/flicker-classify)
        ;; Store in Datomic
        datoms (mapv (fn [tick]
                       (merge tick
                              {:tick/uid (str seed ":" (:tick/sweep tick))}))
                     new-ticks)]
    @(d/transact conn datoms)
    (swap! ticks into new-ticks)
    (swap! cursor + n)
    new-ticks))

(defn advance-all!
  "Advance all 26 terminals by n ticks each."
  [n]
  (doseq [c LETTERS]
    (advance! c n))
  (println (str "forj: advanced all 26 terminals by " n " ticks")))

;; ---------------------------------------------------------------------------
;; Time travel
;; ---------------------------------------------------------------------------

(defn as-of
  "Get terminal's tick state as of a Datomic transaction time.
   Returns ticks that existed at that point in time."
  [id t]
  (let [{:keys [conn]} (terminal id)
        db (d/as-of (d/db conn) t)]
    (d/q '[:find [(pull ?e [:tick/site :tick/sweep :tick/s-old :tick/s-new
                            :tick/mu :tick/flicker :tick/color :tick/tau]) ...]
           :where [?e :tick/sweep _]]
         db)))

(defn since
  "Get ticks added to terminal since transaction time t."
  [id t]
  (let [{:keys [conn]} (terminal id)
        db (d/since (d/db conn) t)]
    (d/q '[:find [(pull ?e [:tick/site :tick/sweep :tick/s-old :tick/s-new
                            :tick/mu :tick/flicker :tick/color :tick/tau]) ...]
           :where [?e :tick/sweep _]]
         db)))

(defn history
  "Full transaction history of a terminal's ticks."
  [id]
  (let [{:keys [conn]} (terminal id)
        db (d/history (d/db conn))]
    (d/q '[:find ?sweep ?s-new ?tx ?added
           :where
           [?e :tick/sweep ?sweep ?tx ?added]
           [?e :tick/s-new ?s-new]]
         db)))

(defn fork
  "Speculatively fork a terminal with additional tx-data.
   Returns {:db-after <speculative-db> :ticks <ticks-in-fork>}.
   Does NOT mutate the terminal."
  [id tx-data]
  (let [{:keys [conn]} (terminal id)
        result (d/with (d/db conn) tx-data)
        fdb (:db-after result)
        ticks (d/q '[:find [(pull ?e [:tick/site :tick/sweep :tick/s-old :tick/s-new
                                      :tick/mu :tick/flicker :tick/color :tick/tau]) ...]
                     :where [?e :tick/sweep _]]
                   fdb)]
    {:db-after fdb :ticks ticks}))

;; ---------------------------------------------------------------------------
;; Sonification: per-terminal voice assignment
;; ---------------------------------------------------------------------------

(defn terminal-pan
  "Stereo position for terminal. a=-1.0 (hard left) ... z=+1.0 (hard right).
   Maps 26 letters linearly across the stereo field."
  [c]
  (let [idx (.indexOf (str LETTERS) (str c))]
    (- (* 2.0 (/ idx 25.0)) 1.0)))

(defn terminal-root-freq
  "Root frequency for terminal. Spread across 2 octaves.
   a=55Hz (A1), z=220Hz (A3). Chromatic-ish spacing."
  [c]
  (let [idx (.indexOf (str LETTERS) (str c))]
    (* 55.0 (Math/pow 2.0 (* idx (/ 2.0 25.0))))))

(defn sonify-terminal!
  "Play current ticks of a terminal. Pan & root from letter position."
  ([id] (sonify-terminal! id nil))
  ([id {:keys [n] :or {n 12}}]
   (let [t (terminal id)
         ticks (if (empty? @(:ticks t))
                 (advance! id n)
                 (take-last n @(:ticks t)))
         pan (terminal-pan (:letter t))
         root (terminal-root-freq (:letter t))]
     (println (str "forj/" (:letter t)
                   " | seed=" (:seed t)
                   " | pan=" (format "%+.2f" pan)
                   " | root=" (format "%.1fHz" root)
                   " | ticks=" (count ticks)
                   " | balance=" (gorj/trit-balance (vec ticks))))
     (doseq [tick ticks]
       (let [freq (* root (get {-1 1.0, 0 3/2, 1 2.0} (:tick/s-new tick) 1.0))
             [a dd s r] (sonify/tick->env tick)
             cutoff (sonify/mu->cutoff (:tick/mu tick 0))
             rq (sonify/mu->rq (:tick/mu tick 0))]
         (sonify/trit-tick-voice
           :freq freq :amp 0.15
           :cutoff cutoff :rq rq
           :attack a :decay dd :sustain s :release r
           :pan pan :saw-mix 0.5)
         (Thread/sleep (long (sonify/tau->dur-ms (:tick/tau tick)))))))))

;; ---------------------------------------------------------------------------
;; Time-travel sonification
;; ---------------------------------------------------------------------------

(defn sonify-as-of!
  "Sonify a terminal's state at a past transaction time.
   Hear what the terminal sounded like at time t."
  [id t]
  (let [ticks (sort-by :tick/sweep (as-of id t))]
    (when (seq ticks)
      (println (str "forj/" (-> (terminal id) :letter)
                    " @ t=" t
                    " | " (count ticks) " ticks"))
      (sonify/play-sequence! (gorj/flicker-classify (vec ticks))))))

(defn sonify-since!
  "Sonify only the ticks added after time t. The 'diff' sound."
  [id t]
  (let [ticks (sort-by :tick/sweep (since id t))]
    (when (seq ticks)
      (println (str "forj/" (-> (terminal id) :letter)
                    " since t=" t
                    " | " (count ticks) " new ticks"))
      (sonify/play-sequence! (gorj/flicker-classify (vec ticks))))))

(defn sonify-fork!
  "Sonify a counterfactual fork of a terminal.
   tx-data describes the speculative mutations.
   Hear what WOULD happen without committing."
  [id tx-data]
  (let [{:keys [ticks]} (fork id tx-data)
        sorted (sort-by :tick/sweep ticks)]
    (println (str "forj/" (-> (terminal id) :letter)
                  " FORK | " (count sorted) " ticks in speculative timeline"))
    (sonify/play-sequence! (gorj/flicker-classify (vec sorted)))))

;; ---------------------------------------------------------------------------
;; Multi-terminal operations
;; ---------------------------------------------------------------------------

(defn superimpose!
  "Sonify two terminals simultaneously. Hear them in stereo."
  [id-a id-b & {:keys [n] :or {n 12}}]
  (let [fa (future (sonify-terminal! id-a {:n n}))
        fb (future (sonify-terminal! id-b {:n n}))]
    @fa @fb))

(defn cascade!
  "Play all 26 terminals in sequence, each for n ticks.
   A 26-letter musical alphabet."
  [& {:keys [n] :or {n 3}}]
  (doseq [c LETTERS]
    (when-not @(:muted? (terminal c))
      (sonify-terminal! c {:n n}))))

(defn chord!
  "Play a chord across selected terminals simultaneously.
   e.g., (chord! [\\a \\e \\i \\o \\u]) — vowel chord."
  [letters & {:keys [n] :or {n 6}}]
  (let [futs (doall (map (fn [c]
                           (future (sonify-terminal! c {:n n})))
                         letters))]
    (doseq [f futs] @f)))

(defn mute! [id] (reset! (:muted? (terminal id)) true))
(defn unmute! [id] (reset! (:muted? (terminal id)) false))

;; ---------------------------------------------------------------------------
;; GF(3) conservation across the ensemble
;; ---------------------------------------------------------------------------

(defn ensemble-balance
  "GF(3) balance across all 26 terminals."
  []
  (let [balances (for [c LETTERS
                       :let [t (terminal c)]
                       :when t]
                   {:letter c
                    :balance (gorj/trit-balance (vec @(:ticks t)))
                    :count (count @(:ticks t))})]
    {:per-terminal balances
     :total-balance (reduce + (map :balance balances))
     :total-ticks (reduce + (map :count balances))
     :conserves? (zero? (mod (reduce + (map :balance balances)) 3))}))

(defn ensemble-health
  "Harmonic health of the full 26-terminal ensemble."
  []
  (let [all-ticks (vec (mapcat (fn [c] @(:ticks (terminal c))) LETTERS))]
    (when (seq all-ticks)
      (merge (sonify/harmonic-health all-ticks)
             (ensemble-balance)))))

;; ---------------------------------------------------------------------------
;; MCP tool interface
;; ---------------------------------------------------------------------------

(defn forj-dispatch
  "MCP dispatch: forj/<letter> <action> [args].
   Actions: advance, play, as-of, since, fork, health, mute, unmute."
  [letter action & args]
  (case action
    "advance"  (advance! letter (or (first args) 12))
    "play"     (sonify-terminal! letter {:n (or (first args) 12)})
    "as-of"    (sonify-as-of! letter (first args))
    "since"    (sonify-since! letter (first args))
    "fork"     (sonify-fork! letter (first args))
    "health"   (let [t (terminal letter)
                     ticks (vec @(:ticks t))]
                 (when (seq ticks) (sonify/harmonic-health ticks)))
    "mute"     (mute! letter)
    "unmute"   (unmute! letter)
    "history"  (history letter)
    "balance"  {:letter letter
                :balance (gorj/trit-balance (vec @(:ticks (terminal letter))))
                :seed (:seed (terminal letter))}
    (throw (ex-info (str "Unknown forj action: " action) {:letter letter :action action}))))

;; ---------------------------------------------------------------------------
;; REPL examples
;; ---------------------------------------------------------------------------

(comment
  ;; Initialize all 26 terminals
  (init!)

  ;; Advance terminal 'a' by 12 ticks
  (advance! \a 12)

  ;; Play terminal 'a'
  (sonify-terminal! \a)

  ;; Advance all 26 by 6 ticks each
  (advance-all! 6)

  ;; Play a vowel chord
  (chord! [\a \e \i \o \u])

  ;; Time travel: hear terminal 'c' as it was 5 transactions ago
  ;; First, get current basis-t
  (let [conn (:conn (terminal \c))
        db (d/db conn)
        t (d/basis-t db)]
    ;; Advance more
    (advance! \c 12)
    ;; Now hear what it sounded like before
    (sonify-as-of! \c t))

  ;; Hear only the new ticks since a checkpoint
  (let [t (d/basis-t (d/db (:conn (terminal \d))))]
    (advance! \d 24)
    (sonify-since! \d t))

  ;; Counterfactual: what if terminal 'z' had all trits flipped?
  (sonify-fork! \z
    [[:db/add [:tick/uid "some-uid"] :tick/s-new -1]])

  ;; Cascade through all 26
  (cascade! :n 3)

  ;; Full ensemble health
  (ensemble-health)

  ;; Superimpose a and z (hard left + hard right)
  (superimpose! \a \z :n 12)

  ;; MCP-style dispatch
  (forj-dispatch \m "advance" 12)
  (forj-dispatch \m "play" 6)
  (forj-dispatch \m "balance"))
