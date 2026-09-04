# Muscle load polish — executable plan

## Goal and scope

Polish the existing muscle-load experience without changing its persisted model: expose the
vendored SVG regions for neck and tibialis anterior, replace the selector with a compact,
three-item cyclic viewport, make exercise-detail involvement visibly role-weighted, and make
canonical (standard) exercises completely non-editable.  Bump the completed fix exactly once from
`versionCode 16` / `versionName 1.3.8` to `17` / `1.3.9`.

Out: Room/entity/DAO/schema/migration changes; repository or public navigation contract changes;
new dependencies, permissions, workers/services, sync behavior, chart palettes, and changes to
custom creation/editing or AI-created-new drafts. The manual front/back `BodyMapFlip` control stays.

Assumptions: `CanonicalExerciseRegistry.isBuiltIn(exercise)` is the built-in identity authority;
the existing `upper-back → LATS` SVG decision remains; standard means registry built-in rather than
`isCustom == false` alone. No product question remains open.

## Acceptance criteria

| AC | Acceptance condition | Task / automated verification |
|---|---|---|
| AC-025 | Neck and tibialis anterior use their matching vendored SVG paths, are tappable, auto-select the correct side, and leave the off-figure list; other mappings, including upper-back→LATS, remain stable. | T-013 / `BodyMapHitTest` |
| AC-026 | Each selector viewport presents exactly previous/current/next; current is centered, arrows are 48dp side controls, neighbor tap and swipe/fling settle one selection, and arrow motion is smooth and fast. | T-014 / selector-state + Compose semantics tests |
| AC-027 | The duplicate title/value row, whole-list control/list, and its accessibility action are absent; accessible previous/next actions and text state remain, with exactly one haptic for a settled changed selection and none duplicated by an external body tap. | T-014 / Compose semantics + selector-state tests |
| AC-028 | Exercise detail visualizes primary/secondary/stabilizer involvement with distinct Material-role/alpha strength; inactive regions differ; the text role list remains a non-colour alternative and the map is read-only. | T-015 / pure role-fill + `ExerciseDetailViewModelTest`/screen semantics test |
| AC-029 | A registry built-in exposes no edit/personalize entry point in detail or library flows; ViewModels reject built-in edit/save paths, including AI-existing and fallback `openEdit`; custom create/edit and AI-new behavior remain unchanged. | T-016 / `ExerciseDetailViewModelTest`, `ExerciseLibraryViewModelTest`, `ExerciseLibraryScreenTest` |
| AC-030 | The fix is versioned once as 17/1.3.9, preserves Room-backed state and navigation restoration, and passes final project gates. | T-017,T-018 / source assertion, full unit suite, debug assembly |

## Current → target flow

`Room exercise_muscles → DAO Flow → immutable ViewModel UiState → BodyMapFlip/Compose` remains
the SSOT/UDF path. T-013 only expands the projection from existing `BodyPaths`; it creates no new
muscle data. `BodyMap` tap emits a muscle upward to the existing owner, which updates saved selected
muscle; selector gestures/arrows/neighbour taps emit the same event. The reusable selector owns only
ephemeral pager/animation settlement and cancels its Compose coroutine with composition; screen
selection remains in the existing `SavedStateHandle`, so process/navigation restoration is unchanged.

Exercise detail continues to combine Room catalogue/maps/completed sets on `@ComputeDispatcher`
and `stateIn(WhileSubscribed(5000))`. It derives a display-only role-fill function from immutable
loads; it performs no writes and needs no new Hilt binding or scope. Built-in protection occurs at
every UI/VM editor ingress using the canonical registry; repository/domain API, Room ownership,
background work, and cancellation ownership remain unchanged.

## Frozen decisions and contracts

1. Map `neck` and `tibialis` source slugs to `Muscle.NECK` and
   `Muscle.TIBIALIS_ANTERIOR`, respectively; remove only those two from `offFigureMuscles`.
   Keep existing front/back geometry and LATS mapping unchanged.
2. The selector has one cyclic horizontal viewport containing exactly three rendered choices:
   previous/current/next. It may use `HorizontalPager` if compatible with the pinned Compose API;
   otherwise retain a bounded three-slot state holder. Programmatic arrows animate with
   `GymMotion.spatialFast()`; a new gesture interrupts/cancels the active animation. No unbounded
   virtual list, full-list fallback, duplicated heading, or `Open entire list` accessibility action.
