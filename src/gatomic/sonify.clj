(ns gatomic.sonify
  "Trit-tick sonification via Overtone.
   Each tick becomes a note; GF(3) balance becomes harmony.

   Mapping (gorj trit-tick table):
     :tick/s-new  → pitch class (fundamental / 5th / octave)
     :tick/mu     → filter cutoff (mu=0 gets resonant sweep)
     :tick/flicker → envelope (emergence=attack, collapse=release, etc.)
     :tick/color  → stereo pan (hue 0-360° → -1..+1)
     :tick/tau    → note duration (WLC residence time)
     :tick/sweep  → sequence position (tempo/rhythm driver)"
  (:require [overtone.live :refer :all]
            [datomic.api :as d]
            [gatomic.gorj :as gorj]
            [gatomic.color :as color]
            [com.rpl.specter :as s]))

;; ---------------------------------------------------------------------------
;; Pitch: GF(3) → overtone series
;; ---------------------------------------------------------------------------

(def trit->freq
  "Three pitch classes from the overtone series.
   -1 = fundamental (C3), 0 = perfect 5th (G3), +1 = octave (C4).
   Rooted at 130.81 Hz (C3) — low enough for the undertow."
  {-1 130.81   ; fundamental
    0 196.00   ; perfect 5th (3:2 ratio)
    1 261.63}) ; octave (2:1 ratio)

(def scale-ratios
  "Extended ratios for melodic variation within each trit class.
   Each trit selects a root; tau fractional part picks a degree."
  {-1 [1 9/8 5/4]       ; major triad from fundamental
    0 [3/2 5/3 15/8]    ; from the 5th: 5th, 6th, 7th
    1 [2 9/4 5/2]})     ; from octave: oct, 9th, 10th

(defn tick->freq
  "Map a trit-tick to a frequency. Base from trit, degree from tau."
  [{:tick/keys [s-new tau]}]
  (let [base (get trit->freq s-new 196.0)
        ratios (get scale-ratios s-new [1])
        degree (mod (int (or tau 0)) (count ratios))
        ratio (nth ratios degree)]
    (* 130.81 ratio)))

;; ---------------------------------------------------------------------------
;; Filter: mu → cutoff
;; ---------------------------------------------------------------------------

(defn mu->cutoff
  "Map mu to filter cutoff frequency.
   mu=0 (non-invertible, open game activation) → resonant low sweep.
   |mu|=1 → wide open. Sign sets resonance direction."
  [mu]
  (case (long mu)
    0  400    ; resonant low-pass — harmonic tension
    1  8000   ; bright, open
    -1 2000   ; mid, slightly dark
    4000))    ; default

(defn mu->rq
  "Resonance (reciprocal Q). mu=0 gets sharp resonance."
  [mu]
  (if (zero? mu) 0.1 0.5))

;; ---------------------------------------------------------------------------
;; Envelope: flicker type → ADSR
;; ---------------------------------------------------------------------------

(def flicker->env
  "Map flicker classification to [attack decay sustain release] in seconds.
   :emergence  = sharp attack (note appears from nothing)
   :collapse   = long release (note fades to nothing)
   :steady     = sustained (held tone)
   :flip       = percussive (trit sign change)
   :persistent-shadow = drone (0→0, the void hum)"
  {:emergence         [0.01  0.1  0.8  0.3]
   :collapse          [0.05  0.1  0.6  1.5]
   :steady            [0.1   0.2  0.9  0.5]
   :flip              [0.005 0.05 0.3  0.1]
   :persistent-shadow [0.5   0.3  1.0  2.0]})

(defn tick->env
  "Extract ADSR from a tick's flicker type."
  [{:tick/keys [flicker]}]
  (get flicker->env (or flicker :steady) [0.1 0.2 0.7 0.5]))

;; ---------------------------------------------------------------------------
;; Pan: color hex → stereo position
;; ---------------------------------------------------------------------------

