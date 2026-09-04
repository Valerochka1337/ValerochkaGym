# Трекер: сохранить завершённую тренировку как программу

**Slug:** `save-completed-workout-as-program`  
**Статус:** complete; Gate T passed, Gate V has no P0/P1, final project gates passed.

| Task | Status | Owner | Depends on | Done evidence |
|---|---|---|---|---|
| T-001 Domain persistence seam and mapping | complete | implementation writer | — | `*SaveCompletedWorkoutAsRoutineUseCaseTest` covers mapping, `gymIds = emptySet()`, typed outcomes, cancellation, success-only scheduling and scheduler exception after durable write |
| T-002 Summary integration | complete | implementation writer | T-001 | `*WorkoutSummaryViewModelTest` covers eligibility including zero completed sets, prefill, error/cancel, recreation, one-shot acknowledgement and concurrent-confirm admission |
| T-003 History-detail integration and UI accessibility regression | complete | implementation writer | T-001 | `*WorkoutDetailViewModelTest` and `*SaveWorkoutAsProgramDialogTest` cover zero-set eligibility, recreation, concurrent confirm, compact/fontScale dialog semantics and disabled progress |
| T-004 Release bookkeeping and integrated validation | complete | implementation writer + final owner | T-001–T-003 | version 20 / 1.3.12; full unit suite and debug assembly pass |

## AC → task → test traceability

| AC | Tasks | Automated evidence |
|---|---|---|
| AC-001 | T-002, T-003 | `WorkoutSummaryViewModelTest`, `WorkoutDetailViewModelTest` eligibility cases |
| AC-002 | T-002, T-003 | VM dialog state tests; Compose semantics test if existing harness supports it |
| AC-003 | T-001, T-002, T-003 | use-case blank/trim/single-write tests; VM rapid-confirm/cancel tests |
| AC-004 | T-001 | mapping/order/all-execution-fields use-case test |
| AC-005 | T-001, T-003 | omitted incomplete rows/source-unchanged use-case + history VM test |
| AC-006 | T-001, T-002, T-003 | fake repository confirms independent `gymIds = emptySet()` even for historical gyms; atomic repository outcomes remain covered |
| AC-007 | T-001 | fake `RoutineUploadScheduler` success-only/once test |
| AC-008 | T-001 | fresh identity/null-rest/duplicate-name test |
| AC-009 | T-002, T-003 | success event + dialog closed/no navigation VM tests |

## Commands and results

| Command | Result |
|---|---|
| Planning-only change: no Gradle command per AGENTS.md | not run — no application/build change |
| `./gradlew :app:testDebugUnitTest --tests "*SaveCompletedWorkoutAsRoutineUseCaseTest"` | passed |
| `./gradlew :app:testDebugUnitTest --tests "*WorkoutSummaryViewModelTest"` | passed |
| `./gradlew :app:testDebugUnitTest --tests "*WorkoutDetailViewModelTest" --tests "*SaveWorkoutAsProgramDialogTest"` | passed |
| `./gradlew :app:testDebugUnitTest --tests "*WorkoutSummaryViewModelTest" --tests "*SaveWorkoutAsProgramDialogTest"` | passed after rapid-confirm regression test |
| `./gradlew :app:testDebugUnitTest --tests "*SaveCompletedWorkoutAsRoutineUseCaseTest" --tests "*WorkoutSummaryViewModelTest" --tests "*WorkoutDetailViewModelTest" --tests "*SaveWorkoutAsProgramDialogTest"` | passed — consolidated P1/P2 fix-pass |
| `./gradlew :app:testDebugUnitTest` | passed — `BUILD SUCCESSFUL` in 17s |
| `./gradlew :app:assembleDebug` | passed — `BUILD SUCCESSFUL` in 9s |

## Deviations

- Product decision after Gate T/V: new independent routines always use `RoutineConfigurationDraft.gymIds = emptySet()`. Historical gym restrictions are deliberately not copied, so deleted gym ambiguity needs no schema, DAO or migration change. AC-006 is updated accordingly.
- P1 lifecycle fix: `isSavingAsProgram` is no longer written to or restored from `SavedStateHandle`; recreation restores the dialog/name/error as retryable UI with `isSavingAsProgram = false`.
- P1 concurrency fix: confirmation uses VM-local `Mutex.tryLock()` admission and captures workout/name once before launching; tests invoke confirm concurrently for both destinations.
- P1 durable-save fix: scheduler exceptions after repository `Saved` are best-effort only and preserve `Saved`; cancellation still rethrows.

## Findings

- Existing `WorkoutFull` may expose historical gyms, but this independent-save flow intentionally ignores them.
- `GymRepository.saveRoutineConfiguration` already provides atomic persistence and validation; no DAO/schema/migration addition is planned.
- `RoutineUploadScheduler` is already singleton-bound; an `@Inject` use case needs no Hilt module edit.
- Gate T passed after the lifecycle, concurrency, zero-completed-set, scheduler-failure and compact/font-scale regressions were added.
- Gate V recheck found no open P0/P1 findings.

## Residual risks

- A scheduler enqueue failure after a successful local transaction leaves the routine durable and the UI reports success; the established upload-all recovery can enqueue it later.
- Dialog semantics are Robolectric-covered at compact width and fontScale 2.0 without screenshots. Actual summary/detail entry action semantics remain a manual accessibility-tree follow-up.
- The scheduler `CancellationException` branch is implemented but has no dedicated scheduler-side regression; repository cancellation propagation is covered.
- A non-teardown cancellation delivered to a still-live ViewModel can leave its transient dialog in the disabled saving state. Normal cancellation clears the ViewModel, and recreation explicitly resets that state; this defensive edge is accepted as residual P2.
