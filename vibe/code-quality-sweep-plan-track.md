# Code quality sweep — трекер

| Task | Status | AC | Evidence |
| --- | --- | --- | --- |
| T-001 | done | AC-001, AC-002 | Spotless 8.10.1 + ktfmt 0.61 configured; `spotlessApply` and `spotlessCheck` pass. |
| T-002 | done | AC-003, AC-004 | Guarded `spotlessCheck` added after Gradle setup; existing PR classifier/path policy preserved. |
| T-003 | done | AC-005 | Bottom sheet, CredentialManager and UseKtx warnings fixed; `lintDebug` passes. Version is 22 / 1.3.14. |
| T-004 | done | AC-006 | Unit tests pass; debug APK generated; review has no finding in this task's scope. |

## Commands

- `./gradlew spotlessApply --stacktrace` — passed.
- `./gradlew spotlessCheck --stacktrace` — passed.
- `./gradlew :app:lintDebug --stacktrace` — passed.
- `./gradlew :app:testDebugUnitTest --stacktrace` — passed.
- `./gradlew :app:assembleDebug --stacktrace` — APK generated at
  `app/build/outputs/apk/debug/app-debug.apk`.

## Deviations and residual risks

- Independent untracked post-update-relaunch production, test, manifest and plan files appeared
  during the task. They are not part of this feature and were preserved; the project-wide formatter
  may have mechanically formatted their Kotlin source because `app/src/**/*.kt` is intentionally
  in scope.