3. `selected` remains the sole external selection source. On settling a changed page, send one
   `onSelected` and one `gymHaptics().tap()`; update local settled identity before callback so a
   body-map-originated state update cannot repeat the haptic. Same selection has neither callback
   nor haptic.
4. Exercise-detail role fills use `MaterialTheme.colorScheme` only: inactive is a distinct neutral
   surface/outline-derived fill; primary is strongest `primary`, secondary a lower-alpha primary,
   stabilizer weakest alpha. The existing labelled list conveys every role in text; `BodyMapFlip`
   receives `onMuscleClick = null` and keeps its front/back control.
5. Standard exercise editability is `!CanonicalExerciseRegistry.isBuiltIn(exercise)`, never an
   inference from `isCustom`. Detail and library hide/remove actions, and `openEditor`, `openEdit`,
   `saveEditor`, and AI-existing routing defensively decline a built-in without clone/persist.
   Existing custom edit, custom creation, and AI-new draft flows are unchanged.
6. No new Room transaction/migration/schema, repository method, DI binding/scope, permission,
   WorkManager work, service, or navigation route is allowed. `viewModelScope` retains VM work;
   Compose effect cancellation owns pager animation.

## Tasks

| ID | Owner / deps | Exact files | Action | Automated verification and observable done condition | AC |
|---|---|---|---|---|---|
| T-013 | Implementation writer; none | `ui/analysis/body/BodyMuscleMapping.kt`; `test/ui/BodyMapHitTest.kt` | Bind already-vendored `neck`/`tibialis` paths to `Muscle.NECK`/`Muscle.TIBIALIS_ANTERIOR`, update preferred-side/off-figure projection and tests; do not alter unrelated geometry/slugs. | `./gradlew :app:testDebugUnitTest --tests '*BodyMapHitTest'`; every mapped path is non-empty/tappable, NECK/TIBIALIS no longer off figure, and LATS mapping regression passes. | 025 |
| T-014 | Implementation writer; T-013 | `ui/analysis/body/MuscleSelector.kt`; `test/ui/BodyMapHitTest.kt`, new focused `test/ui/analysis/body/MuscleSelectorTest.kt` if Compose semantics requires isolation | Replace lazy virtual/full-list UI with a bounded three-item cyclic pager/viewport, side arrows, neighbour taps, snap/fling settlement, `GymMotion.spatialFast`, and saved-selection compatible events. Remove obsolete state helpers/imports. | `./gradlew :app:testDebugUnitTest --tests '*BodyMapHitTest' --tests '*MuscleSelectorTest'`; tests prove wrapped prev/current/next order, centered current, arrow/neighbour/fling result, smooth programmatic animation contract, 48dp semantics, no duplicated header/list action, and single settled haptic callback. | 026,027 |
| T-015 | Implementation writer; T-013 | `ui/exercise/ExerciseDetailScreen.kt`; `test/ui/ExerciseDetailViewModelTest.kt`, new focused `test/ui/exercise/ExerciseDetailScreenTest.kt` if no existing semantics host | Extract/test a role-to-fill helper and pass it plus `onMuscleClick = null` into `BodyMapFlip`; retain labelled role rows and loading/empty/error/content behavior. | `./gradlew :app:testDebugUnitTest --tests '*ExerciseDetailViewModelTest' --tests '*ExerciseDetailScreenTest'`; primary/secondary/stabilizer/inactive fills are ordered/distinct, semantic text names each role, and map exposes no tap action. | 028 |
| T-016 | Implementation writer; T-015 | `ui/exercise/ExerciseDetailScreen.kt`, `ExerciseDetailViewModel.kt`; `ui/library/ExerciseLibraryScreen.kt`, `ExerciseLibraryViewModel.kt`, `AiExerciseCreationSheet.kt` only if call-site handling is required; `data/db/CanonicalExerciseRegistry.kt` (read-only contract use only); `test/ui/ExerciseDetailViewModelTest.kt`, `ExerciseLibraryViewModelTest.kt`, `ui/library/ExerciseLibraryScreenTest.kt` | Remove built-in edit/personalize UI. At all editor ingress and save paths use registry identity to reject standard exercises, including library fallback and AI-existing; leave custom/AI-new paths intact. | `./gradlew :app:testDebugUnitTest --tests '*ExerciseDetailViewModelTest' --tests '*ExerciseLibraryViewModelTest' --tests '*ExerciseLibraryScreenTest'`; built-in has no edit semantics/editor/write or clone, while custom edit/create and AI-new still work. | 029 |
| T-017 | Implementation writer; T-013–T-016 | `app/build.gradle.kts`; focused tests above | Apply the sole version increment and run the consolidated targeted gate after the stable implementation diff. | `./gradlew :app:testDebugUnitTest --tests '*BodyMapHitTest' --tests '*MuscleSelectorTest' --tests '*ExerciseDetailViewModelTest' --tests '*ExerciseDetailScreenTest' --tests '*ExerciseLibraryViewModelTest' --tests '*ExerciseLibraryScreenTest'`; production source declares exactly `17` and `1.3.9`. | 025–030 |
| T-018 | Root; T-017 | `vibe/muscle-load-polish-plan-track.md` | Run final gates once, record outcomes/findings/deviations; no Gradle run for this planning-only change. | `./gradlew :app:testDebugUnitTest` then `./gradlew :app:assembleDebug`, sequentially green. | 025–030 |

