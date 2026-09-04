# Muscle selector carousel polish — executable plan

## Goal

Give the existing cyclic three-slot `MuscleSelector` a restrained, continuous focus-by-distance treatment: as a visible item approaches the viewport centre, its label subtly gains scale, opacity, and the theme's primary text tone. Preserve the wheel's settled logical-selection contract and keep the body map visually dominant.

## Scope, non-goals, assumptions

Scope is AC-044…AC-048 from `vibe/muscle-selector-carousel-polish-product.md`: selector visuals and pure focus math, focused regression tests, and exactly one version increment from `17 / 1.3.9` to `18 / 1.3.10`.

Out: new dependencies or copied source; central card/block; gradients, 3D/rotation, arrows or altered slot count; changes to BodyMap, ViewModels, navigation, repositories/domain, Room/schema/migrations, DI, permissions, workers, background work, or sync. No loading/empty/error state exists at this reusable selector boundary: its total `Muscle.entries` catalogue and nullable logical input already define its only presentation state. No screenshot or render test is authorized or relevant.

Assume the pinned Compose Foundation APIs continue to expose each visible `LazyListItemInfo`'s measured offset and size; the effect is a pure visual transform driven by that geometry and does not change snap physics. The final aesthetic judgement is a manual owner check on device, outside automated acceptance evidence.

## Acceptance criteria

| AC | Condition | Task / automated evidence |
|---|---|---|
| AC-044 | Settled centre is stronger through themed colour, scale, and alpha only; no block, gradient, 3D, or hardcoded colour. | T-028 / pure focus-profile and Compose visual-property tests |
| AC-045 | Focus changes continuously by item-to-viewport-centre distance during drag/fling and creates no callback or haptic beyond the established settle path. | T-028 / pure distance interpolation plus existing one-settle Compose regressions |
| AC-046 | Cyclic snap, multi-item fling, neighbour tap, silent external sync, and logical-`Muscle?` restoration remain unchanged. | T-028 / existing and retained selector unit/Compose tests |
| AC-047 | At fontScale 2.0 labels/role wrap without clipping; slots/dividers remain equal; neighbours are >=48dp; TalkBack state/actions remain. | T-028 / Compose layout and semantics tests |
| AC-048 | No dependency/data/VM/DI/nav/background change; app is exactly `18 / 1.3.10`; unit suite and debug assembly pass. | T-029, T-030 / diff inspection, full Gradle gates |

## Current → target flow

Current: parent-owned `selected: Muscle?` (from existing screen UDF/SavedStateHandle restoration) → `MuscleSelector` derives a non-durable virtual `LazyRow` anchor → Foundation snap settles its actual centre → changed user selection calls `gymHaptics().tap()` once then `onSelected(Muscle)`; external reconciliation/recentering are silent.

Target: the same flow, plus `LazyListState.layoutInfo.visibleItemsInfo` → pure distance-to-viewport-centre focus profile → `graphicsLayer` scale/alpha and `Text` themed colour per visible slot. This projection is read-only: it neither owns selection nor launches work, so drag, fling, callback, haptic, cancellation, external reconciliation, and restoration retain their current owners and timing.

## Architectural decisions and frozen contracts

1. SSOT remains the caller's logical `Muscle?`; virtual indices and focus fractions are composable-local, ephemeral render state and are never saved or emitted. `null` still displays the existing first logical fallback without writing a selection.
2. `MuscleSelector(selected, roleText, onSelected, modifier)` remains source-compatible. State is immutable down (`Muscle?`, role lambda); changed settled user intent is the sole event up (`onSelected(Muscle)`). No ViewModel, repository, dispatcher, Hilt scope/binding, domain state, navigation route, Room transaction/migration, permission, or background ownership changes.
3. Add an internal pure focus-profile helper which accepts item/viewport geometry, clamps normalized centre distance, and returns bounded scale and alpha. It must choose `MaterialTheme.colorScheme.primary` only for the fully focused endpoint and `onSurfaceVariant` for the least-focused endpoint, using `lerp`/theme roles rather than `Color(0x…)`. Exact calm bounds are implementation-tuned but tests freeze monotonicity, endpoints, and clamping—not pixels.
4. Apply the profile only to the real lazy slot via `graphicsLayer` and text colour; retain three equal `slotWidth`s, two `outlineVariant` dividers, wrapping text, and `defaultMinSize(48.dp)`. No new central container, gradient, transform-style 3D, animation library, or inline `spring`/`tween`; scrolling remains Foundation physics. `GymMotion` is not needed because no state-to-state animation is introduced.
5. Keep existing cancellation ownership: real drag cancels a programmatic scroll; external input waits through a user gesture/fling; recentering/external selection remain silent. Focus calculation cannot call `onSelected` or haptics. The current `gymHaptics().tap()` remains exclusively in the changed user-settle path.
6. Accessibility and adaptive policy stay local: root state description and previous/next custom TalkBack actions are unchanged; centre stays selected/read-only, neighbours clickable. The selector derives width from its supplied constraints, therefore naturally keeps three equal slots on compact/medium/expanded hosts; verify its constrained layout and 2.0 font scale rather than add breakpoint logic.
7. Version is a shared build choke point and belongs to the same single implementation writer. Increment only `defaultConfig.versionCode` and patch `versionName`, preserving all test-version overrides and build configuration.