(defn hex->hue
  "Extract approximate hue (0-360) from a hex color string like '#A3F0B2'.
   Uses the max-channel method for a rough hue."
  [hex-str]
  (when (and hex-str (>= (count hex-str) 6))
    (let [hex (clojure.string/replace hex-str "#" "")
          r (/ (Integer/parseInt (subs hex 0 2) 16) 255.0)
          g (/ (Integer/parseInt (subs hex 2 4) 16) 255.0)
          b (/ (Integer/parseInt (subs hex 4 6) 16) 255.0)
          mx (max r g b)
          mn (min r g b)
          d (- mx mn)]
      (if (zero? d)
        0.0
        (let [h (cond
                  (= mx r) (/ (mod (/ (- g b) d) 6) 6.0)
                  (= mx g) (/ (+ (/ (- b r) d) 2) 6.0)
                  :else     (/ (+ (/ (- r g) d) 4) 6.0))]
          (* h 360.0))))))

(defn color->pan
  "Map hex color to stereo pan (-1 to +1).
   Hue 0° = hard left, 180° = hard right, wraps."
  [hex-str]
  (if-let [hue (hex->hue hex-str)]
    (- (* 2.0 (/ hue 360.0)) 1.0)
    0.0))

;; ---------------------------------------------------------------------------
;; Duration: tau → note length
;; ---------------------------------------------------------------------------

(def ^:const BASE-TEMPO 120.0)  ; BPM
(def ^:const BEAT-MS (/ 60000.0 BASE-TEMPO))

(defn tau->dur-ms
  "Map WLC residence time (tau) to note duration in ms.
   tau=0 or nil → one beat. Otherwise scale logarithmically."
  [tau]
  (if (or (nil? tau) (zero? tau))
    BEAT-MS
    (* BEAT-MS (max 0.25 (min 4.0 (Math/log1p (Math/abs tau)))))))

;; ---------------------------------------------------------------------------
;; Synth: the trit-tick instrument
;; ---------------------------------------------------------------------------

(definst trit-tick-voice
  "A single trit-tick voice. Subtractive synth with:
   - saw + pulse oscillators (trit selects mix)
   - resonant low-pass filter (mu controls cutoff)
   - ADSR envelope (flicker type)
   - stereo pan (color hue)"
  [freq 220 amp 0.3
   cutoff 4000 rq 0.5
   attack 0.1 decay 0.2 sustain 0.7 release 0.5
   pan 0.0 gate 1
   saw-mix 0.5]
  (let [osc (+ (* saw-mix (saw freq))
               (* (- 1 saw-mix) (pulse freq 0.3)))
        filt (rlpf osc cutoff rq)
        env (env-gen (adsr attack decay sustain release)
                     :gate gate :action FREE)]
    (pan2 (* filt env amp) pan)))

;; ---------------------------------------------------------------------------
;; Playback: tick sequence → sound
;; ---------------------------------------------------------------------------

(defn play-tick!
  "Sonify a single trit-tick. Returns the synth node."
  [tick]
  (let [freq (tick->freq tick)
        [a d s r] (tick->env tick)
        cutoff (mu->cutoff (:tick/mu tick 0))
        rq (mu->rq (:tick/mu tick 0))
        pan (color->pan (:tick/color tick))
        saw-mix (case (long (:tick/s-new tick 0))
                  -1 0.8   ; fundamental = mostly saw (warm)
                   0 0.5   ; 5th = balanced
                   1 0.2   ; octave = mostly pulse (bright)
                   0.5)]
    (trit-tick-voice
      :freq freq :amp 0.3
      :cutoff cutoff :rq rq
      :attack a :decay d :sustain s :release r
      :pan pan :saw-mix saw-mix)))

(defn play-sequence!
  "Play a sequence of trit-ticks with timing from :tick/tau.
   Blocking — plays in the calling thread."
  [ticks]
  (let [annotated (gorj/flicker-classify ticks)]
    (doseq [tick annotated]
      (play-tick! tick)
      (Thread/sleep (long (tau->dur-ms (:tick/tau tick)))))))

