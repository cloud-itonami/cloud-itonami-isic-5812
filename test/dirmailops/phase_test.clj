(ns dirmailops.phase-test
  "Unit tests of `dirmailops.phase` rollout logic."
  (:require [clojure.test :refer [deftest is testing]]
            [dirmailops.phase :as phase]))

(def clean-verdict {:hard? false :escalate? false})
(def low-conf-verdict {:hard? false :escalate? true})
(def hard-verdict {:hard? true :escalate? false})

(deftest phase-0-read-only
  (testing "phase 0 allows no writes"
    (doseq [op [:log-listing-record :schedule-publication-operation
                :coordinate-distribution :flag-privacy-concern]]
      (let [{:keys [disposition]} (phase/gate 0 {:op op} :commit)]
        (is (= :hold disposition)
            (str "phase 0 must hold all ops including " op))))))

(deftest phase-1-listing-log-only
  (testing "phase 1 allows only listing-record logging, requires approval"
    (let [{:keys [disposition reason]} (phase/gate 1 {:op :log-listing-record} :commit)]
      (is (= :escalate disposition))
      (is (= :phase-approval reason)))
    (let [{:keys [disposition]} (phase/gate 1 {:op :schedule-publication-operation} :commit)]
      (is (= :hold disposition)))))

(deftest phase-2-adds-coordination-ops
  (testing "phase 2 allows coordination ops, still requires approval"
    (doseq [op [:log-listing-record :schedule-publication-operation :coordinate-distribution]]
      (let [{:keys [disposition]} (phase/gate 2 {:op op} :commit)]
        (is (= :escalate disposition)
            (str "phase 2 op " op " requires approval"))))))

(deftest phase-3-auto-commits-clean-ops
  (testing "phase 3 auto-commits clean, high-conf non-privacy-concern ops"
    (let [{:keys [disposition]} (phase/gate 3 {:op :log-listing-record} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :schedule-publication-operation} :commit)]
      (is (= :commit disposition)))
    (let [{:keys [disposition]} (phase/gate 3 {:op :coordinate-distribution} :commit)]
      (is (= :commit disposition)))))

(deftest privacy-concern-holds-when-not-enabled
  (testing ":flag-privacy-concern holds in phases 0-2 (not yet enabled)"
    (doseq [ph [0 1 2]]
      (let [{:keys [disposition]} (phase/gate ph {:op :flag-privacy-concern} :escalate)]
        (is (= :hold disposition)
            (str "phase " ph " has not enabled flag-privacy-concern yet"))))))

(deftest privacy-concern-escalates-when-enabled
  (testing ":flag-privacy-concern ALWAYS escalates when enabled, even if governor says commit"
    (let [{:keys [disposition]} (phase/gate 3 {:op :flag-privacy-concern} :commit)]
      (is (= :escalate disposition)
          "phase 3 must escalate privacy concerns regardless of governor disposition"))))

(deftest flag-privacy-concern-never-in-any-auto-set
  (testing "ADR-2607152500 Wave-4 guardrail: :flag-privacy-concern is never a member of any phase's :auto set"
    (doseq [[ph {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-privacy-concern))
          (str "phase " ph " must never auto-commit :flag-privacy-concern")))))

(deftest hard-hold-always-wins
  (testing "a governor HARD hold stays HOLD regardless of phase"
    (doseq [ph [0 1 2 3]]
      (let [{:keys [disposition]} (phase/gate ph {:op :log-listing-record} :hold)]
        (is (= :hold disposition)
            (str "phase " ph " must respect governor HARD hold"))))))

(deftest verdict->disposition-maps-correctly
  (testing "verdict->disposition correctly translates governor verdict to base disposition"
    (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false})))
    (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
    (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false})))))
