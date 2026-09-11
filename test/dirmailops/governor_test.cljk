(ns dirmailops.governor-test
  "Pure unit tests of `dirmailops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [dirmailops.governor :as gov]
            [dirmailops.store :as store]))

(def listing-1 {:listing-id "listing-1" :name "Acme Plumbing Co." :registered? true :verified? true})
(def listing-3 {:listing-id "listing-3" :name "Riverside Dental Clinic" :registered? true :verified? false})

(defn- clean-proposal [op listing-id]
  {:op op :listing-id listing-id :summary "s" :rationale "routine publication coordination"
   :cites [listing-id] :effect :propose :value {} :confidence 0.85})

(deftest listing-unregistered-is-hard
  (testing "no listing record at all -> HARD hold"
    (let [s (store/mem-store {"listing-1" listing-1})
          verdict (gov/check {} nil (clean-proposal :log-listing-record "unknown-listing") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:listing-unverified} (map :rule (:violations verdict)))))))

(deftest listing-unverified-is-hard
  (testing "listing registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"listing-3" listing-3})
          verdict (gov/check {} nil (clean-proposal :log-listing-record "listing-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:listing-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"listing-1" listing-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-publication-operation "listing-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"listing-1" listing-1})
          verdict (gov/check {} nil (clean-proposal :finalize-privacy-compliance "listing-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest gdpr-compliance-decision-content-is-hard-and-permanent
  (testing "a proposal whose rationale claims to finalize GDPR compliance is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"listing-1" listing-1})
          poisoned (assoc (clean-proposal :log-listing-record "listing-1")
                          :rationale "determined the listing is GDPR compliant"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest ccpa-compliance-decision-content-is-hard
  (testing "a proposal claiming CCPA compliance is HARD-blocked, same as GDPR"
    (let [s (store/mem-store {"listing-1" listing-1})
          poisoned (assoc (clean-proposal :log-listing-record "listing-1")
                          :rationale "confirmed CCPA compliance for this subscriber record"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest deletion-request-resolution-content-is-hard
  (testing "a proposal claiming a deletion request was resolved is HARD-blocked"
    (let [s (store/mem-store {"listing-1" listing-1})
          poisoned (assoc (clean-proposal :coordinate-distribution "listing-1")
                          :summary "deletion request resolved for this subscriber")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest right-to-be-forgotten-content-is-hard
  (testing "a proposal touching a 'right to be forgotten' grant is HARD-blocked"
    (let [s (store/mem-store {"listing-1" listing-1})
          poisoned (assoc (clean-proposal :schedule-publication-operation "listing-1")
                          :value {:decision "granted the right to be forgotten request"})
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest compliance-determination-content-is-hard
  (testing "a proposal touching a general privacy compliance determination is HARD-blocked"
    (let [s (store/mem-store {"listing-1" listing-1})
          poisoned (assoc (clean-proposal :log-listing-record "listing-1")
                          :summary "issued a compliance determination for this listing's consent status")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-privacy-concern-is-not-scope-excluded
  (testing "flagging observed opt-out/accuracy/consent concerns as a PRIVACY CONCERN (not a compliance decision) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"listing-1" listing-1})
          concern (assoc (clean-proposal :flag-privacy-concern "listing-1")
                         :value {:concern "subscriber reported an unconfirmed opt-out request and disputed listing accuracy"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (opt-out/accuracy disputes) is exactly what this op exists to surface"))))

(deftest flag-privacy-concern-always-escalates-even-when-clean
  (testing ":flag-privacy-concern is always high-stakes, regardless of confidence"
    (let [s (store/mem-store {"listing-1" listing-1})
          concern (assoc (clean-proposal :flag-privacy-concern "listing-1") :confidence 0.99)
          verdict (gov/check {} nil concern s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))
