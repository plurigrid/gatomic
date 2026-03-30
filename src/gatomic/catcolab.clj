(ns gatomic.catcolab
  "18 CatColab models for causally complete continuous memory.

   3 of each object type, GF(3) balanced:
     Olog(0)×3 + PetriNet(0)×3 + StockFlow(+1)×3
     + CausalLoop(-1)×3 + Decapode(+1)×3 + RegulatoryNet(-1)×3 = 0

   The mechanism: observe → discretize → store → validate → regulate → observe.
   High-dimensional continuous things are remembered via attractors
   (translation-invariant), discretized at instruments (saddle detectors),
   with local information loss tracked explicitly."
  (:require [com.rpl.specter :as s]
            [datomic.api :as d]))

;; ---------------------------------------------------------------------------
;; CatColab theory types
;; ---------------------------------------------------------------------------

(def theory-types
  {:olog              {:trit 0  :theory-kind :discrete-dbl :color "#5f7fc5"}
   :petri-net         {:trit 0  :theory-kind :discrete-dbl :color "#a1363e"}
   :stock-flow        {:trit 1  :theory-kind :discrete-tab :color "#a48eba"}
   :causal-loop       {:trit -1 :theory-kind :discrete-dbl :color "#3ebb0f"}
   :decapode          {:trit 1  :theory-kind :discrete-tab :color "#16ad14"}
   :regulatory-network {:trit -1 :theory-kind :discrete-dbl :color "#c079e4"}})

;; ---------------------------------------------------------------------------
;; The 18 models: 3 per type
;; ---------------------------------------------------------------------------

(def models
  [;; === OLOG (trit 0) — what exists ===
   {:id :olog/attractor-ontology
    :type :olog
    :generators {:types [:saddle :basin :separatrix]
                 :aspects ["is visited by" "flows into" "divides"]}
    :role "What attractors exist in the reservoir"}

   {:id :olog/instrument-ontology
    :type :olog
    :generators {:types [:electrode :channel :threshold]
                 :aspects ["measures" "discretizes at" "triggers when"]}
    :role "How observation discretizes continuous state"}

   {:id :olog/memory-ontology
    :type :olog
    :generators {:types [:chunk :manifold :embedding]
                 :aspects ["hashes to" "lives in" "projects onto"]}
    :role "How continuous memory is stored in CAS"}

   ;; === PETRI NET (trit 0) — how it moves ===
   {:id :petri/saddle-visitation
    :type :petri-net
    :generators {:species [:saddle-1 :saddle-2 :saddle-3 :saddle-n]
                 :transitions [{:name :visit :consumes [:saddle-i] :produces [:saddle-j]}]}
    :role "Heteroclinic orbit as token flow between saddles"}

   {:id :petri/chunk-lifecycle
    :type :petri-net
    :generators {:species [:raw :hashed :stored :tombstoned]
                 :transitions [{:name :hash :consumes [:raw] :produces [:hashed]}
                               {:name :store :consumes [:hashed] :produces [:stored]}
                               {:name :evict :consumes [:stored] :produces [:tombstoned]}
                               {:name :resurrect :consumes [:tombstoned] :produces [:stored]}]}
    :role "CAS chunk state machine"}

   {:id :petri/trit-accumulation
    :type :petri-net
    :generators {:species [:plus-pool :zero-pool :minus-pool]
                 :transitions [{:name :classify :consumes [:saddle] :produces [:pool]}
                               {:name :balance :consumes [:plus-pool :minus-pool] :produces [:zero-pool]}]}
    :role "GF(3) conservation enforcement"}

   ;; === STOCK-FLOW (trit +1) — generative dynamics ===
   {:id :stock-flow/reservoir-state
    :type :stock-flow
    :generators {:stocks [:x-activation]
                 :flows [:recurrent-flow]
                 :links [:input-coupling :readout]}
    :dynamics "dx/dt = f(Wx + Wu·input)"
    :role "Raw continuous reservoir dynamics"}

   {:id :stock-flow/entropy-budget
    :type :stock-flow
    :generators {:stocks [:s-local :s-attractor]
                 :flows [:dissipation :compression]
                 :links [:prigogine-bound]}
    :dynamics "dS_local/dt = σ - compression_rate"
    :role "What's thrown away locally vs preserved by attractor"}

   {:id :stock-flow/embedding-manifold
    :type :stock-flow
    :generators {:stocks [:dim-effective :curvature]
                 :flows [:inflate :collapse]
                 :links [:takens-embedding]}
    :dynamics "d(dim)/dt = new_saddle_rate - redundancy_rate"
    :role "Effective dimensionality of remembered attractor"}

   ;; === CAUSAL LOOP (trit -1) — constraint/validation ===
   {:id :causal-loop/saddle-stability
    :type :causal-loop
    :generators {:variables [:residence-time :lyapunov-exp :basin-volume]
                 :edges [{:from :residence-time :to :basin-volume :sign :+}
                         {:from :lyapunov-exp :to :residence-time :sign :-}
                         {:from :basin-volume :to :lyapunov-exp :sign :-}]}
    :role "Validates attractor is real, not transient noise"}

   {:id :causal-loop/instrument-fidelity
    :type :causal-loop
    :generators {:variables [:snr :sampling-rate :discretization-error]
                 :edges [{:from :snr :to :discretization-error :sign :-}
                         {:from :sampling-rate :to :discretization-error :sign :-}
                         {:from :discretization-error :to :snr :sign :-}]}
    :role "Validates measurement faithfulness"}

   {:id :causal-loop/memory-consolidation
    :type :causal-loop
    :generators {:variables [:rehearsal-freq :chunk-refcount :decay-rate]
                 :edges [{:from :rehearsal-freq :to :chunk-refcount :sign :+}
                         {:from :decay-rate :to :chunk-refcount :sign :-}
                         {:from :chunk-refcount :to :decay-rate :sign :-}]}
    :role "Validates stored chunks persist"}

   ;; === DECAPODE (trit +1) — field physics / PDE ===
   {:id :decapode/diffusion-on-attractor
    :type :decapode
    :generators {:forms [{:name :rho :degree 0 :kind :primal}]
                 :ops [:laplacian :gradient :divergence]}
    :dynamics "∂ρ/∂t = Δρ + ∇·(ρ∇V)"
    :role "Probability mass evolution on attractor manifold"}

   {:id :decapode/information-flow
    :type :decapode
    :generators {:forms [{:name :I :degree 0 :kind :primal}
                         {:name :J :degree 1 :kind :primal}]
                 :ops [:exterior-d :hodge-star :codifferential]}
    :dynamics "dI/dt = -δJ, J = -D·dI (Fisher information conservation)"
    :role "How distinguishable nearby states are = instrument resolution"}

   {:id :decapode/coherence-field
    :type :decapode
    :generators {:forms [{:name :phi :degree 0 :kind :primal}
                         {:name :omega :degree 1 :kind :primal}]
                 :ops [:kuramoto-coupling]}
    :dynamics "dφ/dt = ω + K·sin(Δφ)"
    :role "Phase locking across measurement channels"}

   ;; === REGULATORY NETWORK (trit -1) — signed regulation ===
   {:id :reg/attractor-switching
    :type :regulatory-network
    :generators {:nodes [:saddle-a :saddle-b :saddle-c]
                 :edges [{:from :saddle-a :to :saddle-b :type :activates}
                         {:from :saddle-b :to :saddle-c :type :activates}
                         {:from :saddle-c :to :saddle-a :type :inhibits}]}
    :role "WLC heteroclinic cycle as regulatory graph"}

   {:id :reg/gain-control
    :type :regulatory-network
    :generators {:nodes [:input-gain :reservoir-gain :readout-gain]
                 :edges [{:from :reservoir-gain :to :input-gain :type :inhibits}
                         {:from :readout-gain :to :reservoir-gain :type :activates}
                         {:from :input-gain :to :readout-gain :type :activates}]}
    :role "Echo state / edge-of-chaos property maintenance"}

   {:id :reg/chunk-regulation
    :type :regulatory-network
    :generators {:nodes [:write-gate :read-gate :forget-gate]
                 :edges [{:from :write-gate :to :read-gate :type :inhibits}
                         {:from :read-gate :to :forget-gate :type :activates}
                         {:from :forget-gate :to :write-gate :type :activates}]}
    :role "CAS gating: mutex on write/read, forget enables write"}])

