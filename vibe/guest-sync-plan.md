# Guest sync — transfer and conflicts (#51, stages 11–12)

## Goal and scope

Move the current guest-owned local dataset into the first authenticated account without loss or cross-account reuse, then keep that account in the existing bidirectional backend sync.  This plan covers only stages 11–12: durable ownership transfer, existing portable-category union, routine/workout conflict policy, recovery, and account UX.  Slug: `guest-sync`.

**In scope:** `exercise`, `gym`, `routine`, `workout`, `measurement`, and the already-shipped `schedule` portable record, including their references and existing baseline/outbox behavior.  A new future calendar-plan aggregate or a DataStore weekly schedule is CAL-01 work; it must be added to `SyncSchema.trackedTables` and `PortableData` in that later feature, not inferred here.

**Non-goals:** guest-mode navigation/AI gating (stage 13/AI-01); Calendar local-plan model, Google grants, or event reconciliation (CAL-01/CAL-02); a product policy for delete-vs-edit and non-routine/non-workout conflicts; backend deployment; destructive reset; changing historical snapshots when a routine is copied.

**Assumptions:** the backend preserves `CloudPush.operationId` idempotence and can round-trip the six current record kinds.  Routine deletion-vs-edit remains an existing explicit conflict because the approved two-routine rule applies to two non-deleted routine versions.  The implementation takes the actual newest Room version immediately before editing (current planning baseline: 16) and calls the migration `N→N+1`.

## Acceptance criteria

| ID | Criterion |
|---|---|
| AC-001 | First login retains the complete guest union of all current portable categories and references until the final initial-merge ACK; retry/lost response adds no duplicate records, and a new local routine copy is not treated as acknowledged until its own ACK. |
| AC-002 | A cache owned by A is never treated as guest data for B; A→B is blocked before token replacement when any unacknowledged or locally retained data/journal exists; only a clear preflight permits cache replacement, retaining the standard catalog. |
| AC-003 | `GUEST → CLAIMED(targetUser, mergeId) → OWNED` and conflict-copy mappings survive recreation, sign-out and lost responses. A claim for B rejects C without deletion or upload to C, and Account UI explains how to resume B. |
| AC-004 | Every ordinary sync conflict on a completed workout UUID automatically applies the server snapshot, creates no local workout copy, and therefore leaves statistics/AI with one workout. |
| AC-005 | Every ordinary sync conflict between non-deleted routine versions keeps the server routine at its original sync ID plus exactly one full personal local copy with a stable new sync ID. Exercises, planned sets, rest and gyms survive; replay of the same conflict never creates a third. |
| AC-006 | Active work defers catalog/remote apply and repeats the guard inside the Room apply transaction. A stale worker, HTTP response, or token refresh cannot mutate another owner. |
| AC-007 | The handwritten migration maps legacy `owner != null` to `OWNED(owner)` and `owner == null` to `GUEST`, preserving pre-existing data, baseline and exact outbox bytes. The retry operation remains exact across process death; current portable categories are explicitly enumerated, and CAL-01 is the dependency for any future calendar aggregate. |

## Current and target flow

Current: Account accepts tokens → `BackendSync.signIn` → `claim`; `claim(null→user)` calls `clearAccountData`; `run` uses generic three-way conflict UI, applies remote records in a Room transaction, and stores a durable `CloudPush` before HTTP. Tokens live separately in encrypted `AtomicFile`.

Target:

```text
Room guest data + state GUEST
  → Room transaction: CLAIMED(B, mergeId) (data, baseline and outbox retained)
  → AtomicFile/Keystore: install B tokens
  → BackendSync singleton/WorkManager: retry ambiguous exact outbox operation; GET snapshot
  → Room transaction: assert CLAIMED/OWNED(B), active=false; policy apply + server baseline/map updates
  → durable batch capture → ACK each batch → final no-outbox + fresh snapshot equals ACK baseline
  → initial merge ACK: OWNED(B); normal bidirectional sync
```

Room remains the SSOT for data, owner claim, initial-merge phase, and conflict-copy mapping. `BackendTokenStore` remains the SSOT for credentials. They cannot share a transaction: after a durable claim, a crash before token installation leaves `CLAIMED(B)` with no request possible; resuming authentication only as B installs tokens and resumes the same `mergeId`. A crash after token installation resumes the same merge. A C session while claimed by B is rejected before any dataset mutation, push, or token replacement. A pre-existing `OWNED(A)` cache followed by B first performs the read-only preservation preflight below. A blocked result leaves A ownership, data, journals and tokens untouched. Only a clear result permits atomic personal-cache replacement and A sync metadata cleanup; standard catalog rows remain.

