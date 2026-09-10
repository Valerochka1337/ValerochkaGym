# PLAN-01 — training proposals

Slug: `training-proposals`. Status: planned; strict path. This is the common proposal/approval contract for #53 (AI calendar, stage 21) and #42 (coach, stage 23). It follows [training-proposals-brief.md](training-proposals-brief.md), AI-01, CAL-01 and guest-sync; Android implementation starts only after the backend contract and CAL-01 aggregate are accepted.

## Goal, scope and assumptions

Recipient sees a versioned AI/coach proposal, may edit only a local approval draft, then explicitly apply or reject it. Server approval creates exactly one recipient-owned routine and one calendar-plan record; Android imports that authoritative result once through its normal owner-safe sync path.

In scope: server proposal lifecycle, authorization hook, canonical draft validation, durable idempotency/recovery, Android inbox/preview and accepted-result projection. Out: AI generation/context policy (AI-01/21), relationship invitation UX/data (stage 23), recurrence and Google transport (CAL-02), history edits, health/profile/notes payloads, direct coach writes and auto-approval.

Assumptions frozen for PLAN-01: expiry is seven days; start instant must be future at approval; one one-off plan per approval; max 30 exercises and 20 sets/exercise; a missing same-exercise history weight is `null`; active workout blocks local apply. Exact Room predecessor N and backend migration ID are resolved by their sole writers immediately before source edits.

## Acceptance criteria

| ID | Criterion |
|---|---|
| AC-001 | Server owns proposal UUID, typed `AI`/`COACH` author, recipient, immutable versions/snapshots, timestamps, expiry and terminal `PENDING/APPROVED/REJECTED/REVOKED/STALE` state. AI is a server actor, never a fake user FK; coach uses account ID. |
| AC-002 | Server authorization permits recipient status/data reads; coach create/read/update only while an accepted active relation exists. Relation revoke immediately blocks coach and pending approval. PLAN-01 installs an authority hook that denies COACH until stage 23 supplies that relation; no client flag grants access. |
| AC-003 | Every snapshot/draft is a bounded planned routine plus one-off plan: canonical exercise and equipment IDs, ordered sets, nullable weights, rest, future start and IANA zone. It excludes local IDs, health/profile/notes, prompts and Google transport. Server validates canonical catalogue/equipment/context before preview and revalidates at approval. `null` from missing history is AI inference only; explicit coach and recipient weights remain valid. |
| AC-004 | Preview draft is locally editable and durable for the current version, shows author/source/version and changes, creates no routine/calendar/portable record, and becomes unusable on a newer author version, expiry or stale context (`needs_refresh`, never a silent substitute). |
| AC-005 | `approve(id, version, operationId, draft, requestFingerprint)` is exactly-once: Android persists immutable canonical request bytes before dispatch and exact retry reuses them; server recomputes the fingerprint. Ledger binds recipient/proposal/version/operation/body. Same operation with changed bytes is 409; another operation after APPROVED returns the same accepted result; REJECTED/REVOKED/STALE return explicit terminal errors. Result IDs are allocated by server. |
| AC-006 | Under one server DB transaction every create/update/approve/reject/proposal-revoke/relation-revoke acquires `catalogue → recipient head → relation (COACH) → proposal → auth/revocation`, revalidates prereads plus locked session revision, then writes. Approval terminalizes proposal, creates routine+calendar-plan, records owner head revision and operation ledger atomically. Races/restart/response loss cannot create duplicates or deadlock. |
| AC-007 | Android never allocates result UUIDs, manually inserts a calendar plan, or uploads a duplicate dirty copy after approval. Under the owner mutex it applies one full authoritative `BackendSync` snapshot by stable UUID/payload, baseline and projection key in one transaction; the result head is not a global cursor and cannot skip unrelated sync changes. Pre-existing outbox is preserved and owner/active guards are rechecked before, after and inside the transaction. |
| AC-008 | Reject is recipient-only, idempotent for the version and creates nothing. Revoke is author-only before approval. Existing completed history and unrelated routines/calendar plans are never changed. |
| AC-009 | UI has loading, empty, content, validation, error/retry, stale/expired, rejected and applied states; Apply/Reject/Back are distinct, adaptive and accessible at 2x font scale. Dismiss decides nothing and approval does not navigate into an active workout. |

## Current → target flow and frozen contracts

Current Room is configuration SSOT (`RoutineEntity.syncId`, `GymRepository`, legacy `ScheduledWorkoutDao`); `PortableData`/`BackendSync` own sync and active-workout protection. `SaveCompletedWorkoutAsRoutineUseCase` is not reusable: it derives a routine from history. CAL-01 replaces the legacy schedule boundary with `CalendarPlanRepository`.

```text
author or AI-01 → backend proposal/version snapshot → recipient inbox → local approvalDraft journal
  → approve(operationId, exact body) → [single server transaction: validate/lock/terminalize/create/ledger]
  → accepted result/status by operationId (IDs/revision are validation metadata, never a cursor)
  → full authoritative BackendSync snapshot using normal owner-safe sync
  → [normal Room sync transaction: server-created UUID records + authoritative baseline
     + projection key + response cursor; preserve any pre-existing outbox]
  → CalendarPlanRepository observes the applied graph; no approval-path local insert/outbound copy
  → CAL-02 later journals Google work
```

