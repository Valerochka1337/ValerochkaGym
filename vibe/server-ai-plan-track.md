# AI-01 tracker

Preflight 10.09.2026: backend implementation is already accepted locally; the rows below
now reflect `/Users/raul/ItmoProjects/ValerochkaGymBackend/vibe/server-ai-drafts-plan-track.md`.
Android T-004/T-005 remain unimplemented. Stage12 is accepted (`52bfd43`); current CAL-01
must finish before this Android writer starts. Fixture SHA parity was rechecked read-only:
`f76033bf776748a37567c0a26a9c74e8cf13d215c47e2077068c0b679ae6599b`.
Prior planning-only command notes below are historical, not current backend status.

| Task | Status | Owner | Dependencies | AC | Automated check |
|---|---|---|---|---|---|
| T-001 | partial | root contract freeze | — | AC-001–AC-003 | Canonical/backend fixture parity PASS; Android test-resource copy remains T-004 |
| T-002 | done_local | backend writer | T-001 | AC-001–AC-005 | a5b567a; backend T/V PASS, final check/bootJar64tests0failures |
| T-003 | done_local | backend writer | T-002 | AC-005, AC-007 | a5b567a; delivery discovery15tests PASS; production delivery remains unverified |
| T-004 | done_local | Android writer, sole early disclosure Room owner | T-001, Stage 12 ACK receipt, accepted backend fixture and health disclosure backend | AC-001–AC-004 | Room 19→20/1→20 plus 9/10/11 historical migration fixtures, final-ACK SyncReady, authenticated draft DTO, disclosure capability/privacy and library/measurement targets PASS |
| T-005 | done_local | Android writer | T-004 | AC-003, AC-006, AC-007 | Direct provider/model/BYOK/logger/settings/DI absence check; legacy encrypted key cleanup; compile/Spotless PASS; final gates remain T-007 |
| T-006 | done_local | backend tester + Sol/high reviewer | T-003 | AC-001–AC-005, AC-007 | Independent T/V and narrow recheck PASS, backend tracker |
| T-006F | done_local | backend writer fixes; reviewer recheck; root final | T-006 | AC-001–AC-005, AC-007 | final64tests0failures/check bootJar PASS; fixture accepted |
| T-007 | done_local | Android tester + Sol/high reviewer, root final gates | T-005, T-006F | AC-001–AC-007 | Android targets → full unit → debug → unsigned release — not run |

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

Android T-004/T-005 focused gate (JDK 21) PASS: `:app:testDebugUnitTest` for
`*BackendAiRepositoryTest`, `*HealthAiDisclosureRepositoryTest`, `*BackendSyncTest`,
`*Migration9To12Test`, `*Migration10To12Test`, `*Migration11To12Test`,
`*Migration19To20Test`, `*Migration1To20Test`, `*ExerciseLibraryViewModelTest` and
`*MeasurementEditorViewModelTest`, with `:app:compileDebugKotlin spotlessCheck` — 95 tests,
BUILD SUCCESSFUL (41 tasks). The copied Android contract fixture matches
`vibe/server-ai-contract-v1.json` SHA-256 `f76033bf776748a37567c0a26a9c74e8cf13d215c47e2077068c0b679ae6599b`.
No full unit suite, debug/release build, backend deployment, Git publication or secret inspection ran.

Repair pass: review exposed a durable latest-choice requirement after the original 19→20
aggregate. The app therefore advances 20→21 without another application-version bump, adding the
owner intent journal and its strict migration/full-path checks. Targeted JDK 21 recovery gate for
`*HealthAiDisclosureRepositoryTest`, `*Migration20To21Test`, `*Migration1To21Test`,
`*BackendSyncTest` and `*MeasurementEditorViewModelTest`, with compile/Spotless, PASS (41 tasks).

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

## Authoritative disclosure delta (2026-09-10)

Gate P narrow review PASS after two P1 and one P2 corrections. AI-01 T-004, not Health, owns the first actual Room
`HealthAiConsentStateEntity` + `HealthAiConsentOutboxEntity` aggregate/DAO, its handwritten next
migration/schema and full-path tests (19→20 unless integrated predecessor changes). It implements
the exact health fixture consent request/receipt and revision header, immediate DataStore block,
explicit grant/revoke and no image encoding/upload without a current enabled same-owner receipt.
Health later reuses those tables/DAO without a duplicate migration/store. Required strict evidence:
exact replay/conflict, stale CAS, receipt monotonicity, guest/claim/A→B isolation, recreation,
header equality and late revoke discard. One AI-01 version increment and unsigned release attempt
remain. No command ran for this delta.

Narrow review corrections: T-004 explicitly tests health-ledger-v1 union on disclosure GET/POST,
accepted/missing/downgraded/426 responses and no-encode/no-upload failure behavior; InBody revision
header equality remains required. Request bytes/hash precede POST; raw receipt persists afterward
before/with monotonic application, never changing the immediate DataStore flag. Governing
manual-health T-003 and tracker now explicitly reuse the AI-01-owned aggregate. Narrow independent Sol/high recheck PASS; no remaining P1/P2 in the delta.

## Superseding Android acceptance — handoff continuation 2026-09-10

AI01 production and regression work is complete locally at version39/1.3.31, Room21;
no extra version increment was added for review fixes. Independent narrow T/V PASS.
The previously remaining privacy/intent/raw-replay/ABA/DTO/editor-boundary findings are
closed, including a deferred DAO read test and consent revoke during in-flight requests.
Real BackendApi interceptor tests prove one AI POST/no refresh on401 and literal raw bytes.

Targeted compilation/Spotless pass; 113 targeted cases pass across combined and final focused
consent recheck. Full `testDebugUnitTest` with JDK21 and the existing isolation init script
PASS6m56s:1091tests/0failures/0errors/1skip. `assembleDebug` PASS.
Control APK: `/private/tmp/yarumo-ai01-v39-debug.apk`.
Logs: `/private/tmp/yarumo-partial-ai-full.log`, `/private/tmp/yarumo-partial-ai-debug.log`.

`assembleRelease` attempted and blocked by missing release signing inputs; no credentials
were read or requested. Independent `minifyReleaseWithR8` verification is in progress.
Release attempt log: `/private/tmp/yarumo-partial-ai-release.log`.
No commit, push, merge, deployment, release publication or live AI call occurred.

Final R8 result: `:app:minifyReleaseWithR8` PASS; `/private/tmp/yarumo-partial-ai-r8.log`. AI01 accepted locally; signed release remains unavailable due missing signing configuration.