(defn play-gorj!
  "Generate n trit-ticks from seed and play them.
   The canonical entry point for sonification."
  ([n] (play-gorj! color/SEED n))
  ([seed n]
   (let [ticks (-> (gorj/generate-ticks seed 0 n)
                   gorj/flicker-classify)]
     (println (str "Playing " n " trit-ticks from seed " seed))
     (println (str "  GF(3) balance: " (gorj/trit-balance ticks)))
     (println (str "  SPI fingerprint: " (:hex (gorj/spi-verify-ticks ticks))))
     (play-sequence! ticks))))

;; ---------------------------------------------------------------------------
;; Bumpus-Kocsis harmonic monitor
;; ---------------------------------------------------------------------------

(defn lem-fraction
  "Fraction of ticks that are 'complemented' (mu != 0).
   Bumpus-Kocsis: this should be <= 2/3 in non-Boolean regimes."
  [ticks]
  (let [total (count ticks)
        complemented (count (remove #(zero? (:tick/mu % 0)) ticks))]
    (if (pos? total)
      (/ (double complemented) total)
      0.0)))

(defn harmonic-health
  "Report harmonic health of a tick sequence against Bumpus-Kocsis.
   Returns a map with LEM fraction, bound status, and GF(3) balance."
  [ticks]
  (let [lem (lem-fraction ticks)
        balance (gorj/trit-balance ticks)
        spi (gorj/spi-verify-ticks ticks)]
    {:lem-fraction lem
     :within-bound? (<= lem (/ 2.0 3.0))
     :gf3-balance balance
     :gf3-balanced? (zero? (mod balance 3))
     :spi-fingerprint (:hex spi)
     :spi-balanced? (:balanced? spi)
     :tick-count (count ticks)}))

;; ---------------------------------------------------------------------------
;; Time-travel sonification
;; ---------------------------------------------------------------------------

(defn version-depth
  "Depth of a transaction in the Datomic log. Used as chromatic step."
  [db tx-id]
  (count (d/tx-range (d/log (d/entity-db db)) nil tx-id)))

(defn rewind-sequence
  "Get trit-ticks as they existed at a past transaction.
   Uses d/as-of for true time travel — not reconstruction."
  [conn tx-id]
  (let [db (d/as-of (d/db conn) tx-id)]
    (->> (d/q '[:find [(pull ?e [:tick/site :tick/sweep :tick/s-old
                                 :tick/s-new :tick/mu :tick/color :tick/tau]) ...]
                :where [?e :tick/s-new]]
              db)
         (sort-by :tick/sweep))))

(defn play-rewind!
  "Sonify a rewind: play the tick sequence backwards with subtractive timbre.
   Square wave replaces saw — time-reversed = subtractive."
  [ticks]
  (let [reversed (reverse (gorj/flicker-classify ticks))]
    (doseq [tick reversed]
      (let [freq (tick->freq tick)
            [a d s r] (tick->env tick)
            cutoff (mu->cutoff (:tick/mu tick 0))
            pan (color->pan (:tick/color tick))]
        ;; saw-mix=0.0 → pure pulse (subtractive, time-reversed timbre)
        (trit-tick-voice
          :freq freq :amp 0.25
          :cutoff cutoff :rq 0.15
          :attack r :decay d :sustain s :release a  ; reversed ADSR!
          :pan (- pan) :saw-mix 0.0))               ; reversed pan too
      (Thread/sleep (long (tau->dur-ms (:tick/tau tick)))))))

(defn play-merge-chord!
  "Sonify a merge: play all branch tips simultaneously as a chord.
   branches = seq of tick sequences (one per branch)."
  [branches]
  (doseq [branch branches]
    (when-let [tip (last (gorj/flicker-classify branch))]
      (let [freq (tick->freq tip)
            pan (color->pan (:tick/color tip))]
        (trit-tick-voice
          :freq freq :amp (/ 0.4 (count branches))
          :cutoff 6000 :rq 0.3
          :attack 0.05 :decay 0.3 :sustain 0.9 :release 1.5
          :pan pan :saw-mix 0.5)))))

(defn play-checkpoint-triad!
  "Sonify a GF(3) balanced checkpoint — the trit triad.
   All three pitch classes play simultaneously = resolved harmony."
  []
  (doseq [[trit mix] [[-1 0.8] [0 0.5] [1 0.2]]]
    (trit-tick-voice
      :freq (trit->freq trit) :amp 0.25
      :cutoff 5000 :rq 0.4
      :attack 0.1 :decay 0.3 :sustain 0.8 :release 2.0
      :pan (- (* 0.6 trit)) :saw-mix mix)))

