# Completed-set edit — implementation tracker

## Task status

| Task | Status | Owner | Dependencies | AC | Automated check / evidence |
|---|---|---|---|---|---|
| T-001 | pending | implementation writer | — | AC-002, AC-004, AC-005 | `./gradlew :app:testDebugUnitTest --tests "*WorkoutSetMutatorTest" --tests "*ActiveWorkoutRepositoryTest" --tests "*WorkoutDaoTest"` — not run |
| T-002 | pending | implementation writer | T-001 | AC-003, AC-004, AC-005 | `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkoutViewModelTest"` — not run |
| T-003 | pending | implementation writer | T-002 | AC-001, AC-006 | `./gradlew :app:testDebugUnitTest --tests "*ActiveWorkoutScreenTest"` — not run |
| T-004 | pending | implementation writer | T-001–T-003 | AC-001–AC-006 | target version check; `./gradlew :app:compileDebugKotlin` — not run |
| T-005 | pending | independent tester + readonly Sol/high reviewer | T-004 | AC-001–AC-006 | targeted tester gate + read-only strict review; implementation writer runs affected recheck after one consolidated fix — not run |
| T-006 | pending | root session | T-005 | AC-001–AC-006 | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` — not run |

## AC → task → test traceability

| AC | Task(s) | Required evidence |
|---|---|---|
| AC-001 | T-003 | Compose tests: completed strength/timed/cardio pill opens prefilled draft; Cancel does not invoke save; uncomplete stays a distinct action. |
| AC-002 | T-001 | Real-Room tests: only type-valid columns change, while completion/timestamp/identity/index and concurrent unrelated numeric values survive. |
| AC-003 | T-002 | ViewModel test: successful completed-number edit neither invokes complete path nor changes focused set/rest state. |
| AC-004 | T-001, T-002 | Real-Room missing/inactive/uncompleted/wrong-type affected-row outcome; ViewModel retains retry draft and emits `Подход уже недоступен для правки`. |
| AC-005 | T-001, T-002 | Mutator test: missing/cancelled caller cannot stall next request; same-SavedState recreation preserves raw input/retry and token test isolates obsolete result. |
| AC-006 | T-003 | Compose semantics/type-field/validation tests; implementation review covers 48dp, Material tokens, font scale and width behavior. |

## Deviations

None. The implementation must record any contract or file-scope change here before proceeding.

## Findings

- Existing `WorkoutSetMutator.edit` serializes transformations but has no result channel.
- Existing `WorkoutDao.updateSet` is unguarded and can overwrite a row after workout finish; the
  entity does not include exercise type, so the new per-type guard must join to the actual DB type.
- `CompletedSetPill` currently directly uncompletes; the new edit entry point replaces that tap while
  retaining explicit uncomplete in the dialog.

## Command results

Strict Gate P recheck (Sol/high), 10.09.2026: PASS. Four P1 findings closed:
atomic completed/active/type guards, primitive-only restored draft with idle submission,
independent verification/root final gates, and one version bump. No remaining P0/P1/P2.
This is plan review only; application implementation and test gates remain pending.

No commands run: this change creates planning artifacts only, and repository policy forbids Gradle
for documentation/plan-only edits.

## Residual risks

- A process death drops the in-memory queue by established design; Room remains authoritative and
  primitive restored draft text is revalidated only after the first loaded active snapshot.
- Final UI review must exercise font scale 2.0 and adaptive widths if Compose semantics alone cannot
  demonstrate clipping; do not create or inspect screenshots without explicit user request.
