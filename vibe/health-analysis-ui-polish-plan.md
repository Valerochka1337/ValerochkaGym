# Health analysis UI polish — implementation plan

## Goal

Make the fourth Analysis selector chip, «Здоровье», reachable and wholly visible on compact
devices at `fontScale = 2f`; remove the duplicate measurement-journal action from an empty
health card without changing health data, ViewModel, navigation, or app-bar behaviour.

## Scope and non-goals

In scope: `AnalysisSectionSelector`, `HealthOverviewCard`, their accessibility/render tests, review,
and the one patch version increment for this follow-up from branch version `23 / 1.3.15` to
`24 / 1.3.16`.

Out of scope: `AnalysisViewModel`, immutable health state/data ownership, repositories/domain,
Room/schema/migrations, DI/dispatchers/cancellation, WorkManager, permissions, routes and route
arguments, app-bar «Замеры», dependencies, charts, and adaptive-shell policy. No new loading,
error, or navigation state is introduced: existing Room → `AnalysisViewModel` → immutable
`HealthAnalysisState` → Compose collection remains the SSOT and continues to own loading,
empty/content/offline/conflict/missing-original presentation.

Version baseline: `origin/main` owns `22 / 1.3.14`, while the current health feature branch already
owns `23 / 1.3.15`; this distinct follow-up therefore advances the branch once to `24 / 1.3.16`.
Recheck the target immediately before publication. The existing `rememberSaveable` selected section
remains the only restored UI state; the list scroll position is local, ephemeral composable state.

## Acceptance criteria

| AC | Criterion | Automated evidence |
|---|---|---|
| AC-001 | Selector remains a horizontally scrollable `LazyRow`, fills available width, keeps a remembered `LazyListState`, and presents all four sections. | `AccessibilityFoundationTest` selector test |
| AC-002 | Selecting «Здоровье» on compact width at `fontScale = 2f` scrolls it until its complete chip bounds are inside the viewport. | `AccessibilityFoundationTest` reachability/full-visibility assertion |
| AC-003 | Every selector action remains at least 48dp high; selected «Здоровье» exposes selected semantics/state. | `AccessibilityFoundationTest` |
| AC-004 | With no measurement, `HealthOverviewCard` exposes only «Открыть замеры» (not «Все замеры»), and it invokes the measurements-journal callback. | `AnalysisRenderTest` empty-card action test |
| AC-005 | With a latest measurement, «Открыть последний замер» calls `onOpenMeasurement` with that exact ID and «Все замеры» separately calls `onOpenMeasurements`. | `AnalysisRenderTest` content-card actions test |
| AC-006 | Existing health loading/content/error-adjacent branches, app-bar «Замеры», routes, ViewModel/domain/Room contracts, and adaptive navigation are unchanged; the targeted tests and final project gates pass. | focused tests, diff review, sequential final gates |

## Current → target flow

```
Room flows → AnalysisViewModel immutable HealthAnalysisState → AnalysisScreen / HealthOverviewCard
                                                    └ selector: selected section (rememberSaveable)

Current selector: select HEALTH → LazyRow may leave its chip clipped
Target selector:  select HEALTH → LazyListState observes layout → scroll only enough to reveal chip

Current empty card: no measurement → «Открыть замеры» + duplicate «Все замеры» → same journal
Target empty card:  no measurement → «Открыть замеры» → journal
Target content card: latest measurement → exact-ID detail action + separate journal action
```

The UI remains unidirectional: `AnalysisScreen` owns the ephemeral selected section and sends
clicks upward; `HealthOverviewCard` is parameter-driven and neither reads a DAO nor launches work.
No coroutine, dispatcher, Hilt binding/scope, cancellation owner, transaction, migration,
background-work, or permission behaviour changes.

## Frozen contracts and decisions

- `AnalysisSectionSelector` keeps `modifier`, `selected`, and `onSectionSelected` API. It owns one
  `rememberLazyListState`; its `LazyRow` remains `fillMaxWidth()` and horizontally scrollable.
  A `LaunchedEffect(selected)` may use that state/layout info to scroll just enough for the selected
  item to lie entirely inside `viewportStartOffset..viewportEndOffset`; it must not mutate
  `selected`, invent persistence, or move state into the ViewModel.
- Keep `GymFilterChip`; it supplies selected semantics and its established visual language. Retain
  the 48dp minimum target and call the existing screen-level `gymHaptics().tap()` exactly once in
  the parent selection event. Do not add a raw haptic call, colour, motion spec, or dependency.
- `HealthOverviewCard` keeps callbacks. Render the journal `TextButton` only when
  `latestMeasurement != null`. Empty «Открыть замеры» calls `onOpenMeasurements`; content
  «Открыть последний замер» calls `onOpenMeasurement(latestMeasurement.id)`, while «Все замеры»
  calls `onOpenMeasurements`. Existing report/restriction/archive/conflict actions are untouched.
- No navigation or state-restoration contract changes: the app-bar action and both card journal
  paths retain the existing `MEASUREMENTS` route; detail route ID remains exact.
- No persistence or background implications: data ownership stays in existing repositories/Room;
  no entity/DAO/schema/migration/worker/permission/DI work is applicable.

## Tasks