The singleton `BackendSync.mutex` serializes UI and worker runs. `assertOwner` is expanded to validate Room phase/target and the current token user before every network result, outbox write/ack, and remote apply. Worker cancellation remains cooperative; `CancellationException` is rethrown. Existing network backoff and unique `backend_sync` work retain ownership; no foreground service, permission, or new dependency is needed.

## Frozen decisions and contracts

1. **Persistence.** Add a Room-owned state representation (phase, `owner`/target, `mergeId`, initial-merge acknowledgement) and a Room-owned `backend_conflict_copies` mapping: `mergeId`, `kind`, `originalSyncId`, `remoteRevision`, canonical `localPayloadFingerprint`, `localCopySyncId`. Its unique key covers the same conflict identity and its `localCopySyncId` is unique. The Room writer owns entity, DAO, `GymDatabase`, migration, schema JSON, and migration tests as one atomic unit.
2. **Claim protocol.** A fresh database is `GUEST`; claim is a transaction which first rejects active work and any `CLAIMED(other)` state, then records B and `mergeId` without clearing guest tables. `OWNED(A)→B` is the only replacement branch, and it requires a clear preservation preflight inside the same Room transaction before any cleanup or token replacement. `CLAIMED(B)` is retained across sign-out and token loss; it is not reclassified as guest.
3. **Operation and ACK boundary.** An already durable outbox is attempted first only while its outcome is ambiguous (network/cancellation after dispatch): it reuses byte-identical `CloudPush.operationId` and request JSON. A definite `POST 409 revision_conflict` preserves that rejected batch, fetches the snapshot, atomically applies automatic policy, and replaces the outbox with a new operation only after recomputing against the fresh baseline while retaining unrelated local changes. If that fetched snapshot has any remaining explicit-resolution conflict, it returns to the current manual UX with **no** aggregate, baseline, or outbox mutation; the rejected bytes remain inspectable for the eventual choice. It never retries the rejected operation as if its outcome were unknown. Server originals alone enter acknowledged baseline. A newly cloned local routine and its durable map stay dirty and are included in a later push; they receive baseline only after that copy's ACK. `CLAIMED` becomes `OWNED` only after the last ACK, no durable outbox remains, and a fresh local snapshot equals the acknowledged baseline; batch limits and a concurrent local edit therefore retain `CLAIMED` and produce another batch. No claim is marked owned merely because tokens were written.
4. **Conflict policy, every run.** Before generic `CloudMerge` handling, classify all conflicts in the pulled snapshot. For `workout`, apply server record and its baseline atomically; do not retain/push a local competing snapshot. For a non-deleted `routine`, atomically look up/insert mapping, clone the local aggregate once using a new stable sync ID, retain the remote original under its original ID, apply it, and baseline **only the remote original**. Mapping lookup happens on every sync, not only initial merge. No exercises, gyms, or history are cloned with a routine. Future schedule references remain attached to the server original until CAL-01 explicitly decides otherwise. Routine delete-vs-edit and `exercise`/`gym`/`measurement`/`schedule` conflicts remain the current explicit-resolution path: before the user chooses, they make no aggregate, baseline, or outbox mutation.
5. **UI/state/navigation.** `AccountViewModel` exposes immutable account/transfer state sourced from `BackendSync`; AccountScreen has loading (claim/sync), recovery/error (`CLAIMED(B)`), existing conflict, and content states. It offers B-resume/return-to-account flow but no C override. State is restored from Room after recreation; only in-flight form/picker state stays in ViewModel/SavedStateHandle. No navigation route or Hilt binding changes are required.
6. **UI quality.** Account recovery uses existing `GymCard`, `PillButton`/outlined action, semantic text and actions, `MaterialTheme.colorScheme`, `GymMotion` only if motion is needed, and hardcoded Kotlin Russian strings. It fits the existing adaptive settings scaffold, provides non-colour error/status text, 48dp actions and a readable `fontScale=2.0` layout.

## Owner-switch preservation preflight (root amendment before implementation)

Under the singleton sync mutex and the final Room transaction, `OWNED(A)→B` rechecks active work and durable ownership before a read-only footprint query. Compare the sorted union of `PortableData.snapshot()` keys and `backend_baseline` payloads, covering inserts, edits and deletions. Independently inspect exact `backend_outbox` bytes, `catalog_state.originalOutbox` and pending catalogue transition state, and `configuration_tombstones`. Any difference or journal blocks replacement even if no ordinary dirty flag is set. Later health stages must extend this footprint with retained local audit versions and dedicated journals irrespective of consent; unsupported future ownership data must fail closed rather than be silently cleared.

