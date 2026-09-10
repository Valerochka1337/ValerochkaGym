# Stage 10 / #9 — workout notes and personal exercise hints

## Goal, scope, dependencies

Add one 0..2000 Unicode-code-point trimmed note to each factual workout/set and one owner-local
pinned hint per stable exercise `syncId`. Notes remain in the `workout` aggregate; `exercise_hint`
is a portable configuration record. Depends on stages 03/12 and the integrated CAL-01 schema.
Migration is the actual next `N→N+1` plus supported full path, never assumed 16→17.

No program notes/tags/files, AI interpretation, trainer/health sharing, second outbox/worker, or
`STANDARD` catalog mutation. Future AI receives notes only as typed dated data, never instructions,
authority, or permission.

## Acceptance criteria

| ID | Acceptance criterion |
|---|---|
| AC-001 | Active workout/set notes create, replace and delete durably by persistent IDs; blank trim deletes and 2001 code points shows accessible error. |
| AC-002 | Set note targets `WorkoutSetEntity.id`; reorder/duplicate cannot transfer it and cascades delete it. |
| AC-003 | History shows workout and complete/incomplete set notes; a notes-only incomplete set survives finish prune, remains uncompleted, and never enters program copy. |
| AC-004 | Personal/`STANDARD` exercise has one owner-local hint; edit/unpin changes no exercise origin/syncId/muscle/equipment/catalog field. |
| AC-005 | Active and detail exercise show/edit/unpin hint before first set action with accessible adaptive UI. |
| AC-006 | Old reads retain workout shape; incapable annotated-workout write is rejected before mutation, while compatible resource writes continue. |
| AC-007 | `exercise_hint` is syncId-keyed, owner-scoped, guest-mergeable and capability-filtered before paging; no foreign/catalog leak or duplicate. |
| AC-008 | Server snapshot wins whole workout-history conflict; server hint/tombstone wins without collateral aggregates. |
| AC-009 | Active workout blocks inbound apply; post-finish apply is atomic and local note edit during HTTP remains dirty. |

## Frozen data, compatibility, and flow

Room adds `workout_sets.note TEXT NOT NULL DEFAULT ''` and `exercise_personal_hints(exerciseSyncId
TEXT PRIMARY KEY,text TEXT NOT NULL,updatedAt INTEGER NOT NULL)`. Hint uses syncId, no local-FK.
`WorkoutDao` note updates constrain expected workout ID; `WorkoutSetMutator` alone changes numbers/
completion. `SyncSchema.trackedTables` adds hints and reuses generation/outbox/worker.

Workout wire keeps workout `note` and adds optional/default-empty set `note`. Hint id is exercise
syncId with `{text,updatedAt}` and tombstone. Hints clear with owner cache and use Stage-12 claim;
server/tombstone wins same-key conflict. Workout server aggregate wins including notes.

Existing workout-level notes retain the legacy backend max10000 contract and are never truncated
on read/migration; the new editor accepts up to2000 code points for a changed value. The new hint/set
fields use the2000 bound. New/changed live hints require a live personal or public exercise;
deleting that exercise later retains an existing hint without blocking the deletion. Hint tombstones
remain valid. A clean previously-ACKed hint hidden by capability downgrade must retain Room and its
baseline exactly; omission of an unsupported kind never implies remote deletion.

Root frozen fixture: `vibe/workout-notes-sync-contract.json`, SHA256
`5a98f491bbb658c76e47933b49b50041fe1f1a16618b9cadfcf1befdbd22818b`.
Copy into each checkout only on its feature branch, before the respective implementation.

The exact legacy strategy is resource-specific `annotated-workout-writes`, never account v4. For an
incapable client, GET `/sync`, `/sync/changes` **before cursor construction**, records list and
single-record reads strip only the new set `note`; existing workout `note` remains legacy shape and
never triggers a reject. Any incapable upsert **or tombstone** that would discard stored nonempty set
note is atomically rejected before operation ledger/revision with
`409 annotated_workout_requires_capability`. Other compatible resources still write. A capability
absence→accepted transition invalidates owner-scoped cached projection and forces a full refresh;
no old cursor permanently omits annotations.

