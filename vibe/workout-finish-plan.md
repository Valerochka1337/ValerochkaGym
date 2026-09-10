# Plan: workout-finish (#47, stage 04)

## Goal

Make the existing active-workout finish flow visible from the header and, once every set is complete,
from the bottom of the screen, while ensuring one confirmation runs one finish/upload/navigation flow.

## Scope

- Add a labelled header finish action outside overflow and a conditional bottom finish button.
- Route both through the existing mandatory `ConfirmDialog` and one ViewModel single-flight.
- Preserve existing repository `finish`, upload scheduling, summary navigation, and #44 completed-set
  editing behavior.
- Add focused ViewModel and Compose tests and the required #47 version bump.

## Non-goals

- Changing the finish transaction, its pruning rules, upload worker, navigation graph, schema,
  migration, Hilt, permission, timer/service, or dependency set.
- Adding a finish control when there are no sets or changing Stage 03 edit/guard contracts.

## Assumptions

- `allCompleted` means the current Room snapshot has at least one set and every set has
  `isCompleted == true`; no mutable completion flag is stored.
- A local `repository.finish` failure leaves the active workout and confirmation flow retryable.
- Once local finish succeeds, the workout is terminal even if `uploadScheduler.schedule` throws:
  report `"Тренировка завершена, не удалось поставить выгрузку в очередь"`, navigate to the
  existing summary once, and never call repository finish/upload/navigation again for that attempt.

## Acceptance criteria

| ID | Criterion |
|---|---|
| AC-001 | Header exposes a direct, labelled `Завершить тренировку` action with a 48dp target; overflow is not required. |
| AC-002 | A bottom finish button appears only for a nonempty current set collection with all sets completed, and Room changes immediately hide/show it. |
| AC-003 | Both entry points open the same mandatory existing confirmation; dismissal/cancel changes neither workout, rest timer, nor navigation. |
| AC-004 | Repeated confirmations run at most one local finish, upload schedule, and summary navigation. Local finish failure remains retryable; after local success an upload-scheduling failure is surfaced and still produces exactly one summary navigation without redoing history writes. |

## Current → target flow

**Current:** header overflow → local `showFinishDialog` → `ActiveWorkoutViewModel.finish` →
`repository.finish` → `uploadScheduler.schedule` → buffered summary event. Repository finish is
transactional/idempotent, but ViewModel calls are not single-flight. No direct header action or
all-completed bottom action exists.

**Target:** direct header action or derived bottom action → the same saveable confirmation dialog →
`finish()` guarded by ViewModel in-flight state → existing transactional repository finish → existing
upload scheduler → one summary event. Room remains SSOT for active workout and the derived bottom
predicate. During flight, both initiators and confirm are disabled; cancellation before confirmation
is a no-op. A repository failure clears in-flight and shows retryable error. A scheduler exception
after successful persistence emits the frozen message then exactly one summary navigation; it does
not reopen/retry finish.

## Frozen contracts and decisions

- `ActiveWorkoutUiState` gets immutable `isFinishing`; ViewModel owns an atomic backing state for
  the current instance, rather than UI-local flags. `finish()` first returns for no active workout,
  then uses atomic `compareAndSet(false, true)` before launching. It never uses `Job.isActive` as the
  terminal guard: once persistence commits, true remains true even if the coroutine completes while
  Room has not yet emitted null. It resets to false only for a pre-commit `repository.finish`
  failure. The Job is owned by `viewModelScope`; `CancellationException` is rethrown.
- The existing repository finish transaction and `UploadScheduler` API remain unchanged. Finish,
  scheduler, and navigation are deliberately one vertical single-flight in the ViewModel: no second
  call enters after a confirmed local success, including when scheduling throws.
- Failure semantics are frozen: repository failure sends `"Не удалось завершить тренировку"`, keeps
  the active screen retryable, and sends no upload/navigation. Scheduler failure after successful
  repository finish sends `"Тренировка завершена, не удалось поставить выгрузку в очередь"` then
  exactly one `NavigateToSummary`; it does not attempt another local finish or scheduler call.
- `ActiveWorkoutContent` derives `allCompleted` from `workout.exercises.flatMap { it.sets }` during
  composition. It shows the extra bottom M3 button only for nonempty/all-complete data. Both actions
  set the same `rememberSaveable` dialog flag; the dialog remains mandatory and uses the existing
  text. Confirm/dismiss and entry actions honour `isFinishing`.
- Header uses an existing rounded check/finish icon with `contentDescription = "Завершить тренировку"`,
  Material colors/shapes, `gymHaptics().tap()` when opening and `.success()` only on confirmed tap.
  The bottom is a normal full-width M3 action with a 48dp target. No route/state-restoration change:
  dialog saveability remains local and back first dismisses it.
- No Room schema/migration, dispatcher, Hilt scope/binding, WorkManager/service, permission,
  navigation, or adaptive-shell change is needed. No code in Stage 03 queue/edit contracts is
  modified; integrate its already accepted changes without rewriting them.

## Tasks

