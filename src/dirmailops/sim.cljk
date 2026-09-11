(ns dirmailops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean listing-record logging
  request through intake -> advise -> govern -> decide -> approval ->
  commit at phase 1 (assisted-logging, always approval), then re-runs
  the same op at phase 3 (supervised-auto, clean + high confidence ->
  auto-commit), then a publication-operation-scheduling request,
  distribution coordination (both auto-commit clean at phase 3), then a
  privacy-concern flag (ALWAYS escalates, at any phase -- approve, then
  commit), then HARD-hold scenarios: an unregistered listing, a listing
  registered but not yet verified, a proposal whose own `:effect` is not
  `:propose`, and a proposal that has drifted into the
  permanently-excluded data-privacy-compliance-decision scope."
  (:require [langgraph.graph :as g]
            [dirmailops.advisor :as advisor]
            [dirmailops.store :as store]
            [dirmailops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "editorial-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :editorial-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :editorial-coordinator :phase 3}
        actor (op/build db)]

    (println "== log-listing-record listing-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-listing-record :listing-id "listing-1"
                                  :patch {:phone "+1-555-0100" :category "plumbing"}} coordinator-phase-1)]
      (println r)
      (println "-- human editorial coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== log-listing-record listing-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-listing-record :listing-id "listing-1"
                                  :patch {:address "12 Riverside Ave"}} coordinator-phase-3))

    (println "\n== schedule-publication-operation listing-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-publication-operation :listing-id "listing-1"
                                  :patch {:operation "quarterly-directory-update" :target-date "2026-08-01"}} coordinator-phase-3))

    (println "\n== coordinate-distribution listing-2 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-distribution :listing-id "listing-2"
                                  :patch {:channel "email" :batch "2026-Q3-newsletter"}} coordinator-phase-3))

    (println "\n== flag-privacy-concern listing-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-privacy-concern :listing-id "listing-1"
                                 :patch {:concern "unconfirmed opt-out request received via phone" :confidence 0.9}} coordinator-phase-3)]
      (println r)
      (println "-- human editorial coordinator reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== log-listing-record listing-99 (unregistered listing -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-listing-record :listing-id "listing-99"
                                  :patch {:phone "+1-555-0199"}} coordinator-phase-3))

    (println "\n== log-listing-record listing-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-listing-record :listing-id "listing-3"
                                  :patch {:phone "+1-555-0300"}} coordinator-phase-3))

    (println "\n== schedule-publication-operation listing-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :schedule-publication-operation :listing-id "listing-1"
                                           :patch {:operation "reprint"}} coordinator-phase-3)))

    (println "\n== log-listing-record listing-1, advisor drifts into privacy-compliance-decision scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :log-listing-record :listing-id "listing-1"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed publication log ==")
    (doseq [r (store/publication-log db)] (println r))))
