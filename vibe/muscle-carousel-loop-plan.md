# Muscle carousel loop — executable plan

## Goal

Replace the selector's one-step `AnimatedContent` gesture with a genuinely physical, seamless cyclic `LazyRow` wheel: exactly three equal-width, full-name slots (previous/current/next) separated by dividers. A rapid fling may cross multiple muscles. The currently selected BodyMap member receives a black inner outline while retaining its heatmap/role fill and contrast outer stroke.

## Scope, non-goals, assumptions

Scope is AC-036…AC-043. One existing `:app` module and the current `feat/muscle-load-system` uncommitted feature diff are the baseline; preserve it. `selected: Muscle?` remains the UDF and saved-state contract for both existing selector consumers; only logical `Muscle` crosses the composable boundary, never a virtual list index. Version remains `17 / 1.3.9`: it was already incremented by this same uncommitted feature.

Out: Room/domain/repository/ViewModel/API/DI/navigation/permission/worker/background-work changes, new dependency, arrows/chips/blocks/list dialog, persistence of virtual position, and chart-library changes. Gate R is frozen: Material3 carousel is finite; KwikUI Loop is a broad, policy/license-risk dependency. Use Compose Foundation `LazyRow` + snap fling + large virtual cyclic range + idle recentering. This fits three simultaneously visible items and free multi-item flings better than `Pager`.

Assume `LazyRow` exposes its established Foundation snapping APIs in the pinned Compose BOM. If the actual API differs, retain the same dependency-free behavior with its compatible Foundation snap API; do not add a library.

## Acceptance criteria

| AC | Acceptance condition | Task / verification |
|---|---|---|
| AC-036 | Wheel is a real cyclic horizontally scrollable `LazyRow`, not one-step `AnimatedContent`; it wraps seamlessly. | T-024 / pure mapping + Compose wrap/recenter tests |
| AC-037 | Viewport shows exactly previous/current/next as full equal-width names, with two dividers and no arrows/chips/blocks. | T-024 / Compose structure and 2.0-font tests |
| AC-038 | Drag and a fast fling are smooth/physical; a fling can traverse multiple muscles and snaps a settled center. | T-024 / Compose multi-item-fling test |
| AC-039 | Selection remains one logical `Muscle`; callback/haptic fire once for a changed settled user selection, external reconciliation is silent, and drag interrupts programmatic motion. | T-024 / callback+haptic/external-sync/cancellation tests |
| AC-040 | Existing TalkBack previous/next custom actions remain, semantic state identifies the selected muscle/role, and neighbor targets are at least 48dp. | T-024 / semantics and layout tests |
| AC-041 | Both existing selector consumers retain `selected: Muscle?` behavior, including null initial choice and external selection/restoration; no virtual index persists. | T-025 / consumer and state-restoration regression tests |
| AC-042 | Selected BodyMap member has a black inner outline via `colorScheme.scrim`; its heatmap/role fill remains underneath and the outer contrast stroke remains. | T-026 / helper plus render test |
| AC-043 | No new dependency, data/schema/migration, VM/API/DI/nav, permission, background-work, or version bump is introduced. | T-027 / diff/Gradle gate |

## Current → target flow

Current: `selected Muscle? → local target + manual draggable offset → one-step settle → AnimatedContent(previous,current,next) → callback/haptic`; external selection resets local target. Target: `selected Muscle? → derived stable central virtual index → LazyRow(large cyclic indices mapped by floorMod to Muscle) → snap settles center → index maps to one logical Muscle → deduplicated user callback/haptic → parent UDF/SavedStateHandle → selected Muscle?`. A `LaunchedEffect` reconciles an external logical muscle by programmatic scroll only when idle; a user drag cancels that job. After idle settlement, recentre to an equivalent middle-range index without emitting a selection.

BodyMap remains parameter-driven: `selectedMuscle`, sector fill and optional member fill enter `BodyMap`; selected geometry draws outer contrast then `scrim` inner stroke. No persistent source changes; Room remains SSOT for muscle maps and the existing ViewModel owns screen state/cancellation.

## Frozen contracts and decisions

1. `MuscleSelector(selected: Muscle?, roleText, onSelected)` remains source-compatible for analysis and editor. `null` displays the first logical muscle but does not write selection until a user settles a different muscle; it stores no index.
2. Define pure, testable index helpers: floor-mod virtual index → `Muscle`, nearest equivalent index for an external `Muscle`, and center/settled item. The virtual item key must be the virtual index, while emitted/semantic identity is logical `Muscle`.
3. A large bounded virtual range has a middle anchor aligned to the first `Muscle`. Recenter only after scroll is idle and only to an equivalent logical member. Recenter and external sync are silent; user gesture wins by cancelling programmatic scroll.
4. `LazyRow` uses Foundation snap fling behavior and three fixed item widths derived from available width after two dividers. Items may wrap their full names at font scale 2.0; adjacent interactive cells have `defaultMinSize(minHeight = 48.dp)`. Motion uses physical scrolling/Foundation behavior—no inline `spring`/`tween`, `AnimatedContent`, or fabricated one-step drag threshold.
5. On transition to a changed settled logical member caused by user scroll, neighbor tap, or TalkBack action, call `gymHaptics().tap()` and `onSelected` exactly once. Same member, recenter, first composition, and external parent reconciliation emit neither.
6. Retain custom TalkBack actions “Предыдущая мышца” and “Следующая мышца”; they use the same programmatic settle path and emit once. Current semantic state includes name and `roleText`; centre is selected/read-only.
7. User instruction supersedes the earlier selected-member outline-color contract: `selectedOutlineColorFor` returns `MaterialTheme.colorScheme.scrim` for any selected member. Its shared sector/member fill remains in the path fill; outer `surfaceContainerHigh` contrast stroke remains. No hardcoded `Color`.
8. No Hilt binding/scope, dispatcher, repository/domain, Room transaction/migration/schema, navigation/state-route, worker, permission, or dependency changes. ViewModel/SavedStateHandle restoration continues to own only logical `Muscle`.