Expose a typed blocked result with counts/flags and an opaque fingerprint, never payloads. Fingerprint includes owner/phase, backend generation and deterministic hashes of the full sorted footprint; generation alone does not cover every journal. The Account UI offers return/sign-in as A to preserve/sync data and cancel B login. This feature adds no destructive bypass or implicit health sync consent. A future separately confirmed full-erase flow must recompute the fingerprint inside its final transaction; a stale warning never authorizes deletion. Before such a flow exists, retained local-only data keeps the switch blocked.

A clear preflight and ownership transition run in the same transaction. Room commits the target ownership before AtomicFile B-token installation; a crash in that window must remain recoverable as the target, without rendering/writing using A credentials. The existing CLAIMED(B) recovery rule remains distinct: it rejects C irrespective of the footprint.

AC-002/006 and T-001/003/004/006 additionally require tests for an edit before outbox capture, lost-response outbox, originalOutbox-only catalogue transition, configuration tombstones, blocked sign-in leaving tokens/rows/journals unchanged, cancellation/process death before transition, active-work race, old A callback after a valid transition, and clean replacement retaining STANDARD. The later health feature adds sync-off local-version/journal cases before changing the footprint. No full-erase implementation or new consent is inferred here.

## Tasks

| Task | Exact files and owner | Depends | Action | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|
| T-001 | **Writer** — `vibe/guest-sync-plan.md`, `vibe/guest-sync-plan-track.md` (implementation preflight only) | — | Lock the claim, legacy-state migration, definite-409 versus ambiguous-operation, baseline and final-ACK contracts above before mutable source work. | Contract checklist review; no Gradle | Writer records actual predecessor `N` and confirms no unresolved contract before schema edits. | AC-001–007 |
| T-002 | **Same Writer, sole Room owner** — `data/backend/{BackendModels.kt,GuestMergeDao.kt,SyncSchema.kt}`; `data/db/GymDatabase.kt`; `app/schemas/com.valerochka1337.valerochkagym.data.db.GymDatabase/<N+1>.json`; `src/test/.../data/db/{Migration<N>To<N+1>Test.kt,Migration1To<N+1>Test.kt}` | T-001 | Add Room entities/DAO, explicit migration, database version/registry and schema. Migrate legacy `owner != null` to `OWNED(owner)`, `owner == null` to `GUEST`; preserve all old rows, baseline and exact outbox bytes; validate FK/index/unique mapping invariants. No destructive fallback. | `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests "*Migration<N>To<N+1>Test" --tests "*Migration1To<N+1>Test"` | Real incremental and supported full-path recreation prove both legacy owner branches, preserved IDs/history/baseline/outbox bytes and newest schema. | AC-002,003,007 |
| T-003 | **Same Writer** — `app/src/main/java/com/valerochka1337/valerochkagym/data/backend/{BackendModels.kt,PortableData.kt,BackendSync.kt,BackendApi.kt,BackendSyncWorker.kt}`; `ui/account/{AccountViewModel.kt,AccountScreen.kt}`; `di/BackendModule.kt` only if compilation proves a binding change | T-002 | Implement the frozen state machine, B-only token recovery, exact ambiguous retry, definite-409 pull/automatic-resolution/new-batch branch, server-only baseline, final-ACK proof, current-category inventory, owner/active guards, worker behavior, immutable UI state and accessible recovery UI. Retain singleton scopes; do not add dependencies, permissions, routes, or logging. | `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:compileDebugKotlin` | B claim/resume and A→B isolation are observable; no pre-choice mutation for explicit conflicts; automatic policy and batch lifecycle compile. | AC-001–006 |
| T-004 | **Same Writer** — `data/BackendSyncTest.kt`; `ui/AccountViewModelTest.kt`; `ui/AccountFormComposeTest.kt` only for changed semantics | T-003 | Add handwritten fakes and real Room tests: all current category union/references; both legacy migration branches; initial ACK after final batch; batch-limit and concurrent-edit remain CLAIMED; A→B and CLAIMED B→C; ambiguous lost response retries exact request; definite POST 409 preserves rejected batch, pulls, resolves, and safely replaces batch with unrelated edits; workout server-wins; routine server-only baseline then copy ACK and replay exactly two; explicit routine delete-vs-edit and exercise/gym/measurement/schedule no-mutation-before-choice; active-before-apply race; UI recreation/accessibility. | `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests "*BackendSyncTest" --tests "*AccountViewModelTest" --tests "*AccountFormComposeTest"` | Each AC has a lowest-layer regression; operation IDs and request bytes prove definite versus ambiguous paths; cancellation rethrows and no test uses mocks/private methods. | AC-001–007 |
| T-005 | **Same Writer, shared choke point** — `app/build.gradle.kts`; `vibe/guest-sync-plan-track.md` | T-004 | Increase `versionCode` once and patch `versionName` once relative to target when implementation is ready; record targeted evidence and deviations. | `./gradlew :app:compileDebugKotlin` | Exactly one feature version increment; no overlapping writer touches the build file. | AC-001–007 |
| T-006 | **Independent tester** (read-only production; sole Gradle owner) and **Sol/high reviewer** (read-only), tracker only | stable T-005 | In parallel: tester audits AC/cancellation/migration/recreation/worker coverage and runs only missing targeted gates; reviewer inspects state migration, 409 versus ambiguous operation handling, server-only baseline, final-ACK proof, conflict replay/security/isolation and UI accessibility. Return one consolidated finding packet. | tester chooses only invalidated/missing targeted tests; reviewer runs no Gradle | Gate T/V evidence records zero open P0/P1, or routes one consolidated fix batch to Writer. | AC-001–007 |
| T-007 | **Writer** — only files named by confirmed T-006 findings; tracker | T-006 only if findings | Apply one consolidated fix batch, rerun smallest invalidated test, have reviewer recheck affected findings; update tracker. | affected test command | No unresolved P0/P1; deviations/residual P2 risk explicit. | affected ACs |
| T-008 | **Root session** — final integration and tracker | T-006/T-007 | Run final gates sequentially once after the stable diff. | `./gradlew :app:testDebugUnitTest`; then `./gradlew :app:assembleDebug` | Both pass, schema is present, all AC evidence and review verdict are recorded. | AC-001–007 |

