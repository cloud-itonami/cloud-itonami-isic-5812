# cloud-itonami-isic-5812

**Publishing of directories and mailing lists** — ISIC Rev.4 class 5812.

A coordination-only actor for directory and mailing-list publishers, behind an independent Governor that earns advisor trust through structured oversight: proposal → advise → govern → decide → commit|hold|escalate.

## Features

- **Closed proposal-op allowlist**: log-listing-record, schedule-publication-operation, coordinate-distribution, flag-privacy-concern (all `:effect :propose`).
- **Three HARD governor checks** (permanent, un-overridable):
  1. **Listing verified** — target directory-entry/subscriber-list record must exist AND be registered/verified in the store.
  2. **Effect is :propose** — any other `:effect` value is rejected.
  3. **Scope exclusion** — directly finalizing a data-privacy-compliance decision (GDPR/CCPA compliance determination, resolving a deletion/erasure/"right to be forgotten" request, a consent-compliance ruling) is permanently blocked.
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: listing-record logging only (approval-gated)
  - Phase 2: + publication-operation scheduling, distribution coordination (approval-gated)
  - Phase 3: auto-commits clean, high-confidence proposals (privacy concerns always escalate)
- **Append-only audit ledger** — every decision is an immutable log entry.
- **langgraph-clj StateGraph** — one request = one supervised run; human-in-the-loop via `interrupt-before`.

## Scope — operations coordination, NOT data-privacy-compliance authority

This actor never decides whether a listing or subscriber record is
privacy-compliant, never resolves a data-subject deletion/erasure
request, and never grants a "right to be forgotten" claim. Those are
always either a hard permanent block (if an advisor proposal drifts
into that territory) or routed to `:flag-privacy-concern`, which
**always escalates to a human** and is never auto-commit-eligible at
any rollout phase (ADR-2607152500 Wave-4 person-facing-service safety
guardrail).

## Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:dev:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

## Test suite

- `test/dirmailops/governor_test.clj` — unit tests of governor hard checks and scope exclusion
- `test/dirmailops/advisor_test.clj` — advisor proposal shape and consistency
- `test/dirmailops/phase_test.clj` — rollout phase logic
- `test/dirmailops/governor_contract_test.clj` — full graph integration, audit trail
- `test/dirmailops/store_contract_test.clj` — Store protocol and MemStore implementation

## Modules

- `dirmailops.store` — SSoT (MemStore, String-keyed listing directory, append-only ledger)
- `dirmailops.advisor` — contained intelligence node (mock + real-LLM seam)
- `dirmailops.governor` — independent compliance layer
- `dirmailops.phase` — staged rollout (0→3)
- `dirmailops.operation` — langgraph-clj StateGraph
- `dirmailops.sim` — demo driver

## License

AGPL-3.0-or-later. See LICENSE file.

## Governance

This actor is part of the cloud-itonami Wave 4 (human-services) fleet. See ADR-2607121000, ADR-2607152500, and the superproject ADR pair for this class for design decisions.
