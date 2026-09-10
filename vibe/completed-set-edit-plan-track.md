# Completed-set edit — implementation tracker

## Task status

| Task | Status | Owner | Dependencies | AC | Automated check / evidence |
|---|---|---|---|---|---|
| T-001 | done | implementation writer | — | AC-002, AC-004, AC-005 | Typed request/reply queue, actual-type guarded numeric SQL and real-Room regressions pass. |
| T-002 | done | implementation writer | T-001 | AC-003, AC-004, AC-005 | Primitive SavedState draft, restore validation and stale-token handling pass in `ActiveWorkoutViewModelTest`. |
| T-003 | done | implementation writer | T-002 | AC-001, AC-006 | Completed pill exposes edit semantics; type-specific Material dialog and explicit uncomplete path are wired through immutable state. |
| T-004 | done | implementation writer | T-001–T-003 | AC-001–AC-006 | Version bumped once from 30 / 1.3.22 to 31 / 1.3.23; targeted compile and formatting gates pass. |
| T-005 | done | independent tester + readonly Sol/high reviewer | T-004 | AC-001–AC-006 | targeted tester gate + read-only strict review; implementation writer runs affected recheck after one consolidated fix — not run |
| T-006 | done | root session | T-005 | AC-001–AC-006 | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` — not run |

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

- The new `WorkoutDao` methods require neutral `0` overrides in eight pre-existing handwritten
  test fakes outside the stage's narrow test list. They only preserve compilation and do not alter
  their scenarios.
- A direct Robolectric Compose fixture for the new `AlertDialog` never reached idle, so it was not
  retained as a false failing test. `ActiveWorkoutScreenTest` verifies the completed-pill edit
  semantics; independent review should exercise the dialog's type fields, validation and large-font
  layout through its accessibility tree.

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

- T-001–T-003 targeted gate: `JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home" ./gradlew --no-daemon :app:testDebugUnitTest --tests "*WorkoutSetMutatorTest" --tests "*ActiveWorkoutRepositoryTest" --tests "*WorkoutDaoTest" --tests "*ActiveWorkoutViewModelTest" --tests "*ActiveWorkoutScreenTest" --console=plain` — **passed** (81 tests, 17s).
- T-004 compile gate: `JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home" ./gradlew --no-daemon :app:compileDebugKotlin --console=plain` — **passed** (13s).
- Formatting: `JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home" ./gradlew --no-daemon spotlessCheck --console=plain` — **passed** (4s).
- Consolidated-fix targeted recheck: `JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home" ./gradlew --no-daemon :app:testDebugUnitTest --tests "*WorkoutSetMutatorTest" --tests "*ActiveWorkoutRepositoryTest" --tests "*WorkoutDaoTest" --tests "*ActiveWorkoutViewModelTest" --tests "*ActiveWorkoutScreenTest" --console=plain` — **passed** (88 tests, 32s). It covers dismiss/reopen epoch isolation, finite-number rejection, cancelled callers, timed/cardio positive guards, and the extracted type-specific edit-fields seam.
- Consolidated-fix formatting: `JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home" ./gradlew --no-daemon spotlessCheck --console=plain` — **passed** (3s); `git diff --check` passed.

## Residual risks

- A process death drops the in-memory queue by established design; Room remains authoritative and
  primitive restored draft text is revalidated only after the first loaded active snapshot.
- Final UI review must exercise font scale 2.0 and adaptive widths if Compose semantics alone cannot
  demonstrate clipping; do not create or inspect screenshots without explicit user request.
- The dialog rendering path needs independent accessibility-tree verification because the direct
  Robolectric `AlertDialog` fixture did not become idle. Its extracted fields are covered directly;
  independent review should smoke the dialog host through the accessibility tree without screenshots.

## Independent verification and root decision

Strict affected recheck Gate V: PASS, stale-dialog epoch and non-finite input findings closed.
Independent Gate T recheck: PASS; 88 targeted tests accepted without redundant rerun.
Root accepts residual P2 for the thin Material AlertDialog popup wrapper: actual popup lifecycle,
action-button semantics at fontScale2/adaptive widths are not dynamically covered. Extracted vertical,
scrollable type fields and all domain/VM persistence/cancellation behavior are covered; static dialog
wiring was reviewed. This is a recorded limit, not a claimed screenshot/device verification.
Current installed emulator v27 and previous output APK v30 did not validate this new UI.
No P0/P1 remains. Full root test/debug gates follow on the stable diff.

## Root final checks

Full `:app:testDebugUnitTest` with temporary forkEvery=16 init script: PASS, 1002 tests, 0 failures/errors, 1 skipped, 1m20s. No tests excluded. `:app:assembleDebug`: PASS, 11s. Logs `/private/tmp/yarumo-completed-edit-final-tests.log` and `/private/tmp/yarumo-completed-edit-debug.log`. Version31/1.3.23; strict GateV and independentGateT PASS, wrapper P2 explicitly accepted above.
