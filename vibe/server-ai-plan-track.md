# AI-01 tracker

| Task | Status | Owner | Dependencies | AC | Automated check |
|---|---|---|---|---|---|
| T-001 | pending | root contract freeze | — | AC-001–AC-003 | fixture parity — not run |
| T-002 | pending | backend writer | T-001 | AC-001–AC-005 | backend AI unit/integration targets — not run |
| T-003 | pending | backend writer | T-002 | AC-005, AC-007 | secure-delivery/config targets — not run |
| T-004 | pending | Android writer | T-001, Stage 12 ACK receipt, accepted backend fixture | AC-001–AC-004 | syncReady/BackendAi/library/measurement targets — not run |
| T-005 | pending | Android writer | T-004 | AC-003, AC-006, AC-007 | absence check + compile target — not run |
| T-006 | pending | backend tester + Sol/high reviewer | T-003 | AC-001–AC-005, AC-007 | backend audit/review/final suite — not run |
| T-006F | pending | backend writer fixes; reviewer recheck; root final | T-006 | AC-001–AC-005, AC-007 | affected targets then full check/bootJar before fixture acceptance |
| T-007 | pending | Android tester + Sol/high reviewer, root final gates | T-005, T-006F | AC-001–AC-007 | Android targets → full unit → debug → unsigned release — not run |

## AC traceability

| AC | Tasks | Evidence |
|---|---|---|
| AC-001 | T-001, T-002, T-004 | fixture parity; authenticated routes; Android request contains only action DTO |
| AC-002 | T-002, T-004 | Stage-12 ACK receipt Ready-only launch; guest/unowned zero-API, A/B/revision/revoke and late response tests |
| AC-003 | T-001, T-002, T-004, T-005 | exact nullable draft fixture; validator/no-write/explicit-save tests |
| AC-004 | T-002, T-004 | auth/consent/error/cancel/manual-fallback and PII-safe error tests |
| AC-005 | T-002, T-003 | fake-provider bounds, timeout/cancel, default-off and delivery tests |
| AC-006 | T-005 | retained domain bindings, direct-provider/models/BYOK/logger absence, encrypted-key deletion and DI/UI tests |
| AC-007 | T-003, T-005, T-007 | secret-safe env test, compatibility test, single bump and final gates |

## Findings and deviations

- Current Android has direct Retrofit chat/models, stored BYOK base/key/model and a debug response
  logger. All are in scope for removal, not merely bypass.
- `BackendSync.run()` currently returns `Unit`; AI T-004 owns Ready after Stage-12 sync, acknowledged personal revision, verified catalog revision and owner/outbox/conflict checks.
- Existing GitHub environment/secret inspection and production deploy access returned HTTP 403.
  No values were requested/read. Deployment remains blocked and no production-AI claim is permitted.
- Provider protocol is frozen to OpenAI Chat Completions at an operator-set HTTPS URL; model IDs are
  required env only, with no guessed default.
- Strict correction: the official Create Chat Completion reference freezes the POST wire fixture,
  including model env selection, `store:false`, non-streaming single choice, schema and JPEG data URI.

## Command results

No build, backend, Gradle, Git, `.env`, or deployment command ran: planning files only.

## Residual risks

Stage 12 and credential delivery are external dependencies. Provider cancellation is best effort;
the bounded deadline limits lifetime but cannot promise no provider charge after a sent request.

Gate P strict recheck: no remaining P0/P1. Root corrected final P2 prose to distinguish personal ACK from separately verified catalog revision; all findings closed. Backend slice may start after root fixture freeze, with independent T-006/T-006F.

## Root fixture freeze / backend start

T-001 core contract frozen at `vibe/server-ai-contract-v1.json`, identical backend test fixture.
SHA-256 `f76033bf776748a37567c0a26a9c74e8cf13d215c47e2077068c0b679ae6599b`.
It includes exact action schemas, nullable InBody fields, provider result wrapper/schema, status,
error map and numeric limits. Android test-resource copy is deferred to its own AI feature branch
to avoid mixing unrelated application files into #47. Backend T-002/T-003 starts locally on
`feat/server-ai-drafts` from c73c411. No upstream publication or live provider configuration exists.