`<N>` is substituted with the actual predecessor version discovered immediately before T-002; it is deliberately not guessed while other feature work may be in progress.

## Ownership and waves

| Boundary | Owner | Files |
|---|---|---|
| All production, tests, Room and schema | one Writer | T-002–005 exact file sets; the only mutable implementation owner |
| Migration entity + DAO + database + migration + schema | same Writer | T-002 only; never split |
| Navigation/Hilt/version catalog choke points | same Writer | Account has no route change; `BackendModule` only if needed; `app/build.gradle.kts` in T-005 |
| Verification | independent tester + Sol/high reviewer | read-only source; tracker evidence/findings only |
| Final project gates | Root | T-008 |

Wave 1: T-001 contract freeze → T-002 schema → T-003 implementation → T-004 tests → T-005 version, sequentially. Wave 2: T-006 tester and reviewer in parallel. Wave 3: conditional T-007. Wave 4: root T-008. No Gradle process runs while the Writer edits.

## Quality gates

Relevant conditional gates: handwritten Room migration + newest exported schema; migration incremental and supported full recreation; atomic Room apply/claim/map tests; WorkManager unique/backoff/idempotent retry, definite 409 handling and cancellation; keystore/AtomicFile degraded recovery; Hilt singleton scope/no new binding unless needed; Account Compose loading/error/content, semantics, 48dp, font scale 2.0 and compact/expanded layout. No runtime permission, manifest, foreground-service, chart, release/R8/dependency, or Calendar/Google gate applies. Final root gates are the two T-008 commands; run `assembleRelease` only if a release-sensitive dependency/build/manifest change is introduced.

## Risks, blockers and rollback

The main technical risk is the unavoidable Room/AtomicFile split; the ordered protocol and B-only recovery contain it but cannot provide a cross-store atomic commit. Backend must honor exact operation IDs and supported portable kinds; otherwise AC-001/007 blocks release. A definite HTTP 409 must never be retried as an ambiguous request, while a lost response must reuse exact bytes. Routine mapping must canonicalize payload before hashing, and migration must reserve unique indices, or replay could clone again. The unresolved product policies (routine delete-vs-edit, other data kinds, and future plan reference reassignment) remain explicitly outside automatic resolution.

Rollback is a forward-compatible app rollback only after the new client has completed migration; no destructive downgrade is promised. Migration preserves the prior payload/outbox/baseline, and a failed apply rolls back state, mapping and aggregate together. Never clear a `CLAIMED(B)` dataset to make C work; recovery is B sign-in or an explicit future data-management decision.

## Gate P self-check

PASS: AC-001…007 each map to T-002–004/008 and named tests; contract freeze precedes schema, implementation and tests; one Writer owns every mutable production and Room file; tester/reviewer are read-only and parallel only after stability; legacy migration, final-ACK, 409/ambiguous-operation and no-mutation explicit-conflict gates are explicit. No blocker requires a product decision for stages 11–12.
