(ns dirmailops.governor-contract-test
  "Integration tests: full OperationActor graph exercising the governor's
  hard checks, escalation logic, and audit trail."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [dirmailops.advisor :as advisor]
            [dirmailops.store :as store]
            [dirmailops.operation :as op]))

(defn exec-request [actor tid request ctx]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn resume-approval [actor tid status]
  (g/run* actor {:approval {:status status :by "coordinator"}} {:thread-id tid :resume? true}))

(deftest listing-log-full-flow
  (testing "clean listing-log proposal -> auto-commit at phase 3"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-1" :phase 3}
          result (exec-request actor "t1"
                               {:op :log-listing-record :listing-id "listing-1" :patch {:phone "+1-555-0100"}}
                               ctx)]
      (is (some? result))
      (is (> (count (store/ledger db)) 0)
          "commit must append audit facts to ledger")
      (is (> (count (store/publication-log db)) 0)
          "commit must append record to publication-log"))))

(deftest privacy-concern-always-escalates
  (testing ":flag-privacy-concern escalates for human approval, regardless of phase/confidence"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-2" :phase 3}
          result (exec-request actor "t2"
                               {:op :flag-privacy-concern :listing-id "listing-1"
                                :patch {:concern "unconfirmed opt-out" :confidence 0.99}}
                               ctx)]
      (is (some? result))
      ;; At this point the actor is paused for approval, not yet committed
      (is (= 0 (count (store/publication-log db)))
          "privacy concern must not auto-commit, must wait for approval")
      ;; Now approve it
      (resume-approval actor "t2" :approved)
      (is (> (count (store/publication-log db)) 0)
          "after approval, record must be committed"))))

(deftest unregistered-listing-hard-hold
  (testing "unregistered listing -> permanent HARD hold, never escalates"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-3" :phase 3}]
      (exec-request actor "t3"
                     {:op :log-listing-record :listing-id "unknown-listing"
                      :patch {:phone "+1-555-0199"}}
                     ctx)
      (is (= 0 (count (store/publication-log db)))
          "HARD hold must never commit"))))

(deftest unverified-listing-hard-hold
  (testing "registered but unverified listing -> permanent HARD hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-4" :phase 3}
          result (exec-request actor "t4"
                               {:op :log-listing-record :listing-id "listing-3"
                                :patch {:phone "+1-555-0300"}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/publication-log db)))
          "unverified listing must HARD hold"))))

(deftest effect-not-propose-hard-hold
  (testing "proposal with :effect :commit (not :propose) -> hard hold"
    (let [db (store/seed-db)
          bad-advisor (reify advisor/Advisor
                        (-advise [_ _ req]
                          (assoc (advisor/infer nil req) :effect :commit)))
          actor (op/build db {:advisor bad-advisor})
          ctx {:actor-id "test-5" :phase 3}
          result (exec-request actor "t5"
                               {:op :log-listing-record :listing-id "listing-1"
                                :patch {:phone "+1-555-0100"}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/publication-log db)))
          "non-:propose effect must HARD hold"))))

(deftest scope-excluded-content-hard-hold
  (testing "proposal drifting into privacy-compliance-decision scope -> permanent hard hold"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-6" :phase 3}
          result (exec-request actor "t6"
                               {:op :log-listing-record :listing-id "listing-1"
                                :out-of-scope? true  ; triggers scope pollution in advisor
                                :patch {}}
                               ctx)]
      (is (some? result))
      (is (= 0 (count (store/publication-log db)))
          "scope-excluded content must HARD hold"))))

(deftest phase-1-approval-gate
  (testing "phase 1 approved request -> commits after human approval"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-7" :phase 1}]
      (exec-request actor "t7"
                     {:op :log-listing-record :listing-id "listing-1"
                      :patch {:phone "+1-555-0100"}}
                     ctx)
      (is (= 0 (count (store/publication-log db)))
          "phase 1 must not auto-commit, requires approval")
      (resume-approval actor "t7" :approved)
      (is (> (count (store/publication-log db)) 0)
          "after approval, must commit")
      (is (some #(= :committed (:t %)) (store/ledger db))
          "committed fact must be logged after approval"))))

(deftest audit-trail-completeness
  (testing "every decision leaves immutable audit facts"
    (let [db (store/seed-db)
          actor (op/build db)
          ctx {:actor-id "test-8" :phase 3}]
      (exec-request actor "t8a"
                     {:op :log-listing-record :listing-id "listing-1" :patch {:phone "+1-555-0100"}}
                     ctx)
      (exec-request actor "t8b"
                     {:op :log-listing-record :listing-id "unknown" :patch {:phone "+1-555-0100"}}
                     ctx)
      (let [ledger (store/ledger db)]
        (is (> (count ledger) 0))
        (is (some #(= :committed (:t %)) ledger)
            "successful commits must be logged")
        (is (some #(= :governor-hold (:t %)) ledger)
            "HARD holds must be logged")))))
