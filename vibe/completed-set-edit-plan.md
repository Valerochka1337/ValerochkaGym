# Plan: completed-set-edit (#44, stage 03)

## Goal

Let a user correct the actual numeric values of a completed set in an active workout, without
changing completion, focus, or rest-timer behavior.

## Scope

- Completed strength, timed, and cardio set pill opens a type-aware draft dialog.
- Save flows through the existing process-wide `WorkoutSetMutator` and reports a guarded outcome.
- Persist only numeric fields valid for the actual database exercise type, and only when its set is
  completed and its workout is active.
- Add focused unit, repository/DAO, ViewModel, and Compose accessibility coverage.

## Non-goals

- Stage 04 finish action (#47), its visible controls, or changes to the all-completed predicate.
- Schema, migration, sync, notification, rest-engine, navigation, dependency, or Hilt changes.
- Editing an incomplete set through this dialog; it retains the existing controls.

## Assumptions

- A completed set belongs to the one active workout exposed by `observeActive`; a delayed write
  after finish, delete, uncomplete, or exercise-type mismatch must be rejected, never resurrect
  data or alter a different field set.
- Strength edits `weightKg` and `reps`; timed edits `durationSec`; cardio edits `durationSec`,
  `speedKmh`, and `inclinePct`. Empty valid fields persist as null, following current NumberField
  parsing semantics; invalid input keeps Save disabled with an inline error.
- The existing `SetActions.uncomplete` remains a separately labelled explicit action in the dialog.

## Acceptance criteria

| ID | Criterion |
|---|---|
| AC-001 | Tapping a completed set opens a prefilled actual-values draft for strength, timed, or cardio; Cancel makes no write and uncomplete remains explicit. |
| AC-002 | Save writes only the selected type’s numeric fields over the fresh row; `isCompleted`, `completedAt`, IDs, index, and concurrent unrelated fields remain unchanged. |
| AC-003 | Save never calls `CompleteSetUseCase`, changes current focus, or starts, resets, stops, or adjusts rest. |
| AC-004 | A missing set or a set whose workout finished before queued save produces a clear failure message; independent concurrent field changes survive. |
| AC-005 | `WorkoutSetMutator` remains the sole process-wide writer. UI cancellation/recreation cannot block its queue or apply a completion result to a different draft. |
| AC-006 | The dialog uses existing Material/project patterns, 48dp controls, meaningful semantics, type/text validation, and works at font scale 2.0 and compact/medium/expanded widths without dependencies. |

## Current → target flow

**Current:** `CompletedSetPill` tap → `uncompleteSet` → repository completion-flag update. Step
controls use `WorkoutSetMutator` → read set → `@Update`; that update has no active-workout guard
and no result. `CompleteSetUseCase` alone owns completion plus rest.

**Target:** `CompletedSetPill` tap → ViewModel-owned, saveable immutable `CompletedSetEditDraft`
(set id, type, token, raw numeric fields, validation/submission state) → dialog events →
`WorkoutSetMutator.editCompletedNumbers` request/reply queue → fresh `getSet` → type-scoped numeric
projection → repository per-type guarded numeric update → DAO returns affected-row count → outcome
back to the same token → dismiss on success or keep draft and show retryable message on rejection. Room
remains SSOT; reactive `observeActive` redraws content. No timer/service/worker call occurs.

## Frozen contracts and decisions

- `WorkoutSetMutator` stays `@Singleton` on `@ApplicationScope`; it owns channel consumption and
  cancellation-independent completion of every queued reply. Its completed-set edit API is
  suspending and returns a closed outcome (`Saved`, `MissingOrInactive`), never a fire-and-forget
  write. A cancelled caller may stop awaiting, but must not stall the consumer.
- The mutation reads the row only in the queue, applies a pure type-scoped numeric transform, then
  delegates to a repository operation that updates numeric columns only. It never calls
  `toggleSetCompleted` or `CompleteSetUseCase`.
- `ActiveWorkoutRepository` exposes the guarded numeric-update boundary and outcome; implementation
  maps zero affected rows to `MissingOrInactive`. `WorkoutDao` exposes three per-type guarded
  `UPDATE`s: strength changes only `weightKg,reps`; timed only `durationSec`; cardio only
  `durationSec,speedKmh,inclinePct`. Each requires `ws.isCompleted = 1`, and an `EXISTS` join from
  set → workout exercise → workout → exercise that requires `finishedAt IS NULL` and the expected
  actual database `ExerciseType`. It changes neither completion columns, IDs/index/relation fields,
  nor numeric columns outside that type, so concurrent unrelated numeric edits survive.
- The ViewModel owns draft lifecycle through `SavedStateHandle`; composables receive immutable
  state and send open/change/save/cancel/uncomplete events. Saved state contains primitives only:
  set id, expected type, token, and raw field texts. Submission and error are always reconstructed
  as idle. After the first loaded Room snapshot, the ViewModel validates that the restored set still
  exists, is completed, and has the expected actual type; otherwise it clears it and emits the
  unavailable message. A monotonically new draft token gates async outcome handling, so an old save
  cannot dismiss/message a newer dialog. Back/dismiss and Cancel remove the draft without enqueuing
  a write.
- No navigation route changes: the dialog is an overlay within `active_workout`; normal back first
  dismisses it. Restored active-workout navigation restores only the draft when its set still
  appears completed; otherwise it is discarded with the usual unavailable message.
- No Room schema/migration, Hilt binding/scope, permission, WorkManager/service, or dispatcher
  change is needed. Numeric read/DAO write is short I/O in existing repository/Room context; no
  new compute flow is introduced.

## Tasks

| ID | Exact files | Owner | Depends on | Actions | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|---|
| T-001 | `app/src/main/java/com/valerochka1337/valerochkagym/domain/WorkoutSetMutator.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/domain/ActiveWorkoutRepository.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/ActiveWorkoutRepositoryImpl.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/db/dao/WorkoutDao.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/domain/WorkoutSetMutatorTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/domain/ActiveWorkoutRepositoryTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/data/WorkoutDaoTest.kt` | implementation writer | — | Freeze and implement request/reply outcome queue, numeric projection, repository outcome mapping, and three per-type guarded numeric-only SQL updates. Use real Room for missing, inactive, uncompleted, and wrong-type rejection; verify all numeric fields outside the selected type and completion metadata survive concurrent writes. | `./gradlew :app:testDebugUnitTest --tests "*WorkoutSetMutatorTest" --tests "*ActiveWorkoutRepositoryTest" --tests "*WorkoutDaoTest"` | Only a completed set of the expected actual DB type in an active workout returns `Saved`; rejected/cancelled requests complete their replies and later queued work executes. | AC-002, AC-004, AC-005 |
| T-002 | `app/src/main/java/com/valerochka1337/valerochkagym/ui/active/ActiveWorkoutViewModel.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/ActiveWorkoutViewModelTest.kt` | implementation writer | T-001 | Add primitive-only `SavedStateHandle` draft persistence, immutable draft/event contract, first-loaded-Room validation, and token-gated save handling. Reset submission/error to idle upon reconstruction; retain draft on rejected retry. Keep uncomplete/rest/complete paths unchanged. Use existing `MainDispatcherRule`, live `uiState` collector, and handwritten fakes. | `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkoutViewModelTest"` | Tests cover recreation with the same `SavedStateHandle` preserving raw input and retry state, invalid restored snapshot clearing, and stale outcome isolation; save leaves focus/rest/completion behavior untouched. | AC-003, AC-004, AC-005 |
| T-003 | `app/src/main/java/com/valerochka1337/valerochkagym/ui/active/ActiveWorkoutScreen.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/active/ActiveWorkoutScreenTest.kt` | implementation writer | T-002 | Replace completed-pill tap with edit opening; render a project-style alert dialog with prefilled, type-specific numeric fields, validation/error text, Cancel, Save, and distinct uncomplete action. Use `gymHaptics()` semantic events, Material colors/shapes, 48dp targets, and explicit TalkBack labels/state/actions. | `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkoutScreenTest"` | Compose tests prove each type opens correct fields, Cancel/uncomplete are distinguishable, invalid Save is blocked, and semantics support edit/uncomplete at large font assumptions. | AC-001, AC-006 |
| T-004 | `app/build.gradle.kts`, `vibe/completed-set-edit-plan-track.md` | implementation writer | T-001–T-003 | Before final gates, compare version against the target branch and apply exactly one #44 `versionCode +1` and patch `versionName +1` if this feature has not already done so; record targeted implementation evidence and deviations. | `./gradlew :app:compileDebugKotlin` | Version is strictly above target branch and is bumped once only; implementation targeted checks are recorded. | AC-001–AC-006 |
| T-005 | *(no file edits; tester/reviewer report to root)* | independent tester + readonly Sol/high reviewer | T-004 | In parallel, tester audits AC coverage and runs the smallest missing targeted test; reviewer performs read-only strict diff review of contracts, persistence, cancellation, UI, and version bump. Implementation writer applies one consolidated fix pass and reruns affected targeted checks; reviewer rechecks only fixed findings. | tester: applicable filtered `:app:testDebugUnitTest`; reviewer: read-only diff review | No open P0/P1 findings; tester and reviewer evidence is supplied to root. | AC-001–AC-006 |
| T-006 | `vibe/completed-set-edit-plan-track.md` | root session | T-005 | On the stable diff, run final project gates sequentially exactly once and record outcomes, AC status, deviations, and residual risks. | `./gradlew :app:testDebugUnitTest` then `./gradlew :app:assembleDebug` | Every AC has passing evidence and no unresolved P0/P1; tracker records failures verbatim. | AC-001–AC-006 |

## File ownership and execution waves

| Owner | Exclusive production/test responsibility |
|---|---|
| implementation writer | T-001 through T-004: all listed production/test files, the shared queue/repository/DAO/UI contracts, and implementation-side tracker evidence. |
| independent tester | T-005 only; no edits, reports targeted test/AC findings to root. |
| readonly Sol/high reviewer | T-005 only; no edits, reports strict diff findings to root. |
| root session | T-006 final-gate execution and final tracker result recording after stable review. |

One writer is required because the queue/repository/DAO contract and the active-screen callback are
shared choke points. Preserve concurrent work outside this exact set; do not modify the separate
stage 01–02 feature files.

1. Wave 1: T-001, freeze and verify persistence/queue contract.
2. Wave 2: T-002, then T-003 after the contract compiles; implementation writer runs each
   targeted test filter and T-004 version bump/compile check.
3. Wave 3: T-005 tester and Sol/high read-only reviewer run in parallel; one consolidated fix and
   affected recheck follows if needed.
4. Wave 4: root runs T-006 final sequential project gates once on the stable application diff.

## Quality gates

- Always: AC traceability, Room-as-SSOT, unidirectional immutable UI state/events, handwritten
  fakes, no logs/mocks/dependencies/destructive fallback.
- Concurrency: test queue ordering, missing/finished/uncompleted/wrong-type rejection, caller
  cancellation, same-SavedState recreation/retry, and stale draft-token delivery.
- Compose: verify loading/content remains unchanged; dialog validation/error/content, back/dismiss
  behavior, 48dp actions, TalkBack semantics, font scale 2.0, and compact/medium/expanded layout.
  No chart, permission, navigation-transition, worker/service, DI, dependency, release/R8, or Room
  migration gate applies.
- Version: before final gates the implementation writer checks the target-branch version and makes
  the required single #44 code/patch bump in `app/build.gradle.kts`.
- Final only after tester/reviewer/fix stabilization: root runs `./gradlew :app:testDebugUnitTest`,
  then `./gradlew :app:assembleDebug`, exactly once. No Gradle command is run for this plan-only change.

## Risks, unresolved questions, rollback/data preservation

- **Risk:** a broad entity `@Update` or an assumed UI type could overwrite unrelated numeric values
  or write after completion changes/finish. **Control:** per-type guarded SQL predicates on the
  actual DB type and completed/active state plus real-Room regression tests.
- **Risk:** delayed save feedback can target a newly opened dialog. **Control:** saved draft token
  comparison before state/event mutation; queue replies always complete.
- **Risk:** restoring malformed/raw numeric input. **Control:** revalidate restored text and discard
  the draft when its completed set is absent from the active Room snapshot.
- No unresolved product blocker. Failure copy is frozen as: `"Подход уже недоступен для правки"`.
- Rollback is code-only: no schema/data migration exists. Removing the feature leaves already
  corrected numeric values intact; no destructive recovery path is introduced.

## Gate P self-check

Pass: AC-001…AC-006 each map to at least one task and automated verification; production ownership
has one writer and no overlapping files; the queue/repository/DAO, primitive SavedState, and UI-token
contracts are frozen before implementation; relevant strict concurrency, real-Room, Compose, version,
independent verification, and final project gates are listed.
