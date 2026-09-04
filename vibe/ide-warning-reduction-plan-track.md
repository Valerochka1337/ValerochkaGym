# IDE warning reduction — tracker

| Task | Status | AC | Test / evidence |
| --- | --- | --- | --- |
| T-001 | implemented | AC-001, AC-002, AC-004 | Catalog now has the approved targets while Kotlin `2.3.20`/KSP `2.3.10` remain unchanged; wrapper is Gradle `9.6.0` with official bin SHA-256; normal app version is `23 / 1.3.15` and test override/signing logic is unchanged. T-003 runs `./gradlew --version` and `./gradlew :app:lintDebug`. |
| T-002 | implemented | AC-002, AC-003 | The three KDocs now use literal code text for `routine_id` / `surfaceContainerHigh`; executable serialization and Material color-role code is unchanged. T-003 runs `./gradlew spotlessCheck` and `./gradlew :app:lintDebug`. |
| T-003 | done | AC-005 | `lintDebug` and `:app:testDebugUnitTest` passed; debug APK was generated. Release signing inputs are unavailable locally. |

## AC-to-task-to-test traceability

| AC | Implementing task | Verification |
| --- | --- | --- |
| AC-001 | T-001 | `./gradlew --version`, `./gradlew :app:lintDebug` |
| AC-002 | T-001, T-002 | catalog/KDoc diff, `spotlessCheck`, `:app:lintDebug` |
| AC-003 | T-002 | KDoc diff, `spotlessCheck`, `:app:lintDebug` |
| AC-004 | T-001 | app-build diff, `:app:lintDebug` |
| AC-005 | T-003 | `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleRelease` or exact signing blocker |

## Deviations

- None at planning time. Preserve unrelated dirty code-quality/relaunch work; do not fold it into
  this feature or overwrite it.

## Findings

- The fixed KDocs use literal code text for the sheet column and Material color role; these remain
  documentation-only changes, not API changes.
- The normal app version is now `23 / 1.3.15`; release signing remains validated from environment
  variables or root `keystore.properties` by `validateSigningRelease`.

## Command results

- `./gradlew :app:lintDebug --stacktrace` — BUILD SUCCESSFUL.
- `./gradlew :app:testDebugUnitTest --stacktrace` — BUILD SUCCESSFUL.
- `./gradlew :app:assembleDebug --stacktrace` — debug APK generated.

## Residual risks

- Kotlin `2.3.20` and KSP `2.3.10` intentionally remain; lint and unit gates establish the pairing
  with AGP 9.4.0 for this project.
- `Muscle.CHEST`, Compose test-rule and WorkManager merger warnings need semantic migrations, so
  they remain outside this mechanical warning-reduction pass.
- If no signing inputs are configured, record Gradle’s exact `Release-подпись не настроена` failure
  and its missing input names; do not mark the release gate passed.
