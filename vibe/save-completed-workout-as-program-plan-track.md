# Трекер: сохранить завершённую тренировку как программу

**Slug:** `save-completed-workout-as-program`  
**Статус:** post-review remediation complete; Gate V and targeted tests passed, debug assembly passed; full unit gate is red only in unrelated parallel `MuscleSelectorComposeTest` changes.

| Task | Status | Owner | Depends on | Done evidence |
|---|---|---|---|---|
| T-001 Domain persistence seam and mapping | complete | implementation writer | — | `*SaveCompletedWorkoutAsRoutineUseCaseTest` covers mapping, `gymIds = emptySet()`, typed outcomes, cancellation, success-only scheduling and scheduler exception after durable write |
| T-002 Summary integration | complete | implementation writer | T-001 | `*WorkoutSummaryViewModelTest` covers eligibility including zero completed sets, prefill, error/cancel, recreation, one-shot acknowledgement and concurrent-confirm admission |
| T-003 History-detail integration and UI accessibility regression | complete | implementation writer | T-001 | `*WorkoutDetailViewModelTest` and `*SaveWorkoutAsProgramDialogTest` cover zero-set eligibility, recreation, concurrent confirm, compact/fontScale dialog semantics and disabled progress |
| T-004 Release bookkeeping and integrated validation | complete | implementation writer + final owner | T-001–T-003 | version 20 / 1.3.12; full unit suite and debug assembly pass |
| T-005 Durable operation idempotency at the repository boundary | complete | implementation writer | T-001 | `GymRepositoryImplTest` verifies sync-id create replay has no rewrite, a distinct key creates another routine, and nonzero editor drafts still replace routine/exercises/gyms; use-case test verifies supplied operation id and replay scheduling; six `RoutineDao` fakes implement the lookup |
| T-006 Restore the same operation in both destinations | complete | implementation writer | T-005 | `WorkoutSummaryViewModelTest` and `WorkoutDetailViewModelTest` reproduce commit-before-cleanup, recreate with the same handle and one durable routine, then cover orphan cleanup and a fresh UUID |
| T-007 Correct secondary save affordance and blank confirmation semantics | complete | implementation writer | — | `SaveWorkoutAsProgramDialogTest` covers empty/whitespace/valid/saving semantics; `WorkoutSummaryScreenTest` verifies the secondary save target and full-width Done primary at fontScale 2.0 |
| T-008 Revalidation and release bookkeeping | partial | implementation writer + final owner | T-005–T-007 | Gate V, targeted tests and `assembleDebug` pass; full suite reaches 843 tests but two unrelated parallel `MuscleSelectorComposeTest` assertions fail; this feature did not change its version |

## AC → task → test traceability

| AC | Tasks | Automated evidence |
|---|---|---|
| AC-001 | T-002, T-003 | `WorkoutSummaryViewModelTest`, `WorkoutDetailViewModelTest` eligibility cases |
| AC-002 | T-002, T-003 | VM dialog state tests; Compose semantics test if existing harness supports it |
| AC-003 | T-001, T-002, T-003 | use-case blank/trim/single-write tests; VM rapid-confirm/cancel tests |
| AC-004 | T-001 | mapping/order/all-execution-fields use-case test |
| AC-005 | T-001, T-003 | omitted incomplete rows/source-unchanged use-case + history VM test |
| AC-006 | T-001, T-002, T-003 | fake repository confirms independent `gymIds = emptySet()` even for historical gyms; atomic repository outcomes remain covered |
| AC-007 | T-001, T-005 | fake `RoutineUploadScheduler` receives every `Saved` routine `syncId`; same-token replay re-enqueues the same unique work |
| AC-008 | T-001 | fresh identity/null-rest/duplicate-name test |
| AC-009 | T-002, T-003 | success event + dialog closed/no navigation VM tests |
| AC-010 | T-005, T-006 | Room no-rewrite/idempotency and use-case token tests; `WorkoutSummaryViewModelTest` and `WorkoutDetailViewModelTest` commit-before-cleanup recreation cases |
| AC-011 | T-007 | `SaveWorkoutAsProgramDialogTest` empty/whitespace/valid/saving semantics |
| AC-012 | T-007 | `WorkoutSummaryScreenTest` semantics: one primary `PillButton` plus secondary save `TextButton` (or existing screen-test target if it already hosts the screen) |
| AC-013 | T-005 | Room regression: nonzero-ID editor draft updates routine fields, exercises and gyms while only `id == 0L` create drafts use sync-id replay |
| AC-014 | T-006 | `WorkoutSummaryViewModelTest`, `WorkoutDetailViewModelTest`: restored visible/name without token closes/clears without write or generated UUID |

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
| Post-review planning-only change | not run — production/build files remain untouched |
| `./gradlew :app:testDebugUnitTest --tests "*GymRepositoryImplTest" --tests "*SaveCompletedWorkoutAsRoutineUseCaseTest" --tests "*WorkoutSummaryViewModelTest" --tests "*WorkoutDetailViewModelTest" --tests "*SaveWorkoutAsProgramDialogTest" --tests "*WorkoutSummaryScreenTest"` | passed — `BUILD SUCCESSFUL` in 14s |
| `./gradlew :app:testDebugUnitTest --tests "*WorkoutSummaryScreenTest"` | passed — `BUILD SUCCESSFUL` in 7s after adding fontScale 2.0 coverage |
| `./gradlew :app:testDebugUnitTest` (post-review final gate) | failed — 843 tests completed, 2 unrelated parallel `MuscleSelectorComposeTest` width assertions failed (`expected 143`, `actual 320`); save-as-program tests pass |
| `./gradlew :app:assembleDebug` (post-review final gate) | passed — `BUILD SUCCESSFUL` in 2s |

