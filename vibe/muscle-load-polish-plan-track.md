# Muscle load polish — plan tracker

Status key: `pending | in_progress | done | blocked`.

## Task status

| Task | Status | Owner | Dependencies | Done evidence |
|---|---|---|---|---|
| T-013 | done | Implementation writer | — | `BodyMapHitTest` passes: neck/tibialis paths map on-figure and LATS remains mapped on back. |
| T-014 | done | Implementation writer | T-013 | Direction-aware bounded viewport, velocity-aware settle, 48dp controls and Compose semantics/de-dup tests pass. |
| T-015 | done | Implementation writer | T-013 | Distinct neutral inactive fill, role-fill helper and read-only map semantics tests pass. |
| T-016 | done | Implementation writer | T-015 | Built-in open/save/AI-existing guards plus browse/picker click-contract and custom edit regression tests pass. |
| T-017 | done | Implementation writer | T-013–T-016 | Version is 17/1.3.9; final consolidated targeted gate passes. |
| T-018 | done | Root | T-017 | Full unit suite and debug assembly both pass on the final stable diff. |

## AC → task → test traceability

| AC | Task | Verification |
|---|---|---|
| 025 | T-013 | `BodyMapHitTest` |
| 026 | T-014 | `BodyMapHitTest`, `MuscleSelectorTest` |
| 027 | T-014 | `MuscleSelectorTest` Compose semantics/state tests |
| 028 | T-015 | `ExerciseDetailViewModelTest`, `ExerciseDetailScreenTest` |
| 029 | T-016 | `ExerciseDetailViewModelTest`, `ExerciseLibraryViewModelTest`, `ExerciseLibraryScreenTest` |
| 030 | T-017,T-018 | source assertion; full unit suite; debug assembly |

## Deviations

None. Record any implementation choice that changes a frozen contract here before proceeding.

## Findings

- Planning-only documentation change: Gradle was not run, per `AGENTS.md`.
- Existing vendored source exposes exact `neck` and `tibialis` paths, mapped to `Muscle.NECK` and
  `Muscle.TIBIALIS_ANTERIOR`; neither requires new
  geometry or a new `Muscle` enum value.
- Planning baseline: selector was an unbounded virtual `LazyRow` with a duplicated header and
  full-list fallback; detail painted every involved muscle uniformly; built-in paths allowed
  personalize/clone behavior.
- Gate T/V follow-up resolved: selector now retains only an ephemeral target/display identity,
  reconciles external selection without replaying haptics, and moves its whole three-choice viewport
  with direction-aware `GymMotion.spatialFast`; browse-mode built-in rows expose no click action.
- Independent Gate T and Gate V rechecks report no remaining P0/P1 findings. The accepted P2 is
  limited to animation/frame and 200% font-scale test depth; production semantics and targets are covered.

## Command results

| Command | Result | Notes |
|---|---|---|
| Planning inspection only | not run | Documentation-only work; no Gradle permitted without explicit request. |
| `./gradlew :app:testDebugUnitTest --tests '*BodyMapHitTest'` | pass | T-013; after correcting an initial selector compilation issue. |
| `./gradlew :app:testDebugUnitTest --tests '*BodyMapHitTest' --tests '*MuscleSelectorTest'` | pass | T-014. |
| `./gradlew :app:testDebugUnitTest --tests '*ExerciseDetailViewModelTest' --tests '*ExerciseDetailScreenTest'` | pass | T-015. |
| `./gradlew :app:testDebugUnitTest --tests '*ExerciseDetailViewModelTest' --tests '*ExerciseLibraryViewModelTest' --tests '*ExerciseLibraryScreenTest'` | pass | T-016; one stale personalization assertion was updated to the frozen non-cloning contract. |
| `./gradlew :app:testDebugUnitTest --tests '*BodyMapHitTest' --tests '*MuscleSelectorTest' --tests '*ExerciseDetailViewModelTest' --tests '*ExerciseDetailScreenTest' --tests '*ExerciseLibraryViewModelTest' --tests '*ExerciseLibraryScreenTest'` | pass | T-017 consolidated targeted gate. |
| `./gradlew :app:testDebugUnitTest --tests '*MuscleSelectorTest' --tests '*MuscleSelectorComposeTest' --tests '*ExerciseDetailScreenTest' --tests '*ExerciseDetailScreenComposeTest' --tests '*ExerciseLibraryScreenTest'` | pass | Gate T/V focused Compose regression pass. |
| `./gradlew :app:testDebugUnitTest --tests '*BodyMapHitTest' --tests '*MuscleSelectorTest' --tests '*MuscleSelectorComposeTest' --tests '*ExerciseDetailViewModelTest' --tests '*ExerciseDetailScreenTest' --tests '*ExerciseDetailScreenComposeTest' --tests '*ExerciseLibraryViewModelTest' --tests '*ExerciseLibraryScreenTest'` | pass | Gate T/V consolidated targeted gate. |
| `./gradlew :app:testDebugUnitTest` | pass | T-018 final gate: BUILD SUCCESSFUL in 16s. The first sandboxed launch could not create the Gradle wrapper lock under `~/.gradle`; the approved rerun completed normally. |
| `./gradlew :app:assembleDebug` | pass | T-018 final gate: BUILD SUCCESSFUL in 8s. |

