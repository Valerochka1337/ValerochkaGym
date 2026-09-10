# Manual health foundation — execution tracker

Status values: pending | in_progress | done | blocked.

| Task | Status | Owner | AC | Verification / evidence |
|---|---|---|---|---|
| T-001 contract/integration preflight | pending | Android writer | AC-001–012 | Record integrated Room predecessor N, accepted guest-sync/AI-01 versions, backend fixture hash and health partition version. |
| T-002 backend ledger/fixtures | blocked | Backend writer | AC-007,009–011 | Strict version/serverSequence/head-CAS, health cursor/full-refresh/capability, exact operation and AI-authorization fixtures before Android source. |
| T-003 Room ledger/migration/schema | pending | Android writer | AC-001–004,008,010,011 | Targeted HealthDao/HealthSyncDao and migration tests, including preserved pre-partition outbox bytes. |
| T-004 repository/consent/comparability | pending | Android writer | AC-001–005,007 | Targeted HealthRepository, HealthConsentStore and HealthComparability tests. |
| T-005 backend projection/owner/AI authorization | pending | Android writer | AC-007–011 | Targeted BackendSync/BackendAiRepository: partition, exact retry, guest phases, head CAS and consent races. |
| T-006 Analysis UI/navigation/InBody privacy | pending | Android writer | AC-001–007,012 | Targeted HealthViewModel, HealthAnalysisCompose and MeasurementEditor tests; no-copy/no-health-debug absence check. |
| T-007 version/evidence | pending | Android writer | AC-001–012 | app compileDebugKotlin. |
| T-008 independent test/review | pending | tester + reviewer | AC-001–012 | Missing targeted gate only; reviewer read-only. |
| T-009 consolidated fixes | pending | Android writer | affected | Smallest invalidated command/re-review. |
| T-010 final gates | pending | root | AC-001–012 | app testDebugUnitTest, then app assembleDebug. |

## AC → task → test traceability

| AC | Tasks | Evidence |
|---|---|---|
| AC-001 | T-003, T-004, T-006 | DAO/repository offline/recreation; ViewModel state. |
| AC-002 | T-003, T-004 | Typed payload/parser boundaries. |
| AC-003 | T-003–005 | Immutable client UUID append, serverSequence assignment, separate head CAS and concurrent pull. |
| AC-004 | T-003, T-005 | Tombstone/no-resurrection round-trip. |
| AC-005 | T-004, T-006 | Exact compatibility/no-conversion; chart/table. |
| AC-006 | T-005,T-006 | Existing `measurement:<id>` transport only; health capture exclusion; UI reference deduplicated by BodyMeasurementEntity ID; no copy/heuristic merge. |
| AC-007 | T-002,T-004–006 | Notice-version transition; independent consents; frontend/backend InBody denial/revoke/late response; AI-01 logger/direct-provider absence. |
| AC-008 | T-003, T-005 | Accepted GUEST→CLAIMED(B,mergeId) before token→OWNED after all ACK; B recovery; A→B→A purge transaction. |
| AC-009 | T-002 | Malformed/cross-owner/capability/tombstone/same-versionId mismatch rejection before ledger/cursor mutation. |
| AC-010 | T-002,T-003,T-005 | Separate health baseline/cursor/outbox; legacy mixed-operation settlement; off/capability-loss byte retention; capable full refresh while non-health advances. |
| AC-011 | T-002,T-003,T-005 | Exact idempotency/cancel/recreation; two client versions coexist; serverSequence retry and stale head CAS keep one server current. |
| AC-012 | T-006 | Semantics, 48dp, fontScale/adaptive. |

## Deviations

None. Any change to immutable version UUIDs/server-assigned ordering, explicit metric identity, head-CAS/server-wins policy, accepted guest-sync phases, AI-disclosure authorization, or isolated hidden-state retention requires a plan amendment.

## Findings

- Gate P repair: replaced conflicting client `(logicalId, sequence)` uniqueness with immutable client `versionId`, idempotent serverSequence assignment and separate head CAS; stale CAS retains every version and the server current pointer.
- Gate P repair: froze health-only baseline/cursor/exact outbox. An already durable pre-partition mixed operation must settle byte-identically before health enablement; it is never filtered or rewritten.
- Gate P repair: health now extends `vibe/guest-sync-plan.md` exactly: Room `GUEST → CLAIMED(B, mergeId)` before token, B-only recovery, `OWNED` after all ACK, and A→B purge; the old destructive null-owner claim is not the contract.
- Gate P repair: AI-01 is an integration prerequisite. Its existing InBody frontend and backend action both require current explicit health disclosure authorization, with revocation/late-response denial and no health debug persistence.
- Gate P repair: local acknowledgement is a monotonic notice version required only for new confirmations; a version increase never deletes, hides, tombstones, uploads or changes existing data/other consents.

## Command results

No backend or Gradle command ran: this is planning-only.

## Residual risks

T-002 is release-blocking. T-001 must freeze integrated Room predecessor after profile/notes/CAL/guest-sync and record the accepted AI-01 Android version. Health capability remains disabled until any pre-partition exact mixed operation settles. Attachments/OCR/new AI functionality/trainer sharing remain stage 18 or later; only the existing AI-01 InBody consent path is integrated here.
