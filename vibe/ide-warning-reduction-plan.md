# IDE warning reduction — plan

## Goal

Remove the approved IDE/build warnings by upgrading the safe build and library set, repairing three
unresolved KDoc links, and releasing the resulting compatible app as version `23 / 1.3.15`.

## Scope, non-goals, assumptions

**In scope:** the exact versions below; Gradle Wrapper 9.6.0 (including its official SHA-256);
the three KDoc link corrections; app version `22 / 1.3.14` → `23 / 1.3.15`; dependency-sensitive
build verification.

**Non-goals:** Kotlin `2.3.20`, KSP `2.3.10`, serialization API/import removal, changing the
Material tonal role, new dependencies, source/API refactors, Room/schema/migrations, DI scopes,
navigation destinations, workers/services, permissions, or UI redesign.

**Assumptions:** the approved versions are compatible as a set. Kotlin/KSP is deliberately held;
its unverified pairing with AGP 9.4.0 remains an explicit residual warning unless Gradle proves
otherwise. Existing dirty code-quality/relaunch work is out of scope and must be preserved.

## Acceptance criteria

- **AC-001:** catalog and wrapper resolve the approved targets: AGP 9.4.0; Gradle 9.6.0; Compose
  BOM 2026.08.00; Material3/adaptive 1.5.0-alpha27; Activity 1.13.0; Navigation 2.10.0;
  Lifecycle 2.11.0; Hilt 2.60.1; Play auth 22.0.0; Exif 1.4.2; OkHttp 5.5.0; Robolectric 4.16.1.
- **AC-002:** Kotlin 2.3.20 and KSP 2.3.10 stay unchanged, `kotlin.android` and kapt remain absent,
  and no serialization import/API or Material `surfaceContainerHigh` role is removed/changed.
- **AC-003:** KDoc renders without unresolved links for `routine_id` in `RoutineRowMapper` and
  `surfaceContainerHigh` in `CircleIconButton` and `GymCard`, while their documented meaning stays
  unchanged.
- **AC-004:** `defaultConfig` becomes versionCode 23 and versionName 1.3.15, preserving the existing
  optional test-version override and release-signing logic.
- **AC-005:** formatting/lint, unit tests, debug assembly, and release assembly all pass; if release
  cannot start due to unavailable signing inputs, the exact Gradle signing message and missing input
  names are recorded instead.

## Current → target data/execution flow

`libs.versions.toml` → root/app plugin and dependency aliases → AGP/KSP/Compose/Hilt/AndroidX
resolution → existing Room → Flow → ViewModel → Compose and worker/service flows. The target has
the same ownership, immutable UI state/events, repository/domain boundaries, injected dispatcher
and cancellation ownership, Hilt scopes, navigation/state restoration, Room data/schema, background
work, and permission behavior. Only resolved artifact versions and KDoc markup change; no loading,
empty, error, content, accessibility, or adaptive-layout state is modified.

## Architectural decisions and frozen contracts

| Decision | Frozen contract |
| --- | --- |
| Build ownership | One writer owns the version catalog, wrapper, and app version as shared build choke points. Versions stay only in `gradle/libs.versions.toml`; AGP uses built-in Kotlin, KSP only. |
| Compatibility | Use only the approved target list; retain Kotlin/KSP. Do not “fix” their pairing by opportunistic upgrades. |
| UI contracts | `CircleIconButton` and `GymCard` retain `MaterialTheme.colorScheme.surfaceContainerHigh`; KDoc uses literal/code text for non-symbol role/column names rather than unresolved links. No UI visual/accessibility/adaptive change. |
| Serialization | Keep `kotlinx.serialization` import/API and its existing R8 keep-rule rationale intact. |
| Persistence/runtime | No entity, DAO, transaction, migration/schema, repository, ViewModel state/event, dispatcher, Hilt binding/scope, route, SavedStateHandle, worker/service, manifest, or permission change. Room SSOT and all cancellation ownership remain as-is. |

