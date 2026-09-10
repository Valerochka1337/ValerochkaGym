# Manual health foundation — execution tracker

Status values: pending | in_progress | done | blocked.

| Task | Status | Owner | AC | Verification / evidence |
|---|---|---|---|---|
| T-001 contract/integration preflight | pending | Android writer | AC-001–012 | Reviewed fixture SHA-256 `33d74a1de76f9e444e02972f463a915b6e29c338c4c76bbac3aea4ce2305f5c0`; record integrated Room predecessor N, accepted guest-sync/AI-01 versions and health partition version before Android implementation. |
| T-002 backend ledger/fixtures | blocked | Backend writer | AC-007,009–011 | Strict version/serverSequence/healthRevision/head-history, combined as-of-H paging, equality-before-reference/head-CAS, health cursor/full-refresh/capability, exact operation and AI-authorization fixtures before Android source. |
| T-003 Room ledger/migration/schema | pending | Android writer | AC-001–004,008,010,011 | Targeted HealthDao/HealthSyncDao and migration tests: immutable head history/revision order, frozen-H pages, preserved pre-partition/outbox bytes, and sync-off local-health owner-switch block. |
| T-004 repository/consent/comparability | pending | Android writer | AC-001–005,007 | Targeted HealthRepository, HealthConsentStore and HealthComparability tests. |
| T-005 backend projection/owner/AI authorization | pending | Android writer | AC-007–011 | Targeted BackendSync/BackendAiRepository: partition, exact/equal retry after report tombstone, combined revision/head-only paging, guest phases, owner-switch preservation fingerprint, head CAS and consent races. |
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
| AC-003 | T-002–005 | Immutable client UUID append, one-time serverSequence/healthRevision assignment, immutable head-history events, separate head CAS and stable combined as-of-H pull including head-only changes. |
| AC-004 | T-002,T-003,T-005 | Tombstone/no-resurrection round-trip; equal stored observation and exact lost response remain stable after report tombstone, while new version/new head selection rejects. |
| AC-005 | T-004, T-006 | Exact compatibility/no-conversion; chart/table. |
| AC-006 | T-005,T-006 | Existing `measurement:<id>` transport only; health capture exclusion; UI reference deduplicated by BodyMeasurementEntity ID; no copy/heuristic merge. |
| AC-007 | T-002,T-004–006 | Notice-version transition; independent consents; frontend/backend InBody denial/revoke/late response; AI-01 logger/direct-provider absence. |
| AC-008 | T-003, T-005 | Accepted GUEST→CLAIMED(B,mergeId) before token→OWNED after all ACK; B recovery; shared A→B preservation footprint blocks sync-off local versions, dirty heads, staging/refresh state or either exact journal before any cleanup/token replacement; clean switch rechecks the fingerprint transactionally. |
| AC-009 | T-002 | Malformed/cross-owner/capability/tombstone/same-versionId mismatch rejection before ledger/cursor mutation. |
| AC-010 | T-002,T-003,T-005 | Separate health baseline/cursor/outbox; distinct change revision and stable as-of-H paging; legacy mixed-operation settlement; off/capability-loss byte retention; capable full refresh while non-health advances. |
| AC-011 | T-002,T-003,T-005 | Exact idempotency/cancel/recreation; two client versions coexist; serverSequence/healthRevision retry and stale head CAS keep one server current; missing-head nonzero base and forward/cyclic parents reject without mutation. |
| AC-012 | T-006 | Semantics, 48dp, fontScale/adaptive. |

## Deviations

None. Any change to immutable version UUIDs/server-assigned ordering, explicit metric identity, head-CAS/server-wins policy, accepted guest-sync phases, AI-disclosure authorization, or isolated hidden-state retention requires a plan amendment.

## Findings

- Gate P repair: replaced conflicting client `(logicalId, sequence)` uniqueness with immutable client `versionId`, idempotent serverSequence assignment and separate head CAS; stale CAS retains every version and the server current pointer.
- Gate P repair: froze health-only baseline/cursor/exact outbox. An already durable pre-partition mixed operation must settle byte-identically before health enablement; it is never filtered or rewritten.
- Gate P repair: health now extends `vibe/guest-sync-plan.md` exactly: Room `GUEST → CLAIMED(B, mergeId)` before token, B-only recovery and `OWNED` after all ACK. A→B includes every retained audit row and both dedicated journals in the shared preservation footprint irrespective of consent/capability; dirty/local-only health blocks without cleanup or token replacement, and only a clear rechecked fingerprint permits local projection cleanup.
- Gate P repair: AI-01 is an integration prerequisite. Its existing InBody frontend and backend action both require current explicit health disclosure authorization, with revocation/late-response denial and no health debug persistence.
- Gate P repair: local acknowledgement is a monotonic notice version required only for new confirmations; a version increase never deletes, hides, tombstones, uploads or changes existing data/other consents.
- T-001 review repair: a distinct owner-scoped healthRevision orders both immutable version-ingestion and head-history events; snapshot/changes freeze H and page a single combined order, so head-only mutations cannot disappear or leak across pages.
- T-001 review repair: exact operation replay and stored-equal version classification precede present-day report validation; historical observations remain auditable after report tombstone, while new versions and matching-CAS selections require the live report head.
- T-001 edge repair: missing-head baseHeadRevision > 0 and forward/cyclic submitted parents reject with health_reference_invalid before any mutation; a parent must be stored or earlier in request order.

## Command results

No backend or Gradle command ran: this is documentation/contract-only. T-001 validation parses JSON with duplicate-key rejection, resolves every `$ref`/route schema, validates Draft 2020-12 schemas and valid fixtures, proves schema invalid fixtures reject, and verifies canonical UTF-8 request reserialization/property order/vector SHA-256 plus the whole fixture SHA.

## Residual risks

T-002 is release-blocking. The Android writer must freeze integrated Room predecessor after profile/notes/CAL/guest-sync and record the accepted AI-01 Android version before implementation. Health capability remains disabled until any pre-partition exact mixed operation settles. Attachments/OCR/new AI functionality/trainer sharing remain stage 18 or later; only the existing AI-01 InBody consent path is integrated here.