(defn scrub-history!
  "Scrub through Datomic transaction history sonically.
   Each tx → one note. Speed multiplier controls tempo.
   speed=1.0 → real-time proportional, speed=4.0 → 4x faster."
  [conn & [{:keys [speed limit] :or {speed 2.0 limit 50}}]]
  (let [log (d/log (d/db conn))
        txs (take limit (d/tx-range log nil nil))]
    (doseq [{:keys [t data]} txs]
      (let [db-at (d/as-of (d/db conn) t)
            ;; Count trit-tick datoms at this point in time
            tick-count (or (d/q '[:find (count ?e) .
                                  :where [?e :tick/s-new]] db-at) 0)
            ;; Depth → chromatic pitch, count → volume
            freq (* 130.81 (Math/pow 1.059463 (mod tick-count 24)))
            amp (min 0.4 (* 0.05 (Math/sqrt (max 1 tick-count))))]
        (trit-tick-voice
          :freq freq :amp amp
          :cutoff 3000 :rq 0.3
          :attack 0.02 :decay 0.1 :sustain 0.5 :release 0.3
          :pan 0.0 :saw-mix 0.5)
        (Thread/sleep (long (/ BEAT-MS speed)))))))

(defn conflict-tone!
  "Sonify a CRDT conflict — tritone interval (the devil's interval).
   Two frequencies a tritone apart, detuned, with harsh resonance."
  [tick]
  (let [freq (tick->freq tick)]
    ;; Root
    (trit-tick-voice :freq freq :amp 0.2
                     :cutoff 1500 :rq 0.08
                     :attack 0.01 :decay 0.05 :sustain 0.4 :release 0.8
                     :pan -0.5 :saw-mix 0.9)
    ;; Tritone (√2 ≈ 1.4142, the irrational interval)
    (trit-tick-voice :freq (* freq 1.4142) :amp 0.2
                     :cutoff 1200 :rq 0.08
                     :attack 0.01 :decay 0.05 :sustain 0.4 :release 0.8
                     :pan 0.5 :saw-mix 0.9)))

(comment
  ;; Generate and play 12 trit-ticks
  (play-gorj! 12)

  ;; Play 69 ticks (structural fixed point)
  (play-gorj! 69)

  ;; Check harmonic health
  (harmonic-health (gorj/generate-ticks 69))

  ;; Play from a specific seed
  (play-gorj! 1069 24)

  ;; Manual: generate, inspect, then play
  (let [ticks (-> (gorj/generate-ticks 12)
                  gorj/flicker-classify)]
    (doseq [t ticks]
      (println (format "  trit=%+d mu=%+d flicker=%-20s color=%s freq=%.1f pan=%+.2f"
                       (:tick/s-new t) (:tick/mu t)
                       (name (:tick/flicker t)) (:tick/color t)
                       (tick->freq t) (color->pan (:tick/color t)))))
    (play-sequence! ticks))

  ;; Bumpus-Kocsis alert: check if we're in a non-Boolean regime
  (let [h (harmonic-health (gorj/generate-ticks 300))]
    (when-not (:within-bound? h)
      (println "ALERT: LEM fraction" (:lem-fraction h) "> 2/3"))
    h)

  ;; --- Time-travel sonification ---

  ;; Rewind: play the last 12 ticks backwards (subtractive timbre)
  (play-rewind! (gorj/generate-ticks 12))

  ;; Scrub through entire Datomic history as chromatic notes
  ;; (scrub-history! conn {:speed 4.0 :limit 30})

  ;; Merge chord: two branches converge
  (play-merge-chord! [(gorj/generate-ticks 1069 0 6)
                       (gorj/generate-ticks 420 0 6)])

  ;; GF(3) balanced checkpoint — the resolved triad
  (play-checkpoint-triad!)

  ;; Conflict: CRDT divergence → tritone dissonance
  (conflict-tone! (first (gorj/generate-ticks 1))))