## Tasks

| ID | Exact files / owner | Depends | Actions | Automated verification | Done condition | AC |
| --- | --- | --- | --- | --- | --- | --- |
| T-001 | `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle.kts` / **implementation writer** | — | Set every approved catalog value; retain Kotlin/KSP; update wrapper URL and official 9.6.0 SHA-256; set 23/1.3.15 only in normal defaultConfig. | `./gradlew --version`; `./gradlew :app:lintDebug` | Resolved runtime reports Gradle 9.6.0; files contain exactly the frozen values; no duplicate/moved version or signing-override regression. | AC-001, AC-002, AC-004 |
| T-002 | `app/src/main/java/com/valerochka1337/valerochkagym/domain/RoutineRowMapper.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/components/CircleIconButton.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/components/GymCard.kt` / **implementation writer** | T-001 | Replace only unresolved KDoc links with literal/code references: `routine_id` and `surfaceContainerHigh`; keep the sheet-column and Material tonal-role prose and all executable code unchanged. | `./gradlew spotlessCheck`; `./gradlew :app:lintDebug` | Three KDocs no longer use unresolved symbol links and component code still selects `surfaceContainerHigh`. | AC-002, AC-003 |
| T-003 | `vibe/ide-warning-reduction-plan-track.md` / **implementation writer** | T-001, T-002 | Run final gates sequentially; record output, signing result/blocker, deviations and residual Kotlin/KSP risk. Do not alter unrelated dirty files. | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug`; `./gradlew :app:assembleRelease` | All available gates pass; otherwise tracker contains the exact release-signing failure and missing configuration names. | AC-005 |

## File ownership and execution waves

| Owner | Exclusive files |
| --- | --- |
| implementation writer | `gradle/libs.versions.toml`; `gradle/wrapper/gradle-wrapper.properties`; `app/build.gradle.kts`; three listed KDoc Kotlin files; this feature’s tracker |

| Wave | Tasks | Rule |
| --- | --- | --- |
| 1 | T-001, T-002 | One writer; T-002 can follow frozen contracts without changing behavior. |
| 2 | T-003 | Start only after the stable implementation diff; run Gradle commands sequentially in this shared checkout. |

## Quality gates

- Required dependency/build gates: `spotlessCheck`, `:app:lintDebug`, then final
  `:app:testDebugUnitTest` and `:app:assembleDebug` once on the stable diff.
- Conditional release gate (dependency/R8/serialization-sensitive): `:app:assembleRelease` when
  signing inputs are available. Otherwise record exactly the `validateSigningRelease` message,
  including missing `RELEASE_KEYSTORE_FILE / storeFile`, `RELEASE_KEYSTORE_PASSWORD / storePassword`,
  `RELEASE_KEY_ALIAS / keyAlias`, and/or `RELEASE_KEY_PASSWORD / keyPassword`.
- Not applicable: Room migration/schema tests, worker/service/permission tests, navigation and
  adaptive/font-scale accessibility tests, chart render snapshots, or new unit tests—no behavior
  at those boundaries changes.

## Risks, questions, rollback/data preservation

- **Residual risk:** AGP 9.4.0 with held Kotlin 2.3.20/KSP 2.3.10 has not been separately verified;
  compilation is the acceptance evidence, not a promise of a supported pairing.
- **Release risk:** local signing material may be unavailable; this blocks only the signed release
  gate, never silently downgrades it.
- **Rollback:** restore only the catalog/wrapper/app-version and three KDoc edits together. No Room
  schema/data or durable state changes, so no migration or user-data recovery path is needed.
- **Unresolved product questions:** none; the approved brief freezes scope.

## Gate P self-check

Every AC maps to T-001–T-003 and executable verification; shared build files have one owner;
version/KDoc/no-behavior contracts are frozen before implementation; only dependency/release
conditional gates are included. **Gate P: pass.**
