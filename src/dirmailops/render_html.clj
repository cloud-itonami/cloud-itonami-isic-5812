(ns dirmailops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: before this namespace
  existed there was NO demo page and no generator at all (`docs/` held
  only `index.html`, `business-model.md` and `operator-quickstart.md`).

  Everything on the generated page is REAL actor output. This namespace
  drives the actual StateGraph — `dirmailops.operation/build` ->
  `:advise` (DirMailAdvisor) -> `:govern` (DirMailGovernor) ->
  `:decide` (`dirmailops.phase/gate`) -> `:commit` | `:hold` |
  `:request-approval` — through `langgraph.graph/run*`, against the real
  seeded store (`dirmailops.store/seed-db`: `listing-1`, `listing-2`,
  `listing-3`). Every id, op, disposition, hold reason, violation detail
  and confidence rendered below is read back out of the resulting store
  ledger / publication log / run state. No hand-typed HTML table of
  make-believe rows, and no prose restatement of the rule set: the rule
  and phase tables are generated from `dirmailops.governor`'s and
  `dirmailops.phase`'s own vars (`allowed-ops`, `always-escalate-ops`,
  `confidence-floor`, `scope-excluded-terms`, `phases`), so they cannot
  drift away from the code.

  Determinism: the scenario is a fixed sequence against a fresh seeded
  store, the advisor is the deterministic mock, and nothing in the page
  carries a timestamp, a random id or an unordered set/map traversal
  (every set is sorted before rendering). Two consecutive runs are
  byte-identical.

  Build-time invariant: `-main` REFUSES to write the file if the
  resulting ledger contains zero `:governor-hold` facts. A console that
  only ever shows the happy path is not evidence that the governor
  works, so the HARD-hold coverage is enforced by the build rather than
  by convention (precedent: cloud-itonami-isic-2513).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [dirmailops.advisor :as advisor]
            [dirmailops.governor :as governor]
            [dirmailops.operation :as op]
            [dirmailops.phase :as phase]
            [dirmailops.store :as store]
            [langgraph.graph :as g]))

;; ----------------------------- scenario driver -----------------------------

(def ^:private approver "editorial-coordinator-1")

(defn- ctx [ph]
  {:actor-id "coord-1" :actor-role :editorial-coordinator :phase ph})

(defn- exec! [actor tid request ph]
  (g/run* actor {:request request :context (ctx ph)} {:thread-id tid}))

(defn- resume! [actor tid status]
  (g/run* actor {:approval {:status status :by approver}}
          {:thread-id tid :resume? true}))

(defn- scenario!
  "Runs one request (and, when `:decision` is given, the human
  operator's resume) through the REAL compiled actor and records what
  actually came back — never what we expected to come back."
  [actor {:keys [id label request phase decision]}]
  (let [r1 (exec! actor id request phase)
        r2 (when decision (resume! actor id decision))
        r  (or r2 r1)]
    {:id id
     :label label
     :phase phase
     :op (:op request)
     :listing-id (:listing-id request)
     :decision decision
     :status (:status r)
     :disposition (get-in r [:state :disposition])
     :verdict (get-in r [:state :verdict])
     :audit (vec (distinct (get-in r [:state :audit])))}))

(defn- compromised-advisor
  "An advisor that has been tampered with / has drifted. `f` rewrites the
  proposal the real `dirmailops.advisor/infer` produced. This is the
  exact threat model the DirMailGovernor exists to catch — the advisor
  is smart-but-untrusted, so the console has to show what happens when
  it misbehaves, not only when it behaves."
  [f]
  (reify advisor/Advisor
    (-advise [_ _store request] (f (advisor/infer nil request)))))

