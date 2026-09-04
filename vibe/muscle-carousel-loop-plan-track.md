# Muscle carousel loop — tracker

Status values: `pending` | `in_progress` | `done` | `blocked`.

## Task status

| Task | Status | Owner | Dependencies | Done evidence |
|---|---|---|---|---|
| T-024 | done | One implementation writer | — | `MuscleSelectorTest` and `MuscleSelectorComposeTest` pass: cyclic mapping/recenter, non-saveable virtual `LazyListState` recreated from logical selection, physical multi-item fling, changed-only callback+haptic, a held-drag external-selection race where the user centre wins, TalkBack actions/state, and 2.0-font wrapped bounds/full-height dividers. The non-drawing sizing row is alpha-zero and semantic-free. |
| T-025 | done | One implementation writer | T-024 | `AnalysisViewModelTest`, `ExerciseEditorSheetTest` and selector Compose tests pass; both consumers retain the `Muscle?` contract and analysis recreation remains logical-only. |
| T-026 | done | One implementation writer | T-024 | `MuscleHeatmapProjectionTest`, detail tests and `AnalysisRenderTest` pass. Authorized inspection of body PNGs confirms black inner outline, retained colour fill and contrast outer stroke; obsolete selected-member colour callback/resolver is removed from all callers. |
| T-027 | done | One implementation writer; root final gates | T-024–T-026 | `git diff --check`, final full unit suite and debug assembly pass; final authorized PNG inspection passes; no dependency/catalog/schema/VM/API/DI/nav/permission/background or additional version change was introduced. |

## AC → task → test traceability

| AC | Task | Automated evidence |
|---|---|---|
| AC-036 | T-024 | `MuscleSelectorTest`; `MuscleSelectorComposeTest` cyclic wrap/recenter |
| AC-037 | T-024 | `MuscleSelectorComposeTest` structure, equal-width/divider/full-name/fontScale tests |
| AC-038 | T-024 | `MuscleSelectorComposeTest` multi-item fling and snapped-centre test |
| AC-039 | T-024 | `MuscleSelectorComposeTest` callback/haptic dedupe, silent external sync and drag-cancel tests |
| AC-040 | T-024 | `MuscleSelectorComposeTest` semantics/custom actions/48dp test |
| AC-041 | T-025 | `AnalysisViewModelTest`; `ExerciseEditorSheetTest`; selector external-selection test |
| AC-042 | T-026 | `MuscleHeatmapProjectionTest`; `AnalysisRenderTest` plus authorized PNG inspection |
| AC-043 | T-025, T-027 | Consumer regression tests; `git diff --check`; root full unit suite + debug assembly |

## Command results