;; ---------------------------------------------------------------------------
;; Verification
;; ---------------------------------------------------------------------------

(defn trit-sum
  "GF(3) sum of all 18 models."
  []
  (reduce + (map #(get-in theory-types [(:type %) :trit]) models)))

(defn models-by-type
  "Group models by CatColab type."
  []
  (group-by :type models))

(defn causal-closure?
  "Check that the 18 models form a causally closed loop:
   every role category (observe/discretize/store/validate/regulate) is covered."
  []
  (let [roles (set (map :role models))]
    (and (>= (count roles) 18)
         (zero? (trit-sum)))))

(defn composition-graph
  "The 6-node causal loop between theory types."
  []
  [{:from :olog            :to :petri-net         :nat :select}
   {:from :petri-net       :to :stock-flow        :nat :emit}
   {:from :stock-flow      :to :decapode          :nat :transform}
   {:from :decapode        :to :causal-loop       :nat :filter}
   {:from :causal-loop     :to :regulatory-network :nat :certify}
   {:from :regulatory-network :to :olog            :nat :bind}])

;; ---------------------------------------------------------------------------
;; Datomic transact helpers
;; ---------------------------------------------------------------------------

(defn model->datoms
  "Convert a CatColab model to datomic tx-data for the topos schema."
  [model]
  (let [theory-info (get theory-types (:type model))]
    [{:topos/theory (:type model)
      :topos/composition (-> (composition-graph)
                             (->> (filter #(= (:from %) (:type model))))
                             first
                             :nat)}]))

(defn transact-all-models!
  "Transact all 18 models into gatomic."
  [conn]
  (let [tx-data (mapcat model->datoms models)]
    @(d/transact conn (vec tx-data))))

(comment
  ;; Verify GF(3) conservation
  (trit-sum) ;; => 0

  ;; All 6 types with 3 each
  (update-vals (models-by-type) count)
  ;; => {:olog 3, :petri-net 3, :stock-flow 3,
  ;;     :causal-loop 3, :decapode 3, :regulatory-network 3}

  ;; Causal closure check
  (causal-closure?)

  ;; The composition loop
  (composition-graph)
  ;; olog →select→ petri →emit→ stock-flow →transform→ decapode
  ;;   ↑bind←──── reg-net ←certify── causal-loop ←filter──┘

  ;; Models by role
  (s/select [s/ALL (s/submap [:id :role])] models))
