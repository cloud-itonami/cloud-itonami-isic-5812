(ns dirmailops.governor
  "DirMailGovernor -- the independent compliance layer that earns the
  DirMailAdvisor the right to commit. The advisor has no notion of
  whether a listing/subscriber record is actually registered and
  verified, whether its own proposed `:effect` secretly claims a direct
  actuation instead of a mere proposal, or whether it has silently
  drifted into a permanently out-of-scope decision area, so this MUST be
  a separate system able to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  ONLY (directory-entry/subscriber-list data logging, publication
  scheduling, distribution coordination, privacy/consent-concern
  flagging). It NEVER performs or authorizes:
    - directly finalizing a data-privacy-compliance decision (deciding
      a listing/subscriber record is GDPR/CCPA/opt-out compliant)
    - resolving or granting a data-subject deletion/erasure/'right to
      be forgotten' request
    - any other privacy-authority determination (consent adjudication,
      regulatory compliance ruling)

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Listing unverified        -- the target listing/subscriber record
                                     must exist AND be independently
                                     confirmed `:registered?`/
                                     `:verified?` in the store before
                                     ANY proposal for it may commit or
                                     even escalate. Never trusts a
                                     proposal's own claim about the
                                     listing -- re-derived from the
                                     listing's own store record, the same
                                     'ground truth, not self-report'
                                     discipline every sibling actor's
                                     governor uses.
    2. Effect not :propose       -- every proposal's `:effect` MUST
                                     be `:propose`. Any other effect
                                     value is, by construction, a
                                     claim to directly actuate/commit
                                     outside governance -- HARD block,
                                     not merely low-confidence.
    3. Scope exclusion           -- ANY proposal (regardless of op)
                                     whose op, rationale, summary,
                                     citations or draft value touches
                                     privacy-compliance-decision
                                     territory (finalizing a GDPR/CCPA
                                     compliance determination, resolving
                                     a deletion/erasure/'right to be
                                     forgotten' request, a consent-
                                     compliance ruling) is a HARD,
                                     PERMANENT block -- this actor's
                                     charter excludes that territory
                                     structurally, not as a rollout
                                     milestone. Evaluated
                                     UNCONDITIONALLY on every proposal.
                                     An op outside the closed four-op
                                     allowlist is the SAME failure mode
                                     (an advisor proposing something it
                                     was never authorized to propose)
                                     and is folded into this same
                                     check.

  One ESCALATE (SOFT) gate: LLM confidence below the floor, OR the op
  is `:flag-privacy-concern` -- ALWAYS escalates to a human, regardless
  of confidence, regardless of how clean the proposal otherwise is.
  `dirmailops.phase` independently agrees: `:flag-privacy-concern` is
  never a member of any phase's `:auto` set either -- two layers, not
  one. Per this fleet's Wave-4 person-facing-service safety guardrail
  (ADR-2607152500): the closed op allowlist never includes an op that
  directly finalizes a data-privacy-compliance decision, and any
  'flag a concern' op always escalates and is never auto-commit-eligible."
  (:require [clojure.string :as str]
            [dirmailops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`).
  None of these ops directly finalizes a data-privacy-compliance
  decision (ADR-2607152500 Wave-4 guardrail)."
  #{:log-listing-record :schedule-publication-operation
    :coordinate-distribution :flag-privacy-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-privacy-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing a
  data-privacy-compliance decision. Scanned across the proposal's op/
  summary/rationale/cites/value, never trusting the advisor's own
  framing of its intent."
  ["gdpr" "ccpa" "個人情報保護法準拠"
   "opt-out complete" "opt-out-complete" "opt out complete" "オプトアウト完了"
   "deletion request resolved" "deletion-request resolved" "deletion request-resolved"
   "erasure request granted" "erasure complete" "erasure-complete" "削除請求解決"
   "right to be forgotten" "right-to-be-forgotten"
   "data subject request finalized" "data-subject request finalized" "dsar resolved" "dsar finalized"
   "compliance decision" "compliance-decision" "compliance determination" "compliance-determination"
   "compliance ruling" "compliance-ruling"
   "consent verified compliant" "consent-verified-compliant" "同意確認済み"])

;; ----------------------------- checks -----------------------------

(defn- listing-unverified-violations
  "The target listing/subscriber record must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the proposal's
  own `:listing-id` claim without a store lookup."
  [{:keys [listing-id]} st]
  (let [l (store/listing st listing-id)]
    (when-not (and l (:registered? l) (:verified? l))
      [{:rule :listing-unverified
        :detail (str listing-id " は未登録または未検証のリスティング -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content directly finalizes a data-privacy-compliance
  decision, regardless of confidence or how clean every other check is.
  Evaluated UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "データプライバシー・コンプライアンス判断(GDPR/CCPA準拠判定、削除・消去請求の解決、忘れられる権利の付与)を直接確定する提案は永久に禁止"}])))

(defn check
  "Censors a DirMailAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [listing-id (or (:listing-id proposal) (:listing-id request))
        hard (into []
                   (concat (listing-unverified-violations {:listing-id listing-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :listing-id (:listing-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