## Ownership and waves

One implementation writer owns T-013…T-017. File sets intentionally overlap at BodyMap/VM/UI
choke points, so parallel implementation is prohibited. The writer preserves unrelated concurrent
changes. Root exclusively owns final command recording in T-018.

| Boundary | Owner | Files/contracts |
|---|---|---|
| SVG mapping and selector | Implementation writer | T-013,T-014; `BodyMuscleMapping`, selector and focused tests |
| Detail role rendering | Implementation writer | T-015; detail screen/helper and tests |
| Built-in edit boundary + version | Implementation writer | T-016,T-017; detail/library VMs/screens, AI-existing ingress, version |

| Wave | Tasks | Gate |
|---|---|---|
| 1 | T-013 | geometry/hit test |
| 2 | T-014,T-015 | selector and detail focused tests (one writer, ordered) |
| 3 | T-016,T-017 | edit-boundary and consolidated targeted test gate |
| 4 | T-018 | final unit suite then debug assembly |

## Relevant quality gates

- UDF/SSOT: Room and existing DAO flows only; immutable UI state down/events up; no raw dispatcher,
  new Hilt binding, repository change, or background/system work.
- Compose/accessibility: Material colors, `GymMotion`, `GymHaptics`; 48dp arrows; meaningful
  previous/next semantics and textual role alternative; font scale 2.0; compact and
  medium/expanded layouts; navigation/back and saved selection restoration. Use semantics tests,
  never screenshots.
- Tests: JUnit4 names in present-tense backticks, handwritten private fakes, live state collectors
  before `uiState.value`. No chart-render gate, migration/schema gate, release gate, permissions, or
  WorkManager gate is relevant.
- Final project gates once, sequentially: `./gradlew :app:testDebugUnitTest`, then
  `./gradlew :app:assembleDebug`.

## Risks, rollback, and Gate P

Pager API compatibility with the pinned Compose BOM is the sole technical choice; use the existing
bounded state alternative if `HorizontalPager` is unavailable. Alpha differences must remain
perceivable without becoming the only role signal; semantics/text tests protect that. Built-in guards
must query the registry at every ingress, avoiding stale `isCustom` imports.

There is no persisted-data change: upgrade/downgrade preserves Room rows, maps, histories, sync and
navigation routes. Version rollback carries ordinary APK downgrade risk only; no migration/data
preservation action is required. No unresolved blockers.

Gate P: pass — AC-025…AC-030 each map to a task and command; one writer owns all overlapping
files; selection, role-fill, and built-in contracts are frozen before implementation; relevant state,
dispatcher/cancellation, DI, navigation/restoration, accessibility/adaptive, data, background and
quality-gate implications are explicit; only applicable conditional gates are included.

---

## Follow-up: shared sectors and selector text polish (AC-031+)

### Goal, scope, non-goals, assumptions

Replace arrow/chip selector chrome with three equal text slots and make the existing SVG map a
complete logical-muscle projection: every one of the 25 persisted `Muscle` values must select,
colour, and be reachable through one or more existing SVG sectors. The same `MuscleSelector` is
intentionally shared by analysis and the exercise editor. Keep this as the same completed feature:
`versionCode 17` / `versionName 1.3.9` remain unchanged.

In scope: sector-first SVG definition/parsing/drawing/hit-testing, heatmap and detail/editor
projection policies, selector accessibility/adaptive typography, removal of the editor's
off-figure assist chips, and focused/render regression coverage. Out: Room/entity/DAO/schema or
migration, analytics math, repository/domain API, navigation routes, Hilt bindings/scopes,
permissions, workers/services, new dependencies, and InBody geometry/classification changes.