| ID | Exact files | Owner | Depends on | Actions | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|---|
| T-001 | `app/src/main/java/com/valerochka1337/valerochkagym/ui/active/ActiveWorkoutViewModel.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/ActiveWorkoutViewModelTest.kt` | implementation writer | Stage 03 integrated | Add atomic backing in-flight state and a single-flight finish flow around existing repository/scheduler/event calls. Test repeats while persistence is suspended and after the full first chain completes while a fake deliberately retains nonnull active Room state; test repository failure retry, repeated scheduler-throw terminal path, cancellation propagation, no-active no-op, and exactly-once upload/navigation. Use `MainDispatcherRule`, live state collection, and handwritten fakes. | `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkoutViewModelTest"` | Each attempt has one terminal behavior: retryable pre-commit persistence failure or one persisted finish plus one scheduler call and one summary event; late Room null cannot permit a second attempt. | AC-003, AC-004 |
| T-002 | `app/src/main/java/com/valerochka1337/valerochkagym/ui/active/ActiveWorkoutScreen.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/active/ActiveWorkoutScreenTest.kt` | implementation writer | T-001 | Add direct header finish action and derived conditional bottom action; route both to the same existing confirmation and bind in-flight disabling. Verify empty/incomplete/all-complete transitions, both entry points, Cancel, semantics/target, and no action while disabled. Preserve #44 dialog/pill APIs and current rest action layout. | `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkoutScreenTest"` | Header and bottom actions meet visibility/predicate/accessibility requirements and can only request the same confirmation/finish path. | AC-001, AC-002, AC-003, AC-004 |
| T-003 | `app/build.gradle.kts`, `vibe/workout-finish-plan-track.md` | implementation writer | T-001–T-002 | Compare against target branch and integrated #44 bump; make exactly one further #47 `versionCode +1` and patch `versionName +1`. Record targeted evidence and deviations. | `./gradlew :app:compileDebugKotlin` | Version is strictly above target and #44’s already-integrated increment; targeted checks are recorded. | AC-001–AC-004 |
| T-004 | *(no file edits; reports to root)* | independent tester + readonly Sol/high reviewer | T-003 | In parallel, tester audits AC/edge coverage and runs the smallest missing target test; reviewer performs strict read-only diff review of single-flight, failure order, UI semantics, #44 preservation, and version. Writer applies one consolidated fix and reruns affected targeted check; reviewer rechecks fixed findings. | tester: applicable filtered `:app:testDebugUnitTest`; reviewer: read-only diff review | No open P0/P1; tester/reviewer evidence goes to root. | AC-001–AC-004 |
| T-005 | `vibe/workout-finish-plan-track.md` | root session | T-004 | On stable implementation, run final gates sequentially once and record command results, AC evidence, deviations, and residual risks. | `./gradlew :app:testDebugUnitTest` then `./gradlew :app:assembleDebug` | All ACs have evidence and no P0/P1 remains. | AC-001–AC-004 |

## File ownership and execution waves

| Owner | Exclusive responsibility |
|---|---|
| implementation writer | T-001–T-003: ActiveWorkout ViewModel/screen, their tests, #47 version bump, and implementation tracker evidence. |
| independent tester | T-004 targeted audit only; no edits. |
| readonly Sol/high reviewer | T-004 strict review only; no edits. |
| root session | T-005 final gates and final tracker recording. |

One writer owns the shared screen/ViewModel choke points. It preserves integrated #44 edits in those
files and does not touch repository, uploader, navigation, or Stage 03 persistence files.

1. Wave 1: T-001, freeze/verify single-flight failure semantics.
2. Wave 2: T-002 and T-003, then targeted UI/VM checks and compile.
3. Wave 3: T-004 tester and Sol/high reviewer in parallel; one consolidated writer fix/recheck.
4. Wave 4: root executes T-005 final gates once on the stable diff.

## Quality gates

- Always: Room remains SSOT; immutable state down/events up; handwritten fakes; no logs, mocks,
  new dependencies, destructive fallback, or unrequested repository changes.
- Strict concurrency: suspended/repeated finish, cancellation, persistence failure retry, scheduler
  failure-after-success, and exactly-once upload/navigation tests.
- Compose: loading/empty/content remain valid; confirmation cancel/back, touch targets, TalkBack
  labels, non-color-only state, font scale 2.0, compact/medium/expanded layout, and no expensive
  derived work. No screenshots without user request.
- No Room migration, chart, permission, WorkManager/service, DI, navigation-transition, dependency,
  or release/R8 conditional gate applies.
- Root final gate after stable review only: `./gradlew :app:testDebugUnitTest`, then
  `./gradlew :app:assembleDebug`. Plan-only work runs no Gradle command.

## Risks, unresolved questions, rollback

- **Risk:** a second confirmation races an in-progress finish or arrives after its coroutine ended
  before Room emits null. **Control:** atomic terminal `isFinishing`, disabled entry/confirm
  controls, suspended and post-chain/non-null-active fake tests.
- **Risk:** scheduler exception makes users retry and duplicate history/navigation. **Control:**
  frozen terminal-after-persistence semantics and exactly-once tests.
- **Risk:** all-completed stale UI. **Control:** derive solely from latest Room `WorkoutFull`; no
  shadow flag.
- No unresolved product blocker. Rollback is code-only: existing finished history is preserved; no
  data/schema transformation is introduced.

## Gate P self-check

Pass: all four ACs map to concrete tasks and tests; one writer owns every overlapping production
file; single-flight and post-persistence failure semantics are frozen; relevant strict concurrency,
Compose, version, independent review, and root final gates are explicit.
