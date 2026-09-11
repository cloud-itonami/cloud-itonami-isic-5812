(ns dirmailops.store
  "SSoT for the ISIC-5812 directory/mailing-list publishing COORDINATION
  actor, behind a `Store` protocol so the backend is a swap, not a
  rewrite -- the same seam every `cloud-itonami-isic-*` actor in this
  fleet uses.

  This actor coordinates the back-office operations of a directory and
  mailing-list publisher: directory-entry/subscriber-list data logging,
  compilation/update/print-run publication scheduling, outbound
  distribution coordination, and data-consent/opt-out/accuracy concern
  flagging. It NEVER directly finalizes a data-privacy-compliance
  decision (deciding a listing is GDPR/CCPA/opt-out compliant, resolving
  a data-subject deletion/erasure request, granting a 'right to be
  forgotten' claim) -- see `dirmailops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `listings` directory keyed by `:listing-id` STRING (never a
  keyword -- consistent keying from the start, avoiding the silent-miss
  bug that plagued an earlier shepherd attempt).

  A registered/verified listing record must exist before ANY proposal
  for that listing may ever commit or escalate -- `dirmailops.governor`'s
  `listing-unverified-violations` re-derives this from the listing's own
  `:registered?`/`:verified?` fields, never from proposal self-report,
  the SAME 'ground truth, not self-report' discipline every sibling
  actor's own governor uses.

  The ledger stays append-only: which listing a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by
  whom is always a query over an immutable log.")

(defprotocol Store
  (listing [s listing-id] "Registered listing record, or nil.
    Listing map: {:listing-id .. :name .. :listing-type .. :registered? bool :verified? bool}.
    `:listing-type` is one of :directory-entry (a business/individual
    entry in a published directory) or :mailing-list-subscriber (a
    subscriber on a published mailing list) -- purely descriptive, the
    governor treats both uniformly.")
  (all-listings [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (publication-log [s] "the append-only committed publication-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-listings [s listings] "replace/seed the listing directory (map listing-id->listing)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained listing directory covering both the happy path
  and the governor's own hard checks, so the actor + tests run offline."
  []
  {:listings
   {"listing-1" {:listing-id "listing-1" :name "Acme Plumbing Co. (business directory entry)"
                  :listing-type :directory-entry :registered? true :verified? true}
    "listing-2" {:listing-id "listing-2" :name "quarterly-newsletter subscriber jdoe@example.invalid"
                  :listing-type :mailing-list-subscriber :registered? true :verified? true}
    "listing-3" {:listing-id "listing-3" :name "Riverside Dental Clinic (in intake, address unconfirmed)"
                  :listing-type :directory-entry :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (listing [_ listing-id] (get-in @a [:listings listing-id]))
  (all-listings [_] (sort-by :listing-id (vals (:listings @a))))
  (ledger [_] (:ledger @a))
  (publication-log [_] (:publication-log @a))
  (commit-record! [_ record]
    (swap! a update :publication-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-listings [s listings] (when (seq listings) (swap! a assoc :listings listings)) s))

(defn seed-db
  "A MemStore seeded with the demo listing directory. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :publication-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `listings` map (listing-id string ->
  listing map) -- the primary test/dev entry point. `listings` may be empty
  (an unregistered-everywhere store)."
  [listings]
  (->MemStore (atom {:listings (or listings {}) :ledger [] :publication-log []})))