## Residual risks

- The focused tests do not inspect animation frames, perform a real Compose fling, or assert the
  selector layout at `fontScale = 2.0`; state/gesture logic, enabled semantics and 48dp production
  targets are covered. No screenshots were generated or inspected, per project instruction.
- Role-fill alpha is supplementary only; the existing text list/semantics remains authoritative.

---

## Follow-up tracker: shared sectors and selector text polish (AC-031+)

### Task status

| Task | Status | Owner | Dependencies | Done evidence |
|---|---|---|---|---|
| T-019 | done | Implementation writer | T-013 | Sector-first map covers all 25 logical muscles; `offFigureMuscles` is empty; sector-path uniqueness, preferred-side, deterministic shared tap and real-region hit tests pass. |
| T-020 | done | Implementation writer | T-019 | Shared selector uses equal text slots and two decorative dividers with no arrows/chips; neighbours retain 48dp click targets, current is read-only, root actions/swipe/haptics and 2.0 font-scale semantics pass. |
| T-021 | done | Implementation writer | T-019,T-020 | Heatmap maximum and detail/editor strongest-role sector projections pass; editor off-figure assist-chip fallback is removed. |
| T-022 | done | Implementation writer | T-019–T-021 | Logical lower-chest restoration and InBody regressions pass; analysis/detail/picker/lower-chest/InBody PNGs were generated and explicitly inspected. |
| T-023 | done | Root | T-019–T-022 | Consolidated targeted gate, final unit suite and debug assembly are green; final PNG inspection passed; version remains the single 17 / 1.3.9 increment. |

### AC → task → test traceability

| AC | Task | Verification |
|---|---|---|
| 031 | T-020,T-021 | `MuscleSelectorTest`, `MuscleSelectorComposeTest`, `MuscleHeatmapProjectionTest`, detail/editor Compose host |
| 032 | T-020,T-021 | selector pure/Compose `fontScale = 2.0`/semantics; consumer projection tests |
| 033 | T-019,T-022 | `BodyMapHitTest`, `MuscleSectorProjectionTest`, `AnalysisRenderTest` |
| 034 | T-019,T-021,T-022 | body hit/sector, heatmap/detail/editor projection, Compose semantics and render tests |
| 035 | T-022,T-023 | `AnalysisViewModelTest`, `InBodySegmentHeatmapTest`, `AnalysisRenderTest`, full unit suite, debug assembly |

### Follow-up deviations

None. The reviewer-corrected sector memberships are the frozen mappings: `SERRATUS_ANTERIOR`
uses front `obliques`, `HIP_FLEXORS` uses front `quadriceps`, and `SIDE_DELTS` uses both views'
`deltoids`; these clarify the accepted sector contract without changing persistence or SSOT.

### Follow-up findings

- Planning inspection only: current `BodyMuscleMapping` is one-muscle-per-geometry and retains
  seven off-figure logical muscles; `BodyMap` consequently parses/draws by muscle rather than
  sector.
- Current shared selector still has arrow `IconButton`s, weighted (not equal) `FilterChip` slots,
  `maxLines = 1`, and a clickable current slot. `ExerciseEditorSheet` retains the off-figure
  `AssistChip` fallback. These are the direct follow-up change points.