(defn run-demo!
  "Drives one fresh seeded store through a scenario that reaches every
  disposition this actor can produce and every HARD rule this repo's
  governor actually has.

  Clean / human-approved paths:
    s01 `:log-listing-record` listing-1 @ phase 3 -> auto-commit
    s02 `:schedule-publication-operation` listing-1 @ phase 3 -> auto-commit
    s03 `:coordinate-distribution` listing-2 @ phase 3 -> auto-commit
    s04 `:log-listing-record` listing-2 @ phase 1 -> escalate
        (:phase-approval; enabled for writing but not auto-eligible) ->
        human approves -> commit
    s05 `:flag-privacy-concern` listing-1 @ phase 3 -> escalate
        (:always-escalate; never auto at ANY phase) -> approves -> commit
    s06 `:flag-privacy-concern` listing-2 @ phase 3 -> escalate ->
        human REJECTS -> :approval-rejected hold
    s07 `:log-listing-record` listing-1 @ phase 3 with an UNCERTAIN
        advisor (confidence 0.42, below `governor/confidence-floor`) ->
        escalate (:low-confidence), left pending — no human has decided

  HARD holds (never reach a human, un-overridable):
    s08 unregistered listing-99            -> :listing-unverified
    s09 registered-but-unverified listing-3 -> :listing-unverified
    s10 advisor claims a direct actuation (`:effect :commit`)
                                            -> :effect-not-propose
    s11 advisor drifts into the permanently excluded
        privacy-compliance-decision scope   -> :scope-excluded
    s12 advisor proposes an op outside the closed allowlist
                                            -> :op-not-allowed

  Phase-gate hold (also never reaches a human):
    s13 `:coordinate-distribution` @ phase 1, where that op is not yet a
        permitted write -> hold with :phase-disabled

  Returns {:db store :scenarios [..]}."
  []
  (let [db (store/seed-db)
        actor (op/build db)
        ;; separate actors for the tampered/uncertain advisors — the
        ;; store is shared, so one ledger records all of it.
        actor-direct-actuation
        (op/build db {:advisor (compromised-advisor #(assoc % :effect :commit))})
        actor-unauthorized-op
        (op/build db {:advisor (reify advisor/Advisor
                                 (-advise [_ _store request]
                                   ;; a fully-formed, high-confidence, :propose
                                   ;; proposal — but for an op this actor was
                                   ;; never authorized to propose.
                                   (assoc (advisor/infer nil (assoc request :op :log-listing-record))
                                          :op :resolve-erasure-request)))})
        actor-uncertain
        (op/build db {:advisor (compromised-advisor #(assoc % :confidence 0.42))})

        specs
        [[actor {:id "s01-log-auto"
                 :label "listing record logged, governor clean, phase-3 auto-commit"
                 :phase 3
                 :request {:op :log-listing-record :listing-id "listing-1"
                           :patch {:phone "+1-555-0100" :category "plumbing"}}}]
         [actor {:id "s02-publication-auto"
                 :label "quarterly directory compilation scheduled, auto-commit"
                 :phase 3
                 :request {:op :schedule-publication-operation :listing-id "listing-1"
                           :patch {:operation "quarterly-directory-update"
                                   :target-date "2026-08-01"}}}]
         [actor {:id "s03-distribution-auto"
                 :label "newsletter distribution coordinated, auto-commit"
                 :phase 3
                 :request {:op :coordinate-distribution :listing-id "listing-2"
                           :patch {:channel "email" :batch "2026-Q3-newsletter"}}}]
         [actor {:id "s04-log-phase1-approved"
                 :label "same logging op at phase 1: write enabled, auto NOT enabled"
                 :phase 1
                 :request {:op :log-listing-record :listing-id "listing-2"
                           :patch {:frequency "quarterly"}}
                 :decision :approved}]
         [actor {:id "s05-privacy-flag-approved"
                 :label "opt-out concern flagged; always escalates, human approves"
                 :phase 3
                 :request {:op :flag-privacy-concern :listing-id "listing-1"
                           :patch {:concern "unconfirmed opt-out request received via phone"
                                   :confidence 0.9}}
                 :decision :approved}]
         [actor {:id "s06-privacy-flag-rejected"
                 :label "accuracy concern flagged; human REJECTS it"
                 :phase 3
                 :request {:op :flag-privacy-concern :listing-id "listing-2"
                           :patch {:concern "subscriber disputes list accuracy"
                                   :confidence 0.8}}
                 :decision :rejected}]
         [actor-uncertain {:id "s07-low-confidence-pending"
                           :label "advisor below the confidence floor; awaiting a human"
                           :phase 3
                           :request {:op :log-listing-record :listing-id "listing-1"
                                     :patch {:address "12 Riverside Ave"}}}]
         [actor {:id "s08-hold-unregistered"
                 :label "target listing is not in the directory at all"
                 :phase 3
                 :request {:op :log-listing-record :listing-id "listing-99"
                           :patch {:phone "+1-555-0199"}}}]
         [actor {:id "s09-hold-unverified"
                 :label "listing registered but address not yet verified"
                 :phase 3
                 :request {:op :log-listing-record :listing-id "listing-3"
                           :patch {:phone "+1-555-0300"}}}]
         [actor-direct-actuation {:id "s10-hold-direct-actuation"
                                  :label "advisor claims a direct actuation instead of a proposal"
                                  :phase 3
                                  :request {:op :schedule-publication-operation :listing-id "listing-1"
                                            :patch {:operation "reprint"}}}]
         [actor {:id "s11-hold-scope-drift"
                 :label "advisor drifts into finalizing a privacy-compliance decision"
                 :phase 3
                 :request {:op :log-listing-record :listing-id "listing-1"
                           :out-of-scope? true
                           :patch {:note "directory audit"}}}]
         [actor-unauthorized-op {:id "s12-hold-op-not-allowed"
                                 :label "advisor proposes an op outside the closed allowlist"
                                 :phase 3
                                 :request {:op :resolve-erasure-request :listing-id "listing-1"
                                           :patch {:request-id "dsar-77"}}}]
         [actor {:id "s13-hold-phase-disabled"
                 :label "distribution coordination is not a permitted write at phase 1"
                 :phase 1
                 :request {:op :coordinate-distribution :listing-id "listing-1"
                           :patch {:channel "post" :batch "2026-Q3-directory"}}}]]]
    {:db db
     :scenarios (mapv (fn [[a spec]] (scenario! a spec)) specs)}))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw [v] (if (keyword? v) (name v) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- strong [v] (str "<strong>" (esc v) "</strong>"))

(defn- num-cell
  "Numeric cell. A nil is rendered as an em dash rather than an empty
  cell -- commit facts genuinely carry no confidence, and a blank cell
  reads as a rendering bug rather than as absence."
  [v]
  (if (some? v)
    (str "<span class=\"num\">" (esc v) "</span>")
    "<span class=\"muted\">—</span>"))

(defn- ops-str
  "Deterministic rendering of an op SET — sorted, never raw traversal
  order (a set's seq order would make the page non-reproducible)."
  [ops]
  (if (seq ops)
    (str/join " " (map #(code (str %)) (sort-by str ops)))
    "<span class=\"muted\">(none)</span>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n") "")
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       (if lede (str "    <p class=\"muted\">" lede "</p>\n") "")
       body
       "  </section>\n"))

;; ----------------------------- page sections -----------------------------

(defn- hold-basis-cell
  "How a hold is described on the page comes from the fact itself: a
  non-empty `:basis` is a HARD governor rule, an empty one with a
  `:phase-reason` is the rollout-phase gate."
  [{:keys [basis phase-reason phase]}]
  (cond
    (seq basis)
    (str "<span class=\"critical\">HARD &middot; "
         (str/join ", " (map #(code (str %)) basis)) "</span>")

    phase-reason
    (str "<span class=\"warn\">phase gate &middot; " (code (str phase-reason))
         " (phase " (esc phase) ")</span>")

    :else "<span class=\"muted\">—</span>"))

(defn- listing-status-cell [ledger listing-id]
  (let [f (last (filter #(= (:listing-id %) listing-id) ledger))]
    (case (:t f)
      :committed         "<span class=\"ok\">last op committed</span>"
      :governor-hold     (str "<span class=\"critical\">last op held &middot; "
                              (str/join ", " (map #(esc (kw %)) (:basis f)))
                              (when (empty? (:basis f)) (esc (kw (:phase-reason f))))
                              "</span>")
      :approval-rejected "<span class=\"warn\">last op rejected by approver</span>"
      nil                "<span class=\"muted\">no ledger activity</span>"
      (str "<span class=\"muted\">" (esc (kw (:t f))) "</span>"))))

(defn- listings-section [db ledger]
  (section
   "Directory & mailing-list records (SSoT)"
   (str "The seeded listing directory this run operated on, read back from "
        (code "dirmailops.store") ". A record must be independently "
        (code ":registered?") " AND " (code ":verified?") " here before ANY "
        "proposal naming it may commit — or even escalate to a human.")
   (table ["Listing" "Name" "Type" "Registered?" "Verified?" "Ledger status"]
          (for [{:keys [listing-id name listing-type registered? verified?]} (store/all-listings db)]
            (row (code listing-id)
                 (esc name)
                 (code (str listing-type))
                 (if registered? "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
                 (if verified? "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>")
                 (listing-status-cell ledger listing-id))))))

(defn- scenario-outcome-cell [{:keys [disposition status audit decision]}]
  (let [hold (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))
        ask  (last (filter #(= :approval-requested (:t %)) audit))]
    (cond
      (= :approval-rejected (:t hold))
      "<span class=\"warn\">human REJECTED &rarr; hold</span>"

      hold (hold-basis-cell hold)

      (and (= :commit disposition) decision)
      "<span class=\"ok\">human approved &rarr; committed</span>"

      (= :commit disposition)
      "<span class=\"ok\">auto-committed</span>"

      (= :interrupted status)
      (str "<span class=\"warn\">awaiting human &middot; "
           (code (str (:reason ask))) "</span>")

      :else (str "<span class=\"muted\">" (esc (kw disposition)) "</span>"))))

(defn- scenarios-section [scenarios]
  (section
   "This run, scenario by scenario"
   (str "Thirteen requests actually pushed through the compiled StateGraph via "
        (code "langgraph.graph/run*") ". The disposition column is "
        (code ":disposition") " off the returned run state; the detail column is "
        "the audit fact the actor itself emitted.")
   (table ["#" "What was asked" "Op" "Listing" "Phase" "Confidence" "Outcome"]
          (for [{:keys [id label op listing-id phase verdict] :as s} scenarios]
            (row (code id)
                 (esc label)
                 (code (str op))
                 (code listing-id)
                 (esc phase)
                 (num-cell (:confidence verdict))
                 (scenario-outcome-cell s))))))

(defn- gate-section []
  (section
   "Closed op allowlist & auto-commit eligibility"
   (str "Generated from " (code "dirmailops.governor/allowed-ops") ", "
        (code "dirmailops.governor/always-escalate-ops") " and "
        (code "dirmailops.phase/phases") " — not from a description of them. "
        "An op outside this allowlist is a scope violation by construction.")
   (table ["Op" "Writes enabled from phase" "Auto-commit eligible from phase" "Always escalates?"]
          (let [ordered (sort-by str governor/allowed-ops)
                phase-ks (sort (keys phase/phases))
                first-phase (fn [k o] (first (filter #(contains? (get-in phase/phases [% k]) o) phase-ks)))]
            (for [o ordered]
              (row (code (str o))
                   (if-let [p (first-phase :writes o)]
                     (str "<span class=\"ok\">phase " (esc p) "</span>")
                     "<span class=\"critical\">never</span>")
                   (if-let [p (first-phase :auto o)]
                     (str "<span class=\"ok\">phase " (esc p) "</span>")
                     "<span class=\"critical\">never, at any phase</span>")
                   (if (contains? governor/always-escalate-ops o)
                     "<span class=\"warn\">yes — a human always looks</span>"
                     "<span class=\"muted\">no</span>")))))))

(defn- phases-section []
  (section
   "Rollout phase gate"
   (str "Generated from " (code "dirmailops.phase/phases") ". This run's default is phase "
        (code (str phase/default-phase)) ". The phase gate can only ever ADD caution: a "
        "governor hold stays a hold no matter which phase is active.")
   (table ["Phase" "Label" "Writes permitted" "Auto-commit permitted"]
          (for [p (sort (keys phase/phases))
                :let [{:keys [label writes auto]} (get phase/phases p)]]
            (row (esc p) (esc label) (ops-str writes) (ops-str auto))))))

(defn- observed-rules-section [ledger]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)
        by-rule (->> holds
                     (mapcat #(map (fn [v] [(:rule v) (:detail v)]) (:violations %)))
                     (reduce (fn [m [r d]] (update m r (fnil conj []) d)) {}))
        phase-holds (filter #(and (empty? (:basis %)) (:phase-reason %)) holds)
        rejections (filter #(= :approval-rejected (:t %)) ledger)]
    (section
     "Rules this run actually tripped"
     (str "Counted out of the ledger produced below, not asserted. The confidence floor is "
          (code (str governor/confidence-floor)) " and the scope-exclusion scan carries "
          (code (str (count governor/scope-excluded-terms))) " terms. Read the "
          (strong "Kind") " column carefully — the three kinds of &ldquo;no&rdquo; are not "
          "interchangeable.")
     (table ["Rule" "Kind" "Times tripped" "What the actor said"]
            (concat
             (for [r (sort-by str (keys by-rule))
                   :let [details (get by-rule r)]]
               (row (str "<span class=\"critical\">" (code (str r)) "</span>")
                    "<span class=\"critical\">HARD — permanent, no human override path</span>"
                    (str "<span class=\"num\">" (count details) "</span>")
                    (esc (first details))))
             (when (seq phase-holds)
               [(row (str "<span class=\"warn\">" (code ":phase-disabled") "</span>")
                     "<span class=\"warn\">rollout phase gate — lifts at a later phase</span>"
                     (str "<span class=\"num\">" (count phase-holds) "</span>")
                     (str "phase gate: " (code (str (:op (first phase-holds))))
                          " is not a permitted write at phase "
                          (esc (:phase (first phase-holds)))))])
             (when (seq rejections)
               [(row (str "<span class=\"warn\">" (code ":approver-rejected") "</span>")
                     "<span class=\"warn\">human decision — the operator looked and said no</span>"
                     (str "<span class=\"num\">" (count rejections) "</span>")
                     (str "escalated to a human, who declined to sign off on "
                          (code (str (:op (first rejections))))))]))))))

(defn- scope-terms-section []
  (section
   "Permanently out-of-scope territory"
   (str "Every proposal's op, summary, rationale, citations and draft value are flattened and "
        "scanned for these terms, from " (code "dirmailops.governor/scope-excluded-terms")
        ". A hit is a HARD, permanent block: this actor coordinates directory and mailing-list "
        "operations and never finalizes a data-privacy-compliance decision, resolves a "
        "deletion/erasure request, or grants a 'right to be forgotten' claim.")
   (str "    <p>"
        (str/join " " (map #(code %) governor/scope-excluded-terms))
        "</p>\n")))

(defn- ledger-section [ledger]
  (section
   "Append-only audit ledger"
   (str "Every fact " (code "dirmailops.store/ledger") " holds after this run, in order. "
        "Commits and holds are written by the graph's own "
        (code ":commit") " / " (code ":hold") " nodes; nothing else writes here.")
   (table ["#" "Fact" "Op" "Listing" "Actor" "Confidence" "Basis / reason"]
          (map-indexed
           (fn [i {:keys [t op listing-id actor confidence] :as f}]
             (row (num-cell (inc i))
                  (case t
                    :committed "<span class=\"ok\">committed</span>"
                    :governor-hold "<span class=\"critical\">governor-hold</span>"
                    :approval-rejected "<span class=\"warn\">approval-rejected</span>"
                    (esc (kw t)))
                  (code (str op))
                  (code listing-id)
                  (esc actor)
                  (num-cell confidence)
                  (case t
                    :committed (esc (:summary f))
                    ;; NOT a governor rule -- a human looked at an escalated
                    ;; proposal and declined it. Rendering it as HARD would
                    ;; misreport who said no.
                    :approval-rejected "<span class=\"warn\">human decision &middot; <code>:approver-rejected</code></span>"
                    (hold-basis-cell f))))
           ledger))))

(defn- publication-section [db]
  (section
   "Committed publication log (SSoT writes)"
   (str "The only records that reached " (code "dirmailops.store/publication-log")
        ". An " (code ":approved-by") " field means a human operator resumed the interrupted "
        "run and signed it off; its absence means the phase-3 auto-commit path.")
   (table ["#" "Op" "Listing" "Payload" "Approved by"]
          (map-indexed
           (fn [i {:keys [op listing-id payload]}]
             (row (num-cell (inc i))
                  (code (str op))
                  (code listing-id)
                  (code (pr-str (dissoc payload :listing-id :approved-by)))
                  (if-let [by (:approved-by payload)]
                    (str "<span class=\"ok\">" (esc by) "</span>")
                    "<span class=\"muted\">— (auto)</span>")))
           (store/publication-log db)))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator console from a real `run-demo!` result."
  [{:keys [db scenarios]}]
  (let [ledger (vec (store/ledger db))
        holds (count (filter #(= :governor-hold (:t %)) ledger))
        commits (count (filter #(= :committed (:t %)) ledger))]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-5812 &middot; directory &amp; mailing-list publishing — Operator Console</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style>\n"
     "</head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Publishing of directories and mailing lists (ISIC 5812) — Operator Console</h1>\n"
     "</header>\n"
     "<p><span class=\"badge\">read-only sample</span> <span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">privacy-compliance decisions permanently out of scope</span></p>\n"
     "<p class=\"subtitle\">Generated at build time by <code>dirmailops.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) by actually running the actor: "
     (esc (count scenarios)) " requests through <code>dirmailops.operation</code>&rsquo;s StateGraph, producing "
     (esc commits) " commits and <strong>" (esc holds) " governor holds</strong>. "
     "Nothing below is hand-written; the build refuses to emit this page if the run produces zero holds.</p>\n"
     "<main>\n"
     (listings-section db ledger)
     (scenarios-section scenarios)
     (observed-rules-section ledger)
     (gate-section)
     (phases-section)
     (scope-terms-section)
     (ledger-section ledger)
     (publication-section db)
     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-5812 &middot; DirMailAdvisor &#8867; DirMailGovernor. "
     "The advisor is smart-but-untrusted: it only ever emits a proposal, and an independent "
     "governor decides whether that proposal may touch the SSoT. Scenarios s10&ndash;s12 above "
     "run a deliberately tampered advisor to show the governor rejecting it.</p>\n"
     "  <p>Deterministic: no timestamps, no random ids, no unordered traversal — two runs of "
     "<code>clojure -M:dev:render-html</code> against the same seed are byte-identical.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        holds (filterv #(= :governor-hold (:t %)) ledger)]
    ;; Build-time invariant, not a convention: a console that never shows
    ;; the governor saying no is not evidence that the governor works.
    (when (zero? (count holds))
      (throw (ex-info (str "REFUSING to write " out
                           ": the scenario produced ZERO :governor-hold ledger facts. "
                           "The operator console must demonstrate at least one HARD hold "
                           "that never reaches a human, or it is not evidence of anything.")
                      {:out out
                       :ledger-facts (count ledger)
                       :fact-types (frequencies (map :t ledger))})))
    (let [html (render result)]
      (when-let [dir (.getParentFile (java.io.File. ^String out))]
        (.mkdirs dir))
      (spit out html)
      (println "wrote" out
               (str "(" (count ledger) " ledger facts, "
                    (count holds) " governor holds, "
                    (count (filter #(= :committed (:t %)) ledger)) " commits, "
                    (count (store/publication-log db)) " SSoT records, "
                    (count html) " chars)")))))