## Tasks

| ID | Owner | Dependencies | Exact files | Actions | Automated verification | Observable done condition | AC |
|---|---|---|---|---|---|---|---|
| T-028 | One implementation writer | None | `app/src/main/java/com/valerochka1337/valerochkagym/ui/analysis/body/MuscleSelector.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/analysis/body/MuscleSelectorTest.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/analysis/body/MuscleSelectorComposeTest.kt` | Add the pure geometry-to-focus profile and apply it only to lazy slot scale/alpha/text colour. Preserve the existing Foundation `LazyRow`, snap/recenter/cancellation state machine, dividers, semantics and UDF signature. Extend unit tests for clamped/monotonic profile endpoints and Compose tests for stronger settled centre, continuous geometry-derived profile, plus regression coverage for callback/haptic, cyclic fling, tap, external sync/restoration, 2x font scale, equal slots, 48dp targets and TalkBack. | `./gradlew :app:testDebugUnitTest --tests '*MuscleSelectorTest' --tests '*MuscleSelectorComposeTest'` | Both selected test classes pass; a centre profile is demonstrably stronger than its neighbours; all prior logical-selection and accessibility tests remain green. | 044–047 |
| T-029 | One implementation writer | T-028 | `app/build.gradle.kts` | Change only `versionCode = 17` to `18` and `versionName = "1.3.9"` to `"1.3.10"`; retain optional test-version override behavior. Inspect the final diff for forbidden module/dependency/data/VM/DI/nav/background edits. | `git diff --check`; `git diff -- app/build.gradle.kts gradle/libs.versions.toml app/src/main/java app/src/test/java` | Version is exactly `18 / 1.3.10`, diff has no whitespace error, no version-catalog/dependency change, and implementation scope is limited to T-028/T-029 files. | 048 |
| T-030 | Integration coordinator (read-only validation) | T-028, T-029 | No production file | Run final project gates once after the implementation diff is stable; record results in tracker. Do not request/produce/analyze screenshots. | `./gradlew :app:testDebugUnitTest`; then `./gradlew :app:assembleDebug` | Both commands exit 0 and tracker records exact results; otherwise task is blocked with the first failing command and cause. | 048 |

## File ownership and execution waves

| Files | Sole writer | Boundary |
|---|---|---|
| `MuscleSelector.kt`, `MuscleSelectorTest.kt`, `MuscleSelectorComposeTest.kt` | One implementation writer | All selector render math, UI behavior, and targeted regression tests are one atomic vertical slice. |
| `app/build.gradle.kts` | Same implementation writer | Shared version choke point; no other build/dependency edits. |
| Both `vibe/muscle-selector-carousel-polish-*` files | Plan owner | Planning/traceability only; implementation must update tracker evidence, not alter the frozen contract without recording a deviation. |

Wave 1: T-028. Wave 2: T-029 after selector tests pass. Wave 3: T-030 sequential final gates. No parallel implementation: all mutable production/test files belong to one writer.

## Quality gates

- Always: preserve single `:app`, UDF, existing Foundation APIs, handwritten fakes, no mocks/logs/dependency; trace every AC above.
- Relevant Compose gates: theme roles only; no hardcoded colour; no inline motion spec; semantic state/actions, 48dp targets, fontScale 2.0, stable list identity, no expensive composition work; validate constrained adaptive sizing and no navigation/back change.
- Relevant cancellation gate: retain the existing user-drag-over-programmatic-scroll behavior and changed-only haptic/callback tests.
- Conditional gates excluded with reason: Room/migration/schema, repository/domain/dispatcher flow, Hilt, permissions, manifest, WorkManager/service, release assembly, and chart render are untouched. No screenshot/render gate without explicit authorization.
- Final project gates (once after stable implementation): `./gradlew :app:testDebugUnitTest`, then `./gradlew :app:assembleDebug`.

## Risks, unresolved questions, rollback/data preservation

| Item | Mitigation / status |
|---|---|
| Neighbours may become too faint or scale too aggressively under a dynamic palette. | Clamp a readable minimum alpha/scale and test profile monotonicity; owner makes the optional on-device aesthetic call. |
| Offset geometry can be absent during first layout. | Use a neutral fallback profile until visible geometry exists; never affect logical selection. |
| Per-frame state reads could cause excess recomposition. | Derive the small visible-item profile only at the slot layer and use `graphicsLayer`; profile has no side effects. |
| Unresolved product choice | None blocking. Exact calm bounds are an implementation detail within PD-001, constrained by the tests and design system. |
| Rollback/data preservation | Revert only the selector visual/profile and version increment as one feature change. No persisted data, schema, saved virtual position, migration, or external contract exists to preserve or roll back. |

## Gate P self-check

Pass: AC-044…AC-048 each map to at least one task and command; contracts, ownership, cancellation, SSOT, accessibility, adaptive behavior, version and exclusions are frozen; the sole writer owns every mutable implementation file; relevant conditional gates only are included. No blocker remains. Recommended first implementation task: **T-028**.