| Command | Status | Result / date |
|---|---|---|
| Planning repository inspection (`git status --short`, selector/BodyMap/test reads) | pass | Existing uncommitted same-feature work detected and preserved; 2026-09-04. |
| `./gradlew :app:testDebugUnitTest --tests '*MuscleSelectorTest' --tests '*MuscleSelectorComposeTest' --tests '*MuscleHeatmapProjectionTest' --tests '*ExerciseEditorSheetTest'` | pass | 2026-09-04: 11 tests pass after selector and outline changes. |
| `./gradlew :app:testDebugUnitTest --tests '*AnalysisViewModelTest' --tests '*ExerciseEditorSheetTest' --tests '*MuscleSelectorComposeTest'` | pass | 2026-09-04: consumer, recreation and external-selection regressions pass. |
| `./gradlew :app:testDebugUnitTest --tests '*MuscleHeatmapProjectionTest' --tests '*AnalysisRenderTest'` | pass | 2026-09-04: BodyMap projection and render checks pass; authorized PNGs inspected. |
| `./gradlew :app:testDebugUnitTest --tests '*MuscleSelectorComposeTest'` | pass | 2026-09-04: physical fast-fling regression passes without timing assertions. |
| `./gradlew :app:testDebugUnitTest --tests '*MuscleSelectorTest' --tests '*MuscleSelectorComposeTest' --tests '*MuscleHeatmapProjectionTest' --tests '*ExerciseDetailScreenTest' --tests '*ExerciseDetailScreenComposeTest' --tests '*AnalysisRenderTest'` | pass | 2026-09-04: Gate T/V selector restoration, interaction deferral, dynamic-height, TalkBack, BodyMap API-removal and render checks pass. `muscle-selector.png`, `analysis-body-back.png` and `analysis-body-lower-chest.png` inspected with authorization. |
| `./gradlew :app:testDebugUnitTest --tests '*MuscleSelectorTest' --tests '*MuscleSelectorComposeTest'` | pass | 2026-09-04: held-pointer race test verifies an external selection arriving mid-drag does not steal the user centre; TalkBack settled state description is asserted. |
| `./gradlew :app:testDebugUnitTest --tests '*AnalysisRenderTest'` | pass | 2026-09-04: regenerated and inspected `muscle-selector.png`; each label is painted once at rest, with no visible sizing-row ghost text. |
| `./gradlew :app:testDebugUnitTest` | pass | 2026-09-04: T-027 final unit gate, BUILD SUCCESSFUL in 17s. |
| `./gradlew :app:assembleDebug` | pass | 2026-09-04: T-027 final build gate, BUILD SUCCESSFUL in 8s. |
| `./gradlew :app:testDebugUnitTest --tests '*MuscleSelectorComposeTest' --tests '*AnalysisRenderTest'` | pass | 2026-09-04: follow-up removes the effective-set value from the analysis wheel, preserves editor role labels, and verifies name-only TalkBack state. |
| `./gradlew :app:testDebugUnitTest --tests '*MuscleSelectorComposeTest'` | pass | 2026-09-04: final name-only and optional editor-role selector scenarios pass, BUILD SUCCESSFUL in 7s. |
| Follow-up authorized render inspection | pass | 2026-09-04: `muscle-selector.png` shows three complete wrapped muscle names with no effective-set row. |
| `./gradlew :app:testDebugUnitTest` | pass | 2026-09-04: follow-up full unit gate, BUILD SUCCESSFUL in 18s. |
| `./gradlew :app:assembleDebug` | pass | 2026-09-04: follow-up debug build gate, BUILD SUCCESSFUL in 3s. |
| Final authorized render inspection | pass | Root inspected `muscle-selector.png`, `analysis-body-lower-chest.png`, and `analysis-body-back.png`: one text layer, complete wrapped name/value, and black selected inner outlines over retained data fills. |
| `git diff --check` | pass | 2026-09-04: no whitespace errors on the final stable diff. |

## Deviations

- The pinned Foundation API exposes `rememberSnapFlingBehavior(LazyListState)`; it is used directly,
  with no new dependency or custom animation spec.
- `BodyMapFlip`/`BodyMap` no longer expose `selectedColorFor`; all callers now use their sector
  fill only, while the selected inner outline is always `colorScheme.scrim`.
- The selector's measurement row is intentionally `alpha(0f)` plus `clearAndSetSemantics { }`:
  it contributes only wrapped height, not painted or accessible duplicate labels.

## Findings

- Existing `MuscleSelector` currently uses `AnimatedContent` plus a manual drag threshold and can settle only one neighbor; it is the replacement boundary.
- Existing consumers already pass only `selected: Muscle?`; `AnalysisViewModel` persists only its `name` in `SavedStateHandle`.
- Existing `BodyMap` draws an outer contrast and inner selected stroke. The new user decision replaces member-fill inner color with `colorScheme.scrim` while retaining fill and outer stroke.
- The working branch contains an already incremented same-feature `17 / 1.3.9`; do not increment again.
- The analysis wheel now omits its effective-set value visually and from `stateDescription`; the shared selector keeps optional role text for the exercise editor.

## Residual risks

- Compose snap test timing/API details could require a compatible Foundation implementation; no dependency is permitted.
- Full names and the role value at 2.0 font scale intentionally wrap and increase selector height;
  the focused bounds and render checks cover this behaviour.
- The held-pointer Compose regression spans two touch-injection calls; it passes with the pinned
  Compose test dispatcher, but portability to another dispatcher remains a low-risk test-only
  concern. Production reconciliation independently defers external input through drag/fling.