Server is SSOT for proposal, relationship authorization, decision and accepted IDs. Room is SSOT only for the recipient projection. Proposal itself is not `PortableData` before approval. Android scopes draft, operation and projection records by owner+proposal+version; it persists edited accepted draft and immutable canonical request bytes before exposing/requesting approval. Cancellation before/after dispatch and process death leave recovery through that journal, never a rewritten retry body. Network calls are repository-owned, on injected IO; ViewModels own only `viewModelScope` request lifetime. No WorkManager is introduced: recovery is explicit inbox/status fetch plus existing sync; CAL-02 owns deferrable Google work.

Frozen wire behavior: `approvalDraft` is the normalized current-version preview; its exact canonical bytes form `requestFingerprint`. Response loss and another device recover via `GET accepted-result`/proposal status. Result carries server allocated routine UUID, calendar-plan UUID, owner head revision and terminal proposal version. A stale/expired proposal returns `needs_refresh`; it cannot be remapped to a newer version. Context freeze records owner/catalogue revision; relevant change rejects decision. AI-01 later supplies a validated draft via its source hook; PLAN-01 never calls a provider. Future human and AI entry points reuse this preview contract.

Server lock order for every proposal/relation mutation is `canonical catalogue → recipient owner head → relation (COACH) → proposal → auth/revocation`; each lock is acquired once in that order. Revalidate all predicates and the authenticated session revision after locks, then write terminal state, result aggregate and operation ledger atomically. Only an internal AI principal creates `AI` source: request clients cannot select actor/source. `COACH` authorization calls `CoachRelationAuthority`; missing/failed authority is deny-by-default and its PLAN-01 implementation always denies until stage 23 replaces only that implementation with an accepted-relation implementation. Recipient reads remain permitted for their own proposal/status.

Android UI uses immutable `TrainingProposalUiState` and `TrainingProposalEvent`; `SavedStateHandle` holds route/proposal/version and Room stores the owner/proposal/version-scoped draft, operation and projection journals. Restore accepts only the exact owner and version: A→B→A cannot carry, remap or expose B state. Compose collects lifecycle-aware `StateFlow` built with `stateIn(WhileSubscribed(5000))`. Pushed proposal detail uses `GymNavGraph` slide/fade specs, Material colors/components, 48dp controls, semantics that name source/version/status/effect, and `GymHaptics` semantic feedback only.

## Tasks