Assumptions: a shared anatomical SVG sector represents one visual area but does not collapse the
logical persisted `Muscle` model; existing `SavedStateHandle` names remain valid. User explicitly
authorizes render screenshot generation and inspection for this follow-up. No product-changing
question remains open.

### Acceptance criteria

| AC | Acceptance condition | Task / automated verification |
|---|---|---|
| AC-031 | Both consumers show exactly equal-width previous/current/next text slots: no arrows, `IconButton`, `FilterChip`, container/outline blocks, or truncation. Two decorative `outlineVariant` vertical dividers separate the slots; neighbours are clickable at least 48dp, current is selected/non-clickable, and root previous/next custom actions remain. | T-020 / `MuscleSelectorTest`, `MuscleSelectorComposeTest` |
| AC-032 | Full Russian `displayName()` always soft-wraps with no `maxLines`, ellipsis, or fixed height; fontScale 2.0 works. The role/value under the current name is horizontally centred (heatmap's numeric effective-set label and editor role label). Swipe/fling, direction-aware `GymMotion.spatialFast`, cancellation on a new gesture, and one haptic per changed settlement remain. | T-020 / selector pure + Compose font-scale/semantics tests |
| AC-033 | Sector-first definitions parse and draw every existing SVG path exactly once; `offFigureMuscles` is empty and all 25 logical muscles map to at least one existing front/back sector with the stated shared-sector mappings and explicit preferred-side metadata. | T-019 / `BodyMapHitTest`, sector projection tests |
| AC-034 | A selected logical muscle outlines every containing sector using its exact heat/detail/editor colour. Without selection, heatmap uses the maximum `weeklySets` member of a shared sector (not sum/average); detail/editor use the strongest involved role/member. Canvas tap returns the selected member if contained, otherwise deterministic sector default; TalkBack enumerates each logical member. Editor has no off-figure fallback assist chips. | T-019,T-021 / body-map hit/semantics and heatmap/detail/editor projection tests |
| AC-035 | The projection remains display-only: Room SSOT, analytics, immutable ViewModel state/events, `SavedStateHandle` restoration, navigation, DI/scopes, dispatcher/cancellation ownership, background work and InBody segment classifications are unchanged. Required render snapshots (analysis cards, detail, editor/body selections, lower-chest/shared-sector case) are generated and explicitly inspected; final project gates pass once on a stable diff. | T-022,T-023 / `AnalysisViewModelTest`, `InBodySegmentHeatmapTest`, `AnalysisRenderTest`, full unit suite, debug assembly |

### Current → target data and execution flow

`Room exercise_muscles → DAO Flow → AnalyticsEngine on @ComputeDispatcher → immutable
AnalysisUiState → BodyMapFlip/MuscleSelector` remains the analysis SSOT/UDF chain. The editor
continues to own only its remembered draft loads, committing through its existing ViewModel/repository
path. The target inserts a pure display projection: `SectorDefinition(slug, logicalMuscles,
defaultTapMuscle, preferred side) → ParsedSector` parses the SVG once per view and draws each path
once. Consumers provide logical-muscle values; the projection derives a sector value without
persisting or aggregating new data.

Selection remains a logical `Muscle`: canvas emits the selected contained member or sector default;
the screen/ViewModel event updates `SavedStateHandle` as today. `MuscleSelector` owns only ephemeral
drag/animation settlement; Compose cancellation owns a superseded animation/gesture, while
`AnalysisViewModel` retains the saved selection. Existing `flowOn(@ComputeDispatcher)` continues to
own analytics work; this projection is bounded UI work and adds no dispatcher, coroutine, Hilt, or
background ownership.

### Frozen contracts

1. `MuscleSelector` renders exactly three equal-width text slots (previous/current/next), two
   decorative `VerticalDivider`s in `MaterialTheme.colorScheme.outlineVariant`, and no arrows,
   `IconButton`, `FilterChip`, outlined/container block, or hidden full-list control. Full names use
   `softWrap = true`, no `maxLines`/ellipsis/fixed height. Neighbours have independent >=48dp
   targets; current exposes selected state but no click. Its root keeps custom previous/next actions.
2. Previous/current/next order, cyclic wrapping, swipe/fling direction, `GymMotion.spatialFast`,
   gesture cancellation and exactly one `gymHaptics().tap()` per changed settlement are retained.
   The current role/value is centred below its name; heatmap formats the numeric effective-set label,
   editor supplies its role label.
3. A sector is first-class and sector-first: SVG slug, `Set<Muscle>`, deterministic default tap
   muscle, and explicit preferred-side metadata. It is parsed/drawn once; no inverse
   one-muscle-per-geometry map or duplicated paths. `offFigureMuscles == emptyList()`.
4. Coverage mapping is frozen: upper/lower chest→front chest; front delts→front deltoids; side
   delts→front+back deltoids; rear delts+rotator cuff→back deltoids; serratus→front obliques;
   biceps→front biceps; triceps/forearms/adductors/calves/traps→their matching front+back sectors;
   abs/obliques/quads/tibialis→matching front; hip flexors→front quadriceps; hamstrings/glutes/lower
   back→matching back; hip abductors→back gluteal; lats+upper back→back upper-back; neck→front+back
   neck. Preferred-side metadata is explicit and preserves the current stable view decision where
   possible.
5. Sector projection: selected logical member outlines all its sectors and uses that member's exact
   supplied colour. Unselected heatmap sector colour comes from the member with maximum `weeklySets`;
   detail/editor choose the strongest involved role/member (deterministic enum-order tie-break). A
   canvas tap chooses the selected contained member, else the sector default; TalkBack provides a
   custom action per logical member. State never becomes a sector slug.
6. `BodyMapFlip` stays the sole shared choke point. InBody paths remain single-drawn and segment
   classifications unchanged. No Room transaction/migration/schema, repository/domain, DI,
   navigation, permission, Worker/service, version, or persistence mutation is allowed.

### Tasks

| ID | Owner / deps | Exact files | Action | Automated verification and observable done condition | AC |
|---|---|---|---|---|---|
| T-019 | Implementation writer; T-013 complete | `ui/analysis/body/BodyMuscleMapping.kt`; `ui/analysis/body/BodyMap.kt`; `test/ui/BodyMapHitTest.kt`; new focused `test/ui/analysis/body/MuscleSectorProjectionTest.kt` | Replace muscle-keyed paths/shapes with frozen sector definitions and parsed sectors; expose pure coverage/preferred-side/tap/sector-projection helpers. Draw paths once, select all containing sectors, preserve silhouette/InBody isolation and enumerate member actions. | `./gradlew :app:testDebugUnitTest --tests '*BodyMapHitTest' --tests '*MuscleSectorProjectionTest'`; all 25 values covered, `offFigureMuscles` empty, sectors/paths are unique, shared sector max/strongest/tap/collision behavior and stable preferred side pass. | 033,034 |
| T-020 | Implementation writer; T-019 | `ui/analysis/body/MuscleSelector.kt`; `test/ui/analysis/body/MuscleSelectorTest.kt`; `test/ui/analysis/body/MuscleSelectorComposeTest.kt` | Replace arrow/chip controls with the frozen three-slot text layout and dividers; retain cyclic state/gesture/settlement semantics, centring and root actions. Add 2.0 font-scale host coverage. | `./gradlew :app:testDebugUnitTest --tests '*MuscleSelectorTest' --tests '*MuscleSelectorComposeTest'`; exactly three equal slots/two dividers/no arrow-or-chip semantics, full wrapped labels, 48dp neighbour targets/current disabled, centred value, root actions, wrap/swipe/fling/haptic rules pass. | 031,032 |
| T-021 | Implementation writer; T-019,T-020 | `ui/analysis/MuscleLoadCards.kt`; `ui/exercise/ExerciseDetailScreen.kt`; `ui/library/ExerciseEditorSheet.kt`; `test/ui/exercise/ExerciseDetailScreenTest.kt`; `test/ui/exercise/ExerciseDetailScreenComposeTest.kt`; new focused `test/ui/analysis/MuscleHeatmapProjectionTest.kt`; new focused `test/ui/library/ExerciseEditorSheetTest.kt` if no suitable host exists | Supply sector-aware value resolvers from heatmap/detail/editor, centre count/role through the shared selector, and remove editor fallback assist chips/imports. Preserve existing loading/empty/error/content and read-only detail map behavior. | `./gradlew :app:testDebugUnitTest --tests '*MuscleHeatmapProjectionTest' --tests '*ExerciseDetailScreenTest' --tests '*ExerciseDetailScreenComposeTest' --tests '*ExerciseEditorSheetTest'`; lower chest shares chest geometry but retains exact selected colour, heatmap uses maximum not sum/average, detail/editor strongest role wins, selected member/talkback behavior and absent fallback chips pass. | 031,032,034 |
| T-022 | Implementation writer; T-019–T-021 | `test/ui/AnalysisViewModelTest.kt`; `test/ui/analysis/body/InBodySegmentHeatmapTest.kt`; `test/ui/AnalysisRenderTest.kt` | Add only restoration regression needed to prove selected logical muscles survive recreation; preserve InBody regression and extend render fixtures for lower-chest/shared sector, analysis card, detail and editor/body selections. Run render test and explicitly inspect `app/build/reports/analysis-render/` PNGs for wrapping/dividers, centring, no duplicate paths, selection and clipping. | `./gradlew :app:testDebugUnitTest --tests '*AnalysisViewModelTest' --tests '*InBodySegmentHeatmapTest' --tests '*AnalysisRenderTest'`; generated named PNGs are manually inspected and no regression is recorded. | 033–035 |
| T-023 | Root; T-019–T-022 | `vibe/muscle-load-polish-plan-track.md` | On the stable implementation diff, run the consolidated targeted gate, then final gates once sequentially; record command output, snapshot inspection, findings/deviations and residual risk. Do not touch `app/build.gradle.kts`: same feature remains 17/1.3.9. | `./gradlew :app:testDebugUnitTest --tests '*BodyMapHitTest' --tests '*MuscleSectorProjectionTest' --tests '*MuscleSelectorTest' --tests '*MuscleSelectorComposeTest' --tests '*MuscleHeatmapProjectionTest' --tests '*ExerciseDetailScreenTest' --tests '*ExerciseDetailScreenComposeTest' --tests '*AnalysisViewModelTest' --tests '*InBodySegmentHeatmapTest' --tests '*AnalysisRenderTest'`; then `./gradlew :app:testDebugUnitTest`, then `./gradlew :app:assembleDebug`; all green and tracker evidence complete. | 031–035 |

### Ownership and execution waves

One implementation writer owns T-019–T-022 because `BodyMapFlip`/sector definitions and the shared
selector are choke points touched by every consumer; no parallel implementation. Root alone owns
T-023 command recording. The writer preserves other agents' existing changes.

| Boundary | Owner | Files/contracts |
|---|---|---|
| Sector geometry, semantics and preferred-side projection | Implementation writer | T-019; body mapping/map and projection tests |
| Shared selector | Implementation writer | T-020; selector UI/state/Compose tests |
| Consumer values, editor fallback removal and render/restoration evidence | Implementation writer | T-021,T-022; analysis/detail/editor and tests |
| Final command evidence | Root | T-023; tracker only |

| Wave | Tasks | Gate |
|---|---|---|
| 5 | T-019 | sector coverage, collision, tap and shared-projection tests |
| 6 | T-020,T-021 | selector and consumer-focused tests (ordered, one writer) |
| 7 | T-022 | restoration/InBody/render test and explicit PNG inspection |
| 8 | T-023 | consolidated target gate; final unit suite then debug assembly once |

### Relevant quality gates, risks, rollback, Gate P

- UI/accessibility/adaptive: Material-only divider/theme colours, `GymMotion`, `GymHaptics`, 48dp
  neighbours, custom Canvas alternatives, fontScale 2.0, compact and medium/expanded behaviour.
  User-authorized render snapshots must be inspected after `AnalysisRenderTest`.
- Data/concurrency: existing Room/DAO SSOT and `StateFlow` UDF only; no new persistence or
  computation. `flowOn(@ComputeDispatcher)`, `viewModelScope`, Compose cancellation, saved state
  and navigation restoration stay unchanged; only the relevant restoration regression is added.
- Excluded conditional gates: no Room/migration/schema, DI, permission, WorkManager/service,
  release/R8, dependency or navigation-route gate applies. Final project gates remain required.
- Risk: shared visual sectors can hide a weaker logical member unless selection semantics and
  non-colour labels are exact; deterministic max/strongest rules and tests prevent accidental
  sum/average. Long labels can increase card height at 200% font scale; render/Compose inspection
  verifies no clipping. SVG parsing must stay process-cached and each path single-drawn.

Rollback/data preservation: no persistent format or version change; reverting UI projection keeps
all Room rows, history, sync and saved logical muscle names intact. APK downgrade has ordinary
platform restrictions only. No unresolved blockers.

Gate P: pass — AC-031…AC-035 each map to T-019…T-023 and automated verification; contracts,
ties, ownership, cancellation, SSOT/restoration and render inspection are frozen; writers have no
overlapping ownership; only relevant conditional gates are included.
