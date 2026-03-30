(ns gatomic.core
  "Gay-Datomic: deterministic color generation with datom-backed
   reafference, GF(3) trit algebra, and time-travel queries.

   ~315 lines of Clojure replaces Gay.jl's functional core."
  (:require [gatomic.color :as color]
            [gatomic.schema :as schema]
            [gatomic.db :as db])
  (:gen-class))

(defn -main
  "Initialize Gay-Datomic: create DB, transact genesis, verify, demo queries."
  [& _args]
  (println "Gay-Datomic v0.1.0")
  (println "==================")
  (println)

  ;; 1. Pure color generation (no DB)
  (println "1. Pure color generation")
  (println "   splitmix64(1069) =" (color/splitmix64 1069))
  (println "   color-at(1069, 1) =" (color/color-at 1069 1))
  (println "   Genesis verify (pure):" (color/verify-genesis))
  (println)

  ;; 2. Create Datomic database
  (println "2. Creating Datomic database...")
  (let [conn (db/create-db)]
    (println "   Schema transacted (" (count schema/color-schema) "attributes)")

    ;; 3. Transact genesis chain
    (println)
    (println "3. Transacting genesis chain...")
    (db/transact-genesis! conn)
    (let [ddb (datomic.api/db conn)]
      (println "   Genesis datoms verified:" (db/verify-genesis-datoms ddb))
      (println "   Trit balance:" (db/trit-balance ddb))

      ;; 4. Generate more colors
      (println)
      (println "4. Generating colors 13-24...")
      (db/transact-colors! conn 1069 13 12)
      (let [ddb2 (datomic.api/db conn)]
        (println "   Trit balance after 24 colors:" (db/trit-balance ddb2))

        ;; 5. GF(3) queries
        (println)
        (println "5. GF(3) trit queries")
        (println "   Trit -1 colors:" (count (db/colors-by-trit ddb2 -1)))
        (println "   Trit  0 colors:" (count (db/colors-by-trit ddb2 0)))
        (println "   Trit +1 colors:" (count (db/colors-by-trit ddb2 1)))

        ;; 6. Reafference
        (println)
        (println "6. Reafference checks")
        (println "   Check seed=1069 index=1:" (db/reafference-check! conn 1069 1))
        (println "   Check seed=1069 index=25:" (db/reafference-check! conn 1069 25))

        ;; 7. Stream splitting
        (println)
        (println "7. Stream splitting (SPI)")
        (let [children (db/split-seed! conn 1069 3)]
          (println "   Split 1069 into 3 children:" children)
          (println "   Child 0 color:" (color/color-at (first children) 1)))

        ;; 8. Fault injection (speculative)
        (println)
        (println "8. Fault injection (d/with, no write)")
        (let [ddb3 (datomic.api/db conn)
              result (db/inject-fault ddb3 1069 1 "#FFFFFF")]
          (println "   Injected #FFFFFF at index 1")
          (println "   Genesis still OK?" (:genesis-ok? result)))

        ;; 9. Time travel
        (println)
        (println "9. Color history for seed=1069 index=1")
        (let [hist (db/color-history (datomic.api/db conn) 1069 1)]
          (doseq [[attr val tx added] (sort-by (fn [[_ _ tx _]] tx) hist)]
            (println (format "   tx=%d %s %s = %s"
                             tx (if added "+" "-") attr val))))

        (println)
        (println "Done. All systems nominal.")))))