## Deviations

- Product decision after Gate T/V: new independent routines always use `RoutineConfigurationDraft.gymIds = emptySet()`. Historical gym restrictions are deliberately not copied, so deleted gym ambiguity needs no schema, DAO or migration change. AC-006 is updated accordingly.
- P1 lifecycle fix: `isSavingAsProgram` is no longer written to or restored from `SavedStateHandle`; recreation restores the dialog/name/error as retryable UI with `isSavingAsProgram = false`.
- P1 concurrency fix: confirmation uses VM-local `Mutex.tryLock()` admission and captures workout/name once before launching; tests invoke confirm concurrently for both destinations.
- P1 durable-save fix: scheduler exceptions after repository `Saved` are best-effort only and preserve `Saved`; cancellation still rethrows.

## Findings

- Existing `WorkoutFull` may expose historical gyms, but this independent-save flow intentionally ignores them.
- `GymRepository.saveRoutineConfiguration` already provides atomic persistence and validation; this remediation adds only a DAO lookup method, with no entity/schema/migration change.
- `RoutineUploadScheduler` is already singleton-bound; an `@Inject` use case needs no Hilt module edit.
- Gate T passed after the lifecycle, concurrency, zero-completed-set, scheduler-failure and compact/font-scale regressions were added.
- Gate V recheck found no open P0/P1 findings.
- Post-review finding 1 (valid): the restored dialog persists a name but not an operation identity. A prior Room commit followed by process death before state cleanup can repeat save with a fresh `RoutineEntity.syncId` and create a duplicate.
- Post-review finding 2 (valid): `SaveWorkoutAsProgramDialog` currently enables confirm with `enabled = !isSaving`, so blank and whitespace-only values can submit only to receive avoidable validation error.
- Post-review finding 3 (valid): summary renders both «Сохранить» and «Готово» as full-width `PillButton` primary actions; Save must become a secondary M3 `TextButton`.
- Post-review Gate V passed with no P0/P1/P2 findings. Independent targeted test audit passed; its fontScale 2.0 P2 coverage gap was closed and the focused screen test passed again.
- During this task, unrelated parallel edits appeared in `MuscleSelector.kt`, `MuscleSelectorComposeTest.kt` and `app/build.gradle.kts`. They were preserved. Their two selector assertion failures are the only reason the final full unit gate is red; `assembleDebug` remains green.

## Post-review frozen decisions

- Reuse the existing unique `RoutineEntity.syncId` as a durable operation idempotency key for create drafts only (`routine.id == 0L`). Store the UUID in each destination `SavedStateHandle` during the dialog lifetime; do not add schema, migration, destructive fallback, dependencies, Hilt bindings, navigation, or a new persistent table.
- In `GymRepositoryImpl.saveRoutineConfiguration`'s transaction, `RoutineDao.getRoutineBySyncId(syncId)` runs before writes only for `id == 0L` create drafts. A match returns the existing routine without mutation; nonzero-ID editor drafts retain validation/upsert/replace for routine, exercises and gyms. The use case maps either create result to UI success and schedules that routine's `syncId`; the existing scheduler's unique/idempotent work closes the commit-before-enqueue gap.
- `Saved`, cancel/dismiss and ineligible source clear the operation key; a later explicitly opened dialog gets a fresh UUID. A restored visible/name draft without a token is atomically closed/cleared, never assigned a fresh token. `isSaving` remains non-restored.
- Confirm is enabled exactly for `name.isNotBlank() && !isSaving`; summary retains «Готово» as the sole primary action and exposes Save as an accessible secondary M3 `TextButton`.
- Version remains `20 / 1.3.12`: it was already raised once by this feature from main `19 / 1.3.11`; no second bump for review remediation.

## Residual risks

- A scheduler enqueue failure after a successful local transaction leaves the routine durable and the UI reports success; the established upload-all recovery can enqueue it later.
- Dialog semantics are Robolectric-covered at compact width and fontScale 2.0 without screenshots. Actual summary/detail entry action semantics remain a manual accessibility-tree follow-up.
- The scheduler `CancellationException` branch is implemented but has no dedicated scheduler-side regression; repository cancellation propagation is covered.
- A non-teardown cancellation delivered to a still-live ViewModel can leave its transient dialog in the disabled saving state. Normal cancellation clears the ViewModel, and recreation explicitly resets that state; this defensive edge is accepted as residual P2.
- The full unit gate remains red until the unrelated parallel `MuscleSelectorComposeTest` width expectations and implementation are reconciled; these files are outside this remediation's ownership.
