(ns dirmailops.advisor
  "DirMailAdvisor -- the *contained intelligence node* for the
  ISIC-5812 directory/mailing-list publishing operations-coordination
  actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: listing-record logging, publication-operation scheduling,
  distribution coordination, and privacy-concern flagging. CRITICAL: it
  is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER
  a direct actuation -- every proposal's `:effect` is always `:propose`.
  Every output is censored downstream by `dirmailops.governor` before
  anything touches the SSoT.

  This advisor NEVER drafts a proposal that directly finalizes a
  data-privacy-compliance decision (a GDPR/CCPA compliance
  determination, a resolved deletion/erasure/'right to be forgotten'
  request, a consent-compliance ruling) -- that is permanently out of
  scope for this actor, not merely un-implemented.
  `dirmailops.governor`'s `scope-exclusion-violations` independently
  re-scans every proposal for exactly this failure mode (a compromised
  or confused advisor drifting into scope it must never touch) and
  HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :listing-id str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-listing-log
  "Draft a directory-entry/subscriber-list data logging entry. Pure
  logging of a listing's factual fields (name, category, contact
  fields, mailing address) -- never a privacy-compliance determination."
  [_db {:keys [listing-id patch]}]
  {:op         :log-listing-record
   :listing-id listing-id
   :summary    (str listing-id " のリスティング記録を更新: " (pr-str (keys patch)))
   :rationale  "ディレクトリ掲載/購読者リストの事実データ記録のみ。プライバシー・コンプライアンス判断なし。"
   :cites      [listing-id]
   :effect     :propose
   :value      (merge {:listing-id listing-id} patch)
   :confidence 0.93})

(defn- propose-publication-operation
  "Draft a compilation/update/print-run publication-operation scheduling
  proposal (a calendar/production entry, never a direct print/send
  dispatch)."
  [_db {:keys [listing-id patch]}]
  {:op         :schedule-publication-operation
   :listing-id listing-id
   :summary    (str listing-id " の発行作業(編纂/更新/印刷)を提案: " (pr-str (keys patch)))
   :rationale  "ディレクトリ/リストの編纂・更新・印刷スケジュール調整のみ。発行実施の最終決定は人間の編集責任者が行う。"
   :cites      [listing-id]
   :effect     :propose
   :value      (merge {:listing-id listing-id} patch)
   :confidence 0.88})

(defn- propose-distribution-coordination
  "Draft an outbound directory/list distribution coordination proposal
  (shipping/mailing/delivery logistics, never a direct dispatch)."
  [_db {:keys [listing-id patch]}]
  {:op         :coordinate-distribution
   :listing-id listing-id
   :summary    (str listing-id " に関連する配送調整: " (pr-str (keys patch)))
   :rationale  "ディレクトリ/メーリングリストの配送・発送ロジスティクス調整のみ。個人データの開示範囲判断は含まない。"
   :cites      [listing-id]
   :effect     :propose
   :value      (merge {:listing-id listing-id} patch)
   :confidence 0.86})

(defn- propose-privacy-concern
  "Surface a data-consent/opt-out/accuracy concern (an unconfirmed
  opt-out request, a disputed listing accuracy, a possible consent
  lapse) for HUMAN triage. This op ALWAYS escalates in
  `dirmailops.governor` -- never auto-committed at any phase --
  regardless of how confident the advisor is that the concern is real.
  It NEVER resolves the underlying privacy-compliance question itself."
  [_db {:keys [listing-id patch]}]
  {:op         :flag-privacy-concern
   :listing-id listing-id
   :summary    (str listing-id " のプライバシー/同意懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "掲載/購読データの同意・オプトアウト・正確性に関する観察事実の報告のみ。コンプライアンス判断は行わず、常に人間の確認が必要。"
   :cites      [listing-id]
   :effect     :propose
   :value      (merge {:listing-id listing-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-listing-record (propose-listing-log _db request)
                   :schedule-publication-operation (propose-publication-operation _db request)
                   :coordinate-distribution (propose-distribution-coordination _db request)
                   :flag-privacy-concern (propose-privacy-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually determined the listing is GDPR compliant and resolved the deletion request")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :listing-id (:listing-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