`exercise-hint` uses CAL-01 optional intersection, owner cache, filtering *before* snapshot/changes
cursor paging/records, and atomic unsupported POST rejection; dirty hints stay retained. Android
advertises and caches both `annotated-workout-writes` and `exercise-hint` per owner. Unsupported
annotated changes are filtered before a new outbox/batch, remain dirty, and never cause ambiguous
outbox rebuild, partial ACK, or blocked compatible records. Exact incapable-client predicate:
legacy workout projection may be sent only when BOTH current and acknowledged-baseline set notes
are empty (including a pending tombstone's baseline). Strip empty new set-note fields for that
compatible projection. Otherwise retain the aggregate dirty until capability acceptance; never drop
a previous note deletion by examining current text alone. An already-dispatched annotated outbox
stays byte-identical until its outcome is resolved. Test empty/empty, nonempty/empty, empty/nonempty,
nonempty/nonempty and tombstones, with exact sent-payload baselines.

`UI draft → validated Room transaction → Flow/ViewModel state → existing screens → tracked generation
→ PortableData aggregate/hint → BackendSync outbox/ACK`. SavedState has target/draft/error/token only.
Finish pruning treats a nonempty note as content but not completion/program-copy eligibility.

## Tasks

| ID | Exact files | Owner | Depends on | Actions | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|---|
| T-001 | `vibe/workout-notes-sync-contract.json`; backend `src/test/resources/workout-notes-sync-contract.json`; Android `app/src/test/resources/workout-notes-sync-contract.json` | root contract owner | CAL-01 strategy | Freeze byte-identical note/hint/capability/tombstone/old-read-reject-write/conflict fixture. | `cmp` and SHA-256 | Same canonical contract. | AC-006–AC-008 |
| T-002 | Backend `/Users/raul/ItmoProjects/ValerochkaGymBackend`: `controller/data/DataController.kt`, `service/data/{SyncService,RecordValidator}.kt`, sync models/capability parser, changelog/master, `BackendIntegrationTest.kt` | backend writer | T-001 | Add hint kind; full GET sync/changes-before-cursor/list/single/POST legacy matrix; strip only set notes; atomic upsert/tombstone rejection before ledger/revision; absence→accepted full refresh. | backend integration target + bootJar | Annotations cannot be erased and compatible writes work. | AC-006–AC-008 |
| T-003 | backend fixture/config tests and tracker | independent backend tester + readonly Sol/high reviewer | T-002 | Read-only audit server-wins/tombstone, owner/guest/CAL compatibility and full matrix. No edits here; send findings to T-003F. | targeted audit + read-only review | Findings recorded for T-003F. | AC-006–AC-009 |
| T-003F | Backend T-002 files | backend writer fixes; readonly reviewer rechecks; root final | T-003 | Conditional fix batch and affected tests, narrow recheck, then stable full check/bootJar before fixture handoff. | affected targets then full backend suite | Accepted fixture, no P0/P1. | AC-006–AC-009 |
| T-004 | Android `data/db/entity/{WorkoutSetEntity,ExercisePersonalHintEntity}.kt`, `dao/{WorkoutDao,ExercisePersonalHintDao}.kt`, `GymDatabase.kt`, actual migration/schema/tests; `data/backend/{PortableData,SyncSchema,BackendSync}.kt`, `data/{ActiveWorkoutRepositoryImpl,ExercisePersonalHintRepository}.kt`, domain/DI bindings | Android writer | T-001,T-003F,03/12/current CAL schema | Sole Room owner implements notes/hints/cascade and advertises/caches both capabilities per owner. Filter unsupported annotation before outbox/batch; dirty retained, compatible mixed batch sends, no rebuild/partial ACK; absence/downgrade→later accepted full refresh. | targeted migration/DAO/BackendSync tests | Durable owner-safe records and exact retry/baseline semantics. | AC-001–AC-004, AC-006–AC-009 |
| T-005 | `ui/active/{ActiveWorkoutViewModel,ActiveWorkoutScreen}.kt`, `ui/history/{WorkoutDetailViewModel,WorkoutDetailScreen}.kt`, `ui/exercise/{ExerciseDetailViewModel,ExerciseDetailScreen}.kt`, their tests, `domain/ActiveWorkoutRepositoryTest.kt`, `domain/SaveCompletedWorkoutAsRoutineUseCaseTest.kt`, and hint repository tests, `app/build.gradle.kts` | Android writer | T-004 | Add saveable editors, including in-flight hint edit/delete token race; hint edit/unpin remains available on `STANDARD` because guard protects catalog mutation only. Add notes-only finish prune/program-copy repository tests; preserve routes and one #9 bump. | targeted active/history/exercise/repository tests + compile | Room truth, accessible personal hint, no program copy. | AC-001–AC-005, AC-009 |
| T-006 | *(no edits)* | independent Android tester + readonly Sol/high reviewer | T-005 | Read-only audit migration, legacy loss, mixed supported/unsupported baseline/outbox/late-local dirty state, privacy, prune and UI. No edits here; route findings to T-006F. | smallest invalidated targets + recheck | No P0/P1. | AC-001–AC-009 |
| T-006F | Android T-004/T-005 files | Android writer fixes; readonly reviewer rechecks | T-006 | Conditional consolidated fixes with affected targets, then narrow reviewer recheck. | affected tests + recheck | No P0/P1 before root gates. | AC-001–AC-009 |
| T-007 | `vibe/workout-notes-plan-track.md` | root | T-006F | Record final evidence/version and run final Android gates. | unit, debug, unsigned release attempt | All AC evidence. | AC-001–AC-009 |

## Ownership, gates, risks

T-001 precedes backend writer T-002, then independent T-003 audit/recheck. One Android writer owns T-004–T-005,
including Room/schema/migration, DI/navigation/version choke points. Strict gates cover incremental+
full Room migration/schema, Unicode/cascade, GET sync/changes-before-cursor/list/single/POST matrix,
capability absence/downgrade/full-refresh, mixed batch/exact retry/atomic reject, guest/owner/tombstone/
active races, in-flight hint edit/delete and late-local dirty state, notes-only prune, semantics/48dp/fontScale/adaptive, backend
integration and Android unit/debug/unsigned-release final gates.

Risk: actual N depends on integrated work; owner-scoped capability filter must precede cursor creation.
Rollback retains additions/tombstones; narrow old annotated write failure prevents loss while other
legacy sync continues. Gate P: all ACs map to tasks/tests; compatibility and privacy are frozen.