| Task | Owner | Depends on | Exact files | Actions | Automated verification | Observable done condition | AC |
|---|---|---|---|---|---|---|---|
| T-001 Selector visibility implementation | Implementation writer | frozen contracts | `app/src/main/java/com/valerochka1337/valerochkagym/ui/analysis/AnalysisScreen.kt` | Add/reuse one remembered `LazyListState`, apply `fillMaxWidth`, and on selected-section change reveal the selected item completely without removing horizontal user scrolling or parent haptics/saveable selection. | T-003 focused command | HEALTH is reachable and no selected chip is clipped in the covered compact/font-scale case; source has no VM/navigation contract change. | AC-001–003, AC-006 |
| T-002 Health-card action implementation | Implementation writer | frozen contracts | `app/src/main/java/com/valerochka1337/valerochkagym/ui/analysis/HealthCards.kt` | Gate «Все замеры» on a latest measurement; preserve exact-ID detail and all unrelated health branches/actions. | T-003 focused command | Empty card has one journal entry point; populated card has two semantically distinct entry points. | AC-004–006 |
| T-003 Targeted regression tests | Implementation writer | T-001, T-002 | `app/src/test/java/com/valerochka1337/valerochkagym/ui/AccessibilityFoundationTest.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/AnalysisRenderTest.kt` | Replace the selector test with a compact viewport + `fontScale=2f` test that scrolls/selects Health, verifies selected semantics, 48dp height, and full bounds inside the selector viewport. Extend empty-card test to assert no «Все замеры» node and its one callback; retain/strengthen content test for exact ID and journal callback separation. Use existing Compose/Robolectric fixtures; no screenshots or artifact inspection. | `./gradlew :app:testDebugUnitTest --tests '*AccessibilityFoundationTest' --tests '*AnalysisRenderTest'` | Both test classes pass and directly prove every UI branch in AC-001–005. | AC-001–005 |
| T-004 Review, version integration, and final gates | Implementation writer | T-003 | `app/build.gradle.kts` (conditional version-only edit); all T-001–T-003 files for review; `vibe/health-analysis-ui-polish-plan-track.md` | Review changed files for frozen contracts/diff scope. Immediately before version edit, compare the confirmed target version; only if it is `23 / 1.3.15`, update exactly once to `24 / 1.3.16`. Record review and commands in tracker. Run final gates sequentially after the stable diff. | `git diff --check`; then `./gradlew :app:testDebugUnitTest`; then `./gradlew :app:assembleDebug` | Review has no open P0/P1; version is exactly one confirmed-target increment (or task is explicitly blocked awaiting target/version confirmation); final commands pass. | AC-006 |

## Ownership and execution

One implementation writer owns T-001–T-004 and the only production choke point
(`AnalysisScreen.kt`), both test files, conditional version file, and tracker updates. This avoids
overlap in shared Compose contracts and permits no parallel implementer. Review is a read-only
activity by the same final integration owner or a separate reviewer, never a second file writer.

| File / responsibility | Exclusive owner |
|---|---|
| `ui/analysis/AnalysisScreen.kt` selector/list state and scroll reveal | Implementation writer |
| `ui/analysis/HealthCards.kt` empty/content measurement actions | Implementation writer |
| `AccessibilityFoundationTest.kt`, `AnalysisRenderTest.kt` | Implementation writer |
| `app/build.gradle.kts` one conditional version bump | Implementation writer |
| plan tracker evidence | Implementation writer |

| Wave | Work | Entry / exit |
|---|---|---|
| W1 | T-001, T-002, T-003 sequentially by one writer | Contracts frozen; focused UI proof passes |
| W2 | T-004 review, confirm target/version, conditional version integration | No open focused-test failure; no overlapping writer |
| W3 | Final sequential project gates | Stable reviewed diff; unit suite then debug assembly pass |

## Quality gates

- Relevant Compose/accessibility gate: compact width, `fontScale=2`, selected semantics/state,
  full selected-chip visibility, horizontal scrolling, and 48dp targets through T-003; retain
  medium/expanded behaviour because `fillMaxWidth` and the adaptive shell are not altered.
- Relevant state/navigation gate: preserve existing `rememberSaveable` section state and existing
  callbacks/routes; review the diff confirms no ViewModel, navigation, repository, or Room access.
- `AnalysisRenderTest` is relevant because it covers the changed Health composable, but no chart
  rendering changed; do not inspect `analysis-render` image artifacts without explicit user
  permission.
- Not applicable: Room/migration/schema, Hilt/DI, dispatcher/cancellation, WorkManager/service,
  permissions/manifest, dependency/R8/release assembly. No release gate is needed for this
  UI-only fix unless scope later changes.
- Final gates, exactly once and sequentially after stable code: `./gradlew :app:testDebugUnitTest`,
  then `./gradlew :app:assembleDebug`.

## Risks, questions, rollback

- The visibility assertion must use the actual selector viewport rather than merely asserting that
  a semantics node exists; otherwise a partially clipped chip regresses unnoticed.
- Avoid an unconditional `scrollToItem` that makes manual scroll position jump after recomposition;
  calculate/reveal only the selected item and keep user scrolling intact.
- Button-text matching is duplicated wording; test both absence in empty state and callback routing
  in content state to distinguish the actions.
- Unresolved blocker: exact version bump requires confirmation of the target branch and that it
  still contains `23 / 1.3.15`. If not confirmed, implementation and tests may finish but T-004
  remains blocked rather than risking a conflicting version.
- Rollback is source-only: revert the two composable edits and their tests; no persisted data,
  migration, worker, or navigation state is created or needs preservation.

## Gate P self-check

Pass: AC-001…006 each map to a task and automated verification; all writable implementation files
have one owner; selector/action contracts are frozen before work; Room/DI/background/permission
gates are deliberately excluded as irrelevant; focused and final sequential gates are explicit.

Recommended first implementation task: **T-001**, then T-002 in the same writer turn before
adding the paired regression tests.
