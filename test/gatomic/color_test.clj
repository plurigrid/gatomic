(ns gatomic.color-test
  (:require [clojure.test :refer [deftest is testing]]
            [gatomic.color :as color]
            [gatomic.db :as db]))

(deftest splitmix64-test
  (testing "SplitMix64 is deterministic"
    (is (= (color/splitmix64 0) (color/splitmix64 0)))
    (is (= (color/splitmix64 1069) (color/splitmix64 1069))))
  (testing "SplitMix64 is a bijection (different inputs -> different outputs)"
    (is (not= (color/splitmix64 0) (color/splitmix64 1)))
    (is (not= (color/splitmix64 1069) (color/splitmix64 1070)))))

(deftest color-at-test
  (testing "color-at is deterministic"
    (is (= (color/color-at 1069 1) (color/color-at 1069 1)))
    (is (= (color/color-at 1069 12) (color/color-at 1069 12))))
  (testing "color-at returns valid RGB"
    (let [{:keys [r g b]} (color/color-at 1069 1)]
      (is (<= 0 r 255))
      (is (<= 0 g 255))
      (is (<= 0 b 255))))
  (testing "color-at returns valid trit"
    (let [{:keys [trit]} (color/color-at 1069 1)]
      (is (contains? #{-1 0 1} trit)))))

(deftest genesis-test
  (testing "Genesis chain matches Gay.jl canonical colors"
    (is (color/verify-genesis))))

(deftest trit-test
  (testing "GF(3) trit values are correct for genesis"
    (doseq [{:keys [index trit]} color/genesis-colors]
      (is (= trit (:trit (color/color-at 1069 index)))
          (str "Trit mismatch at index " index)))))

(deftest db-genesis-test
  (testing "Genesis datoms round-trip through Datomic"
    (let [conn (db/create-db)]
      (db/transact-genesis! conn)
      (is (db/verify-genesis-datoms (datomic.api/db conn))))))

(deftest reafference-test
  (testing "Reafference: new color returns :new"
    (let [conn (db/create-db)]
      (is (= :new (db/reafference-check! conn 1069 1)))))
  (testing "Reafference: existing color returns :match"
    (let [conn (db/create-db)]
      (db/transact-color! conn 1069 1)
      (is (= :match (db/reafference-check! conn 1069 1))))))

(deftest fault-injection-test
  (testing "Fault injection detects corrupted genesis"
    (let [conn (db/create-db)]
      (db/transact-genesis! conn)
      (let [result (db/inject-fault (datomic.api/db conn) 1069 1 "#FFFFFF")]
        (is (false? (:genesis-ok? result)))))))

(deftest stream-split-test
  (testing "Stream split produces deterministic children"
    (let [conn (db/create-db)
          children (db/split-seed! conn 1069 3)]
      (is (= 3 (count children)))
      (is (= children (mapv #(color/splitmix64 (unchecked-add 1069 (long %)))
                            (range 3)))))))

(deftest trit-balance-test
  (testing "Trit balance queryable"
    (let [conn (db/create-db)]
      (db/transact-genesis! conn)
      (is (number? (db/trit-balance (datomic.api/db conn)))))))