| Task | Exact files / owner | Depends | Action | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|
| T-001 | Contract owner: `vibe/training-proposals-plan.md`, `vibe/training-proposals-plan-track.md`, `vibe/contracts/training-proposals-contract.json`; backend `/private/tmp/yarumo-backend-delivery/vibe/training-proposals-plan.md`, `/private/tmp/yarumo-backend-delivery/vibe/training-proposals-plan-track.md` | AI-01, CAL-01, guest-sync contracts | Freeze DTO/ledger/result schema, canonical request-byte fixture/fingerprint, error/status matrix, authority hook and global lock order. | `cmp`/`shasum -a 256` fixture parity | Backend and Android writers accept one byte-level contract; no field or retry ambiguity remains. | AC-001–006 |
| T-002 | Backend writer (sole server writer): exact files in `/private/tmp/yarumo-backend-delivery/vibe/training-proposals-plan.md` T-002 ownership table—proposal/relationship entities+DAO, migration, transaction service, controller/DTO, operation ledger, auth hook, tests | T-001, accepted next basic-profile backend baseline | Implement server lifecycle, internal-AI-only source creation, seven-day expiry, bounded validation, canonical catalogue/equipment checks and global lock/revalidation transaction. Create routine/calendar-plan with server UUIDs/head revision and terminal+ledger atomically. | backend focused proposal transaction/auth integration tests | Race/deadlock/barrier, spoof/missing/throw authority, three-source weight, cancellation and response-loss tests pass. | AC-001–008 |
| T-002V | Independent backend tester/reviewer (read-only) | stable T-002 | Strictly audit backend transaction, ledger, authorization and all P1 race/fault cases before Android begins. | backend full test + boot artifact | No P0/P1 or one consolidated backend finding packet. | AC-001–008 |
| T-002F | Original backend writer; backend root final acceptance | T-002V findings | Fix confirmed backend findings, rerun invalidated tests, then root accepts backend baseline before Android T-003. | affected tests then backend full test + boot artifact | Accepted backend contract/baseline, currently reported as 68 passing tests. | AC-001–008 |
| T-003 | Android writer (sole Room/sync/UI writer): `app/src/main/java/com/valerochka1337/valerochkagym/data/trainingproposal/{TrainingProposalApi,TrainingProposalModels,TrainingProposalRepository,ApplyTrainingProposalUseCase}.kt`, `data/db/{GymDatabase.kt,entity/TrainingProposalDraftEntity.kt,entity/TrainingProposalOperationEntity.kt,entity/TrainingProposalProjectionEntity.kt,dao/TrainingProposalDao.kt}`, `data/backend/{BackendApi.kt,BackendModels.kt,BackendSync.kt,PortableData.kt}`, CAL-01 `CalendarPlanRepository` files, `di/{BackendModule.kt,DataModule.kt,DomainModule.kt}`, `app/schemas/.../N+1.json`, matching `app/src/test/...` | T-001,T-002F,CAL-01 accepted Android aggregate | Persist owner-scoped edited draft/operation/canonical bytes before HTTP. Under owner mutex invoke normal full authoritative `BackendSync` snapshot application: stable UUID/payload+baseline+projection key atomically, preserving existing outbox and all pre/post/in-transaction owner/active guards. Do not call manual `CalendarPlanRepository.insert`, create a dirty outbox copy, or treat result head as global cursor. Register one handwritten migration. | focused repository/Room migration/BackendSync tests; `./gradlew :app:compileDebugKotlin` | Exact retry, cancellation/death, owner A-B-A restore, response loss, multi-device/head race and active-workout race yield one projection without second POST or duplicate outbox. | AC-003–008 |
| T-004 | Same Android writer: `ui/trainingproposal/{TrainingProposalViewModel.kt,TrainingProposalScreen.kt,TrainingProposalModels.kt}`, `ui/navigation/GymNavGraph.kt`, inbox host chosen from CAL-01/21, focused ViewModel/Compose tests | T-003 | Build inbox/detail/editable preview and immutable state/events; retain route/version in SavedStateHandle, draft in Room; implement distinct apply/reject/back and retry/refresh outcomes. | focused `*TrainingProposal*Test`; Compose semantics/adaptive tests; `:app:compileDebugKotlin` | All visible states, font scale, compact/expanded, stale/version and no-auto-navigation behavior pass. | AC-004,008,009 |
| T-005 | Same Android writer: `app/build.gradle.kts`, tracker | T-003,T-004 | Compare target version, make one code/version increment, record actual N, manifest/fixture hash and targeted commands. | `./gradlew :app:compileDebugKotlin` | One feature bump and Gate I evidence recorded. | AC-001–009 |
| T-006 | Independent Android tester/reviewer (read-only; tester is sole Android Gradle owner), tracker findings | stable T-005 | Audit Android AC traceability and run only missing targeted tests: exact retry/409 recovery, crash/response loss, owner/head/active races, local transaction/outbox and lifecycle/accessibility. Backend is already accepted in T-002F. | Android targeted tests | Android Gate T/V has no P0/P1; consolidated findings name owner/files. | AC-003–009 |
| T-007 | Original writer(s), affected files only; tracker | confirmed T-006 findings | Make one bounded correction pass and rerun only invalidated tests. | smallest affected command | No open P0/P1; accepted P2 residual risk is explicit. | affected AC |
| T-008 | Root integration owner, tracker | T-006/T-007 | Run final Android project gates after stable diff and carry forward accepted backend T-002F evidence. | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` | Every AC has command/result evidence; no deployment or Google transport claim. | AC-001–009 |

## Ownership and waves

| Boundary | Owner | Files |
|---|---|---|
| Contract, fixture, plans/tracker | Contract owner | T-001 only |
| Backend transaction/auth/schema/ledger | one backend writer | all T-002 server files; entity+DAO+migration stay together |
| Android Room/sync/projection/UI/navigation/Hilt/version | one Android writer | T-003–005; shared choke points remain single-writer |
| Verification | independent tester/reviewer | read-only source; tracker findings |

Wave 1: T-001. Wave 2: after the next basic-profile backend baseline, T-002 → independent T-002V → backend T-002F/root acceptance. Wave 3: Android T-003 only after that accepted backend baseline and CAL-01 aggregate. Wave 4: T-004/T-005. Wave 5: Android-only read-only T-006, then conditional T-007 and final Android integration T-008. No Android Gradle while its writer edits.

## Quality gates, risks and rollback

Relevant conditional gates: handwritten Room N→N+1 and supported full migration/schema; transaction/fault/cancellation/idempotency tests; injected dispatcher and Hilt singleton scopes; existing outbox/worker idempotency; navigation restoration; adaptive/48dp/TalkBack/fontScale 2.0. No new permission, manifest, foreground service, chart, dependency or release-sensitive build change is planned; `assembleRelease` applies only if implementation introduces one. Final gates are the two Android commands in T-008.

Risks: CAL-01/guest-sync predecessor contracts may change N or projection APIs; server relation storage belongs to stage 23 but PLAN-01 must keep the deny-by-default hook; Room and network cannot commit together, so projection journal/replay is mandatory. Forward rollback preserves approved server results and local projection journals; destructive database fallback, history rewrites, deleting an accepted routine, or replaying a Google operation are prohibited. An approved result that fails local apply remains recoverable by status/operation ID.

## Gate P self-check

PASS: AC-001…009 map to T-001…008 with named verification; known backend plan/tracker paths replace the invented manifest; backend strict I→T/V→fix/root acceptance precedes Android; server and Android have non-overlapping ownership; the server transaction and Android full-authoritative projection contracts are frozen before work; Room aggregate/migration/schema have one owner; only relevant conditional gates are included.