## Tasks

| ID | Owner / deps | Exact files | Action | Automated verification / observable done condition | AC |
|---|---|---|---|---|---|
| T-024 | One implementation writer / none | `app/src/main/java/com/valerochka1337/valerochkagym/ui/analysis/body/MuscleSelector.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/analysis/body/MuscleSelectorTest.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/analysis/body/MuscleSelectorComposeTest.kt` | Replace `AnimatedContent` and manual one-step `draggable` with a 3-slot Foundation `LazyRow`, snap fling, large cyclic range, idle recenter and cancellation-aware external sync. Keep composable contract, dividers, roles, full names, tap neighbors and custom accessibility actions. Add pure mapping/recenter tests and Compose tests for cyclic wrap, multi-item fling, changed-only callback+haptic, silent external sync, drag cancellation, semantics, equal widths/48dp and fontScale 2.0. | `./gradlew :app:testDebugUnitTest --tests '*MuscleSelectorTest' --tests '*MuscleSelectorComposeTest'` passes; test evidence demonstrates a fling crosses >1 logical muscle and one settled changed selection emits exactly once. | 036–040 |
| T-025 | One implementation writer / T-024 | `app/src/main/java/com/valerochka1337/valerochkagym/ui/analysis/MuscleLoadCards.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/ui/library/ExerciseEditorSheet.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/AnalysisViewModelTest.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/library/ExerciseEditorSheetTest.kt` | Integrate only as needed to preserve both consumer call sites and their existing UDF events. Add regression coverage that external selected/null and `SavedStateHandle` restoration feed the wheel logically without persisting/requiring virtual index. | `./gradlew :app:testDebugUnitTest --tests '*AnalysisViewModelTest' --tests '*ExerciseEditorSheetTest' --tests '*MuscleSelectorComposeTest'` passes; analysis and editor compile and restore/select only `Muscle?`. | 041,043 |
| T-026 | One implementation writer / T-024 | `app/src/main/java/com/valerochka1337/valerochkagym/ui/analysis/body/BodyMap.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/analysis/MuscleHeatmapProjectionTest.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/AnalysisRenderTest.kt` | Freeze the selected inner outline to composable `colorScheme.scrim`, retain the contrast outer stroke and fill layering; update helper and focused render coverage. | `./gradlew :app:testDebugUnitTest --tests '*MuscleHeatmapProjectionTest' --tests '*AnalysisRenderTest'` passes; inspect the relevant authorized PNGs under `app/build/reports/analysis-render/` and record result. | 042 |
| T-027 | One implementation writer / T-024–T-026 | `vibe/muscle-carousel-loop-plan-track.md` only (implementation writes status/evidence; no production file added for this task) | Inspect diff for forbidden layers/dependencies/version changes; update tracker with commands, visual inspection, deviations/findings and residual risks. Root runs final project gates once after the stable diff. | `git diff --check`; root: `./gradlew :app:testDebugUnitTest`, then `./gradlew :app:assembleDebug`, both pass. Done only when tracker maps every AC to passing evidence and diff confirms no forbidden change. | 043, all |

## File ownership and execution waves

| Owner | Exclusive files/responsibility |
|---|---|
| One implementation writer | All T-024…T-027 files above, including shared selector, both consumers, BodyMap and tests/tracker. |
| Root session | Final sequential unit-suite and debug-assembly gate only; no concurrent Gradle. |

Wave 1: T-024. Wave 2: T-025 and T-026 sequentially under the same writer after the selector contract is stable (no parallel implementers). Wave 3: T-027, then root final gates. The single writer owns the shared UI choke points, preventing overlap.

## Quality gates

- Relevant Compose/accessibility/adaptive gate: immutable UDF contract, no expensive composition work, stable virtual key, cancellation ownership, 2.0 font scale, semantic state/custom actions, non-color-only role text, 48dp neighbor targets, compact/available-width three-cell layout.
- Relevant motion/haptic gate: Foundation physical scroll/snap only; no inline specs; `GymHaptics.tap()` once per changed user settlement.
- Relevant render gate: run `AnalysisRenderTest`; user authorized inspection of its relevant PNGs.
- Not applicable: Room/schema/migration, WorkManager/service, permissions/manifest, Hilt/dispatcher, navigation route, dependency/version-catalog, release/R8 gates.
- Final project gates (root, exactly once after stable implementation): `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug`.

## Risks, questions, rollback/data preservation

Risk: snap APIs or Compose test fling timing differ in the pinned BOM; mitigate through Foundation-compatible APIs and deterministic pure mapping tests. Risk: a programmatic external sync races a drag; cancel its job at drag start and assert no callback/haptic. Risk: huge index approaches a bound only after impractically many flings; idle recenter preserves the logical muscle before it matters. Risk: 2.0-font names make cells tall; wrapping is permitted, but neither width equality nor 48dp targets may regress.

No unresolved product blocker. The only implementation check is exact available Foundation snapping signatures. Rollback is code-only: restore the prior selector/outline behavior; no data, saved-state format, Room row, migration or worker state is changed or needs preservation.

## Gate P self-check

Pass: AC-036…AC-043 each map to T-024…T-027 and an automated verification; one writer owns every mutable file; selector/index, emission, external-sync, outline and no-dependency contracts are frozen; only Compose/accessibility/motion/render gates and final project gates are relevant.
