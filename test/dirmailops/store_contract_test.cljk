(ns dirmailops.store-contract-test
  "Contract tests for `dirmailops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [dirmailops.store :as store]))

(deftest mem-store-listing-lookup
  (testing "MemStore can store and retrieve listings by ID (string keys)"
    (let [listings {"l1" {:listing-id "l1" :name "Alice's Bakery" :registered? true :verified? true}}
          s (store/mem-store listings)]
      (is (some? (store/listing s "l1")))
      (is (nil? (store/listing s "l99"))))))

(deftest mem-store-all-listings
  (testing "MemStore returns all listings in sorted order"
    (let [listings {"l2" {:listing-id "l2" :name "Bob's Garage"}
                     "l1" {:listing-id "l1" :name "Alice's Bakery"}
                     "l3" {:listing-id "l3" :name "Carol's Clinic"}}
          s (store/mem-store listings)
          all-l (store/all-listings s)]
      (is (= 3 (count all-l)))
      (is (= "l1" (:listing-id (first all-l))))
      (is (= "l3" (:listing-id (last all-l)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-publication-log
  (testing "MemStore commit-record! appends to publication-log"
    (let [s (store/mem-store {})
          record {:op :log-listing-record :listing-id "l1" :value {:phone "+1-555-0100"}}]
      (is (= 0 (count (store/publication-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/publication-log s))))
      (is (= record (first (store/publication-log s)))))))

(deftest mem-store-with-listings
  (testing "MemStore with-listings replaces the listing directory"
    (let [s (store/mem-store {})
          new-listings {"l1" {:listing-id "l1" :name "Alice's Bakery"}}]
      (is (= 0 (count (store/all-listings s))))
      (store/with-listings s new-listings)
      (is (= 1 (count (store/all-listings s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo listings"
    (let [s (store/seed-db)]
      (is (> (count (store/all-listings s)) 0))
      (is (some? (store/listing s "listing-1")))
      (is (some? (store/listing s "listing-2")))
      (is (some? (store/listing s "listing-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for listing-id"
    (let [demo (store/demo-data)
          listings (:listings demo)]
      (doseq [[k v] listings]
        (is (string? k) "keys must be strings")
        (is (string? (:listing-id v)) "listing-id must be string")
        (is (= k (:listing-id v)) "key must match listing-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
