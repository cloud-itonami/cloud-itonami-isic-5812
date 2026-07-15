# Contributing to cloud-itonami-isic-5812

Contributions should preserve the actor's scope: back-office publishing
operations coordination only, with CRITICAL exclusions of directly
finalizing a data-privacy-compliance decision (see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: clojure -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Directly finalize a data-privacy-compliance decision (deciding a listing/subscriber record is GDPR/CCPA compliant).
- Resolve or grant a data-subject deletion/erasure/"right to be forgotten" request.
- Make any other privacy-authority determination (consent adjudication, regulatory compliance ruling).

Contributions that cross these boundaries will be rejected.
