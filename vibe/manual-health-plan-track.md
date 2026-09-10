# Manual health foundation — execution tracker

Status values: pending | in_progress | done | blocked.

| Task | Status | Owner | AC | Verification / evidence |
|---|---|---|---|---|
| T-001 contract/integration preflight | done | root | AC-001–012 | Fixture copies match SHA-256 `33d74a1de76f9e444e02972f463a915b6e29c338c4c76bbac3aea4ce2305f5c0`; accepted AI-01 Room21/version39 and Profile Room23/version41 establish predecessor23. Health targets Room24/version42/1.3.34. Agreed parallel seam: manual-health-android-api.md. |
| T-002 backend ledger/fixtures | done_local | Backend writer | AC-007,009–011 | Strict version/serverSequence/healthRevision/head-history, combined as-of-H paging, equality-before-reference/head-CAS, health cursor/full-refresh/capability, exact operation and AI-authorization fixtures before Android source. |
| T-003 Room ledger/migration/schema | in_progress | shared_owner | AC-001–004,008,010,011 | Targeted HealthDao/HealthSyncDao and migration tests: immutable head history/revision order, frozen-H pages, preserved pre-partition/outbox bytes, and sync-off local-health owner-switch block. |
| T-004 repository/consent/comparability | in_progress | shared_owner | AC-001–005,007 | Targeted HealthRepository, HealthConsentStore and HealthComparability tests. |
| T-005 backend projection/owner/AI authorization | in_progress | shared_owner | AC-007–011 | Targeted BackendSync/BackendAiRepository: partition, exact/equal retry after report tombstone, combined revision/head-only paging, guest phases, owner-switch preservation fingerprint, head CAS and consent races. |
| T-006 Analysis UI/navigation/InBody privacy | in_progress | root | AC-001–007,012 | Targeted HealthViewModel, HealthAnalysisCompose and MeasurementEditor tests; no-copy/no-health-debug absence check. |
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

## Shared disclosure prerequisite — 2026-09-10

AI-01 owns the initial consent entities/DAO/schema and exact journal. Health T-003 creates only
remaining ledger tables and reuses the accepted AI-01 aggregate; T-004/T-005 reuse its repository.
Backend T-002 is accepted locally at8bc1fae: independentT/V PASS and root120tests/check/bootJar PASS;
older blocked/fix-checkpoint paragraphs below are historical. Android tasks remain pending.

## Read-only integration packet — 2026-09-10

The existing HealthAiConsentState/Outbox/Intent entities, HealthAiConsentDao and
HealthAiDisclosureRepository remain the only AI-disclosure aggregate. Health adds local
notice acknowledgement and backend-health-sync choice separately. Owner transitions
stay in BackendSync.claim/signIn and its preservation preflight/final transaction;
health ledger assignments, dirty heads, refresh staging and both retained journals must
join that fingerprint before any token replacement. Existing owner-keyed AI receipts
must never be rebound to another account. Health baseline/cursor/data queue remain
separate from PortableData and the generic outbox.

UI extends AnalysisSection immediately after Progress and hosts a separate HealthViewModel
and screen. Shared owner integrates pushed detail/editor routes. Existing BodyMeasurement
rows are referenced by ID without copying or metric-name matching. The existing InBody
picker/disclosure path remains the sole AI entry. Compatible trends always include a
textual representation; incompatible values use a reason/table. No screenshot review
is authorized. This packet introduces no implementation before Profile acceptance.

## Android implementation start — 2026-09-10

Profile final gates passed (1160 tests, no failures/errors, one skip; debug assembly).
The two disjoint writers now implement Health against the frozen API appendix.
shared_owner alone owns Room, domain/data, shared sync, DI/navigation and version;
ai_fix owns screens/ViewModels and their tests. Root alone runs serialized Gradle
after both writers freeze; independent tester/reviewer remain read-only.
Existing AI-disclosure entities and journal are reused. No publication is authorized.

## First Android compilation and independent review

Ownership now: shared_owner retains all shared data/navigation files; root owns the
Health screens/ViewModels/tests and Analysis integration after taking over the initial
UI scaffold. There are still only two writers. No publication or unrelated feature work.
Root added real forms/operator switching, reference rows, compatible chart/table,
separate disclosure controls, pushed back/history, target invalidation and restriction
restoration. Twenty UI/VM tests are prepared, including actual Analysis selector access
without workout history and representative 48dp bounds. They have not run yet.

Initial compile attempts stopped on two syntax errors, then one missing import, then
HealthConsentStore Unit return types and a missing HealthOperationResult declaration.
Current log: `/private/tmp/yarumo-partial-health-compile.log`. No successful compile or
final schema acceptance is claimed. All Gradle is stopped while the shared owner fixes.

Independent data review requires the following consolidated repairs before acceptance:
interleaved version/head paging; ordered operation receipt/head binding; server-wins
STALE projection; subsequent dirty capture after retained outbox ACK; explicit successful
final-stage apply before POST; canonical IDs and bounded strict raw input; incremental
baseline union; complete staged graph validation; negotiated capability before the first
health request and at final ACK. Byte-sensitive owner fingerprint and owner/race coverage
remain required. Existing immutable-history and navigation-modal defects were corrected.

## Current targeted acceptance (updated workflow)

Health implementation is integrated at Room24/version42/1.3.34. First assembly succeeds.
Initial Health + migration run: 50 tests / 1 UI failure, no errors/skips. The real
Analysis selector now uses adaptive 48dp chips, and Health actions enforce both dimensions.
All six HealthAnalysisComposeTest cases and all six proposal wire/API cases pass in
`/private/tmp/yarumo-health-deadlock-recheck.log`.

Health's new active-workout check exposed a cross-dispatcher raw-SQL deadlock inside Room;
confirmed by `/private/tmp/yarumo-health-hang-threads.txt` and replaced with a suspend DAO
query that inherits the transaction context. No test was disabled.
Final narrow HealthLedgerSyncTest: 9 tests / 0 failures/errors/skips, including actual late
head-history collision rollback, active-during-GET, exact request retention across disabled
consent/capability and mandatory full refresh, stale CAS/later edit preservation, and
same-timestamp reverse-UUID parent-first capture. Log:
`/private/tmp/yarumo-health-final-sync-targeted.log`.
Remaining broad verification is the final stable integration full unit/debug gate, not a
new full run after every internal stage. Health capability negotiation now also advertises
health-ledger-v1 in the normal transport (no payload or consent changes).
