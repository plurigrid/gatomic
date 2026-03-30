(ns gatomic.wolfram
  "Wolframite bridge for gorj trit-tick sequences.
   Color #E9454F (wolframite-compass, trit 0, ergodic).

   Provides:
   - SystemModeler / Modelica simulation from trit-tick initial conditions
   - Wolfram numerical analysis (FFT, Lyapunov, bifurcation) on tick sequences
   - Compile'd hot paths for WLC reservoir analysis
   - GF(3) conservation verification via Wolfram's modular arithmetic"
  (:require [wolframite.core :as wl]
            [wolframite.wolfram :as w]
            [gatomic.gorj :as gorj]
            [gatomic.color :as color]
            [com.rpl.specter :as s]))

;; ---------------------------------------------------------------------------
;; Kernel lifecycle
;; ---------------------------------------------------------------------------

(defonce ^:private kernel-state (atom nil))

(defn start!
  "Start Wolfram kernel. Idempotent."
  []
  (when-not @kernel-state
    (wl/start!)
    (reset! kernel-state :running)
    :started))

(defn eval!
  "Evaluate a Wolfram expression. Starts kernel if needed."
  [expr]
  (start!)
  (wl/eval expr))

;; ---------------------------------------------------------------------------
;; Trit-tick -> Wolfram data
;; ---------------------------------------------------------------------------

(defn ticks->wl-list
  "Convert gorj trit-ticks to a Wolfram List of {s-old, s-new, mu, color-hue} tuples."
  [ticks]
  (w/List (mapv (fn [t]
                  (w/List [(:tick/s-old t)
                           (:tick/s-new t)
                           (:tick/mu t)
                           (double (/ (:tick/color t |0|) 10.0))]))
                ticks)))

(defn ticks->trit-series
  "Extract s-new series as Wolfram List for time-series analysis."
  [ticks]
  (w/List (s/select [s/ALL :tick/s-new] ticks)))

;; ---------------------------------------------------------------------------
;; GF(3) verification via Wolfram
;; ---------------------------------------------------------------------------

(defn wl-gf3-verify
  "Verify GF(3) conservation of a tick sequence using Wolfram modular arithmetic.
   Returns {:sum mod3-sum :balanced? bool}."
  [ticks]
  (let [trits (s/select [s/ALL :tick/s-new] ticks)
        result (eval! (w/Mod (apply w/Plus trits) 3))]
    {:sum result
     :balanced? (zero? result)
     :n (count trits)}))

;; ---------------------------------------------------------------------------
;; Spectral analysis
;; ---------------------------------------------------------------------------

(defn trit-spectrum
  "FFT of trit sequence. Returns power spectrum as Clojure vector."
  [ticks]
  (let [trits (s/select [s/ALL :tick/s-new] ticks)]
    (eval! (w/Abs (w/Fourier (w/List trits))))))

(defn trit-autocorrelation
  "Autocorrelation of trit sequence — reveals periodicity in GF(3) dynamics."
  [ticks]
  (let [trits (s/select [s/ALL :tick/s-new] ticks)]
    (eval! (w/CorrelationFunction (w/List trits)
                                  (w/List (range 1 (min 50 (count trits))))))))

;; ---------------------------------------------------------------------------
;; Modelica / SystemModeler bridge
;; ---------------------------------------------------------------------------

(defn wsm-simulate
  "Run a Modelica simulation via Wolfram SystemModeler.
   model-path: fully qualified Modelica model name (e.g. \"Modelica.Blocks.Sources.Sine\")
   params: map of parameter overrides
   t-end: simulation end time"
  [model-path params t-end]
  (eval! (w/WSMSimulate
          model-path
          (w/List 0 t-end)
          (w/Rule (w/WSMInitialValues)
                  (w/Association
                   (mapv (fn [[k v]] (w/Rule (str k) v)) params))))))

(defn simulate-wlc-modelica
  "Simulate a 3-species WLC (Winnerless Competition) in Modelica.
   Uses Lotka-Volterra competitive dynamics matching zig-syrup/wlc_reservoir.zig.
   rho: inhibition strength (default 2.5, matching Zig impl)
   dt: timestep, t-end: duration"
  ([] (simulate-wlc-modelica 2.5 0.005 10.0))
  ([rho dt t-end]
   (eval!
    '(Module [result]
       ;; Define the WLC ODE system matching wlc_reservoir.zig
       (Set result
            (NDSolve
             [(== (D (x1 t) t) (* (x1 t) (- 1 (x1 t) (* ~rho (x2 t)) (* ~rho (x3 t)))))
              (== (D (x2 t) t) (* (x2 t) (- 1 (x2 t) (* ~rho (x3 t)) (* ~rho (x1 t)))))
              (== (D (x3 t) t) (* (x3 t) (- 1 (x3 t) (* ~rho (x1 t)) (* ~rho (x2 t)))))
              (== (x1 0) 0.5)
              (== (x2 0) 0.3)
              (== (x3 0) 0.2)]
             [(x1) (x2) (x3)]
             [t 0 ~t-end]
             (Rule MaxStepSize ~dt)])
       result))))

;; ---------------------------------------------------------------------------
;; Compiled hot path for real-time trit classification
;; ---------------------------------------------------------------------------

(defn compile-trit-classifier!
  "Compile a Wolfram function for fast trit classification.
   Returns a compiled function handle that maps hue -> trit."
  []
  (eval! (w/Compile
          [[hue w/_Real]]
          (w/Which
           (w/Less hue 120.0) 1
           (w/Less hue 240.0) 0
           w/True -1))))

;; ---------------------------------------------------------------------------
;; Lyapunov exponent estimation
;; ---------------------------------------------------------------------------

(defn lyapunov-estimate
  "Estimate largest Lyapunov exponent from trit-tick sequence.
   Uses Wolfram's TimeSeriesModelFit for embedding dimension estimation."
  [ticks]
  (let [trits (s/select [s/ALL :tick/s-new] ticks)
        n (count trits)]
    (when (> n 30)
      (eval! (w/Module ['[lambda]
                        (w/Set 'lambda
                               (w/Mean
                                (w/Table
                                 (w/Log (w/Abs (w/Subtract
                                                (w/Part (w/List trits) (w/Plus 'i 1))
                                                (w/Part (w/List trits) 'i))))
                                 (w/List 'i 1 (dec n)))))
                        'lambda])))))

(comment
  ;; Start kernel
  (start!)

  ;; Generate ticks and verify GF(3) via Wolfram
  (def ticks (gorj/generate-ticks 69))
  (wl-gf3-verify ticks)

  ;; Power spectrum of trit sequence
  (trit-spectrum ticks)

  ;; Simulate WLC in Modelica (matching zig-syrup parameters)
  (simulate-wlc-modelica 2.5 0.005 10.0)

  ;; Simulate a custom Modelica model
  (wsm-simulate "Modelica.Electrical.Analog.Examples.Rectifier" {} 0.1)

  ;; Compile fast trit classifier
  (def fast-trit (compile-trit-classifier!))
  (eval! (fast-trit 137.5)) ;; => 0
  )