- The user authorized screenshot generation and inspection, superseding the prior no-screenshot
  residual risk for this follow-up only. Gradle was not run while updating this plan/tracker.
- P1 follow-up resolved: unselected shared sectors retain max/strongest projection, while the
  selected logical member now supplies the exact inner-outline colour. Direct lower-chest tests
  cover the weaker member against a hotter/stronger upper chest. Tibialis SVG paths now classify
  as a left/right leg for InBody.

### Follow-up command results

| Command | Result | Notes |
|---|---|---|
| Planning inspection only | not run | Documentation-only planning work; user instructed this planner not to run Gradle. |
| `./gradlew :app:testDebugUnitTest --tests '*BodyMapHitTest' --tests '*MuscleSectorProjectionTest' --tests '*MuscleSelectorTest' --tests '*MuscleSelectorComposeTest' --tests '*MuscleHeatmapProjectionTest' --tests '*ExerciseDetailScreenTest' --tests '*ExerciseDetailScreenComposeTest' --tests '*ExerciseEditorSheetTest' --tests '*AnalysisViewModelTest' --tests '*InBodySegmentHeatmapTest' --tests '*AnalysisRenderTest'` | pass | T-019–T-022 focused gate: BUILD SUCCESSFUL in 12s. Initial sandboxed launch could not create Gradle's wrapper lock under `~/.gradle`; approved reruns were used. |
| Render snapshot inspection | pass | Explicitly inspected `analysis-body-lower-chest.png`, `analysis-cards.png`, `exercise-detail.png`, `exercise-muscle-picker.png`, and `inbody-segment-maps.png`: shared chest sector is single/outlined, no duplicate-path artefact or clipping observed; text slots/dividers and role/value centring are visible in analysis card. |
| `./gradlew :app:testDebugUnitTest --tests '*AnalysisRenderTest' --tests '*MuscleHeatmapProjectionTest' --tests '*ExerciseDetailScreenTest' --tests '*ExerciseEditorSheetTest' --tests '*InBodySegmentHeatmapTest' --tests '*MuscleSelectorComposeTest' --tests '*MuscleSectorProjectionTest' && git diff --check` | pass | P1 correction: BUILD SUCCESSFUL in 11s; direct sector membership, exact selected-member outline colour, tibialis path→leg and selector equal-width/48dp/font-scale checks pass. |
| P1 render snapshot inspection | pass | Re-inspected `analysis-body-lower-chest.png`, `analysis-cards.png`, `exercise-muscle-picker.png`, and `inbody-segment-maps.png`: selected lower chest uses its red inner outline while the shared fill remains orange; no clipping or duplicate-path artefact. |
| `./gradlew :app:testDebugUnitTest --tests '*BodyMapHitTest' --tests '*MuscleSectorProjectionTest' --tests '*MuscleSelectorTest' --tests '*MuscleSelectorComposeTest' --tests '*MuscleHeatmapProjectionTest' --tests '*ExerciseDetailScreenTest' --tests '*ExerciseDetailScreenComposeTest' --tests '*ExerciseEditorSheetTest' --tests '*AnalysisViewModelTest' --tests '*InBodySegmentHeatmapTest' --tests '*AnalysisRenderTest'` | pass | T-023 consolidated targeted gate: BUILD SUCCESSFUL in 11s. The initial sandboxed launch could not create the Gradle wrapper lock under `~/.gradle`; the approved rerun completed normally. |
| `./gradlew :app:testDebugUnitTest` | pass | T-023 final unit gate: BUILD SUCCESSFUL in 15s. |
| `./gradlew :app:assembleDebug` | pass | T-023 final build gate: BUILD SUCCESSFUL in 8s. |
| Final render snapshot inspection | pass | Root opened the regenerated lower-chest, analysis-card, exercise-detail, exercise-picker and InBody PNGs: full three-slot labels/dividers and centred value remain readable; selected lower chest has its exact red outline over the orange shared-sector fill; body/detail and tibialis leg rendering are intact without clipping or duplicate paths. |

### Follow-up residual risks

- Focused Compose semantics use a `fontScale = 2.0` host; a real-device TalkBack traversal and
  animation-frame/fling timing remain final manual smoke-test risks. Automated tests cover the
  logical member actions, deterministic settlement and no duplicate haptics.
