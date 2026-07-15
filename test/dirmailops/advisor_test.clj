(ns dirmailops.advisor-test
  "Unit tests of `dirmailops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [dirmailops.advisor :as adv]
            [dirmailops.store :as store]))

(def db (store/seed-db))

(deftest propose-listing-log-shape
  (testing "listing-log proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-listing-record
                           :listing-id "listing-1"
                           :patch {:phone "+1-555-0100" :category "plumbing"}})]
      (is (= :log-listing-record (:op p)))
      (is (= "listing-1" (:listing-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :listing-id)))))

(deftest propose-publication-operation-shape
  (testing "publication-operation proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-publication-operation
                           :listing-id "listing-2"
                           :patch {:operation "quarterly-update" :target-date "2026-08-01"}})]
      (is (= :schedule-publication-operation (:op p)))
      (is (= "listing-2" (:listing-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-distribution-coordination-shape
  (testing "distribution-coordination proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-distribution
                           :listing-id "listing-1"
                           :patch {:channel "email" :batch "2026-Q3"}})]
      (is (= :coordinate-distribution (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest propose-privacy-concern-shape
  (testing "privacy-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-privacy-concern
                           :listing-id "listing-1"
                           :patch {:concern "unconfirmed opt-out request"}})]
      (is (= :flag-privacy-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-listing-record :schedule-publication-operation
                :coordinate-distribution :flag-privacy-concern]]
      (let [p (adv/infer db {:op op :listing-id "listing-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-listing-record :schedule-publication-operation
                :coordinate-distribution :flag-privacy-concern]]
      (let [p (adv/infer db {:op op :listing-id "listing-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))

(deftest no-proposal-ever-drafts-a-privacy-compliance-decision
  (testing "no default (non-out-of-scope) proposal text ever claims to finalize a privacy-compliance decision"
    (doseq [op [:log-listing-record :schedule-publication-operation
                :coordinate-distribution :flag-privacy-concern]]
      (let [p (adv/infer db {:op op :listing-id "listing-1" :patch {:concern "opt-out request"}})
            blob (clojure.string/lower-case (pr-str (select-keys p [:summary :rationale :value])))]
        (is (not (clojure.string/includes? blob "gdpr")))
        (is (not (clojure.string/includes? blob "compliance decision")))
        (is (not (clojure.string/includes? blob "deletion request resolved")))))))
