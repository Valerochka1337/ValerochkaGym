# Yarumo coach rebrand — implementation tracker

## Task status

| Task | Status | Owner | Dependencies | AC | Check |
|---|---|---|---|---|---|
| T-001 | pass | implementation writer | — | AC-002, AC-003, AC-005 | `*AppIconManagerTest`, `*LauncherBrandResourcesTest` — pass (8 tests) |
| T-002 | pass | implementation writer | T-001 | AC-001, AC-003, AC-005 | `*PostUpdateRelaunchTest`, `*AccountFormComposeTest` — pass (10 tests; account gate at 2× font) |
| T-003 | pass | implementation writer | T-001–T-002 | AC-001, AC-003, AC-004 | brand/identity `rg` audit — pass |
| T-004 | pass | implementation writer | T-001–T-003 | AC-001–AC-005 | target version 35/1.3.27; `:app:compileDebugKotlin` — pass |
| T-005 | pass | tester + readonly Sol/high reviewer | T-004 | AC-001–AC-005 | final independent T/V PASS; all findings closed |
| T-006 | pass | root session | T-005 | AC-001–AC-005 | full1041tests0failures/errors1skip; debugPASS; R8PASS; signedassembleReleaseblockedlocalkeystore; canonical34→35 upgrade+accessibilityPASS |

## AC traceability

| AC | Tasks | Evidence |
|---|---|---|
| AC-001 | T-002–T-003 | string/Compose/notification assertions and repository/docs brand audit |
| AC-002 | T-001 | alias/adaptive/round/monochrome resource tests and approved-source audit |
| AC-003 | T-001–T-004 | alias/manifest plus immutable identity/updater parser/version audit |
| AC-004 | T-003 | README/design-system/branding README retained-identity and external OAuth checklist review |
| AC-005 | T-001–T-006 | resource tests, accessibility host checks, APK metadata, available-device upgrade evidence |

## Deviations

Strict plan review corrections applied by root; Sol/high narrow recheck PASS. Record any compatibility invariant, unavailable release-signing/device, or
external OAuth-console status here.

- Gate I: source baseline rechecked after implementation. applicationId/namespace, both manifest
  authorities, `gym.db`, all three DataStore names, OAuth resource key/value hash, four aliases
  with their order/defaults, portable UUID prefixes, updater owner/repository/asset parser,
  `AppIconManager`, and `AccentColor` are unchanged. The approved 1254×1254 opaque PNG is copied
  byte-for-byte to `drawable-nodpi` (SHA-256
  `0eabd16bbfc8ba3f1edaa14ad25702f5beb0131eb71cb63e3085b2ee230b2431`).
- External Google OAuth consent-screen display name remains an owner manual action; no Android
  OAuth configuration was changed. Packaged APK metadata, release signing/R8, and non-clearing
  device upgrade stay with T-006.
- Strict V correction: the monochrome lower lobe is enclosed by the outer ring; its resource test
  parses the actual `pathData` and checks its bounds with stroke widths. Focused resource test and
  formatter pass; root repeats the final gate.

## Findings

- Final independent Gate T PASS: README installer copy and real AccountGate fontScale=2 semantics verified. Strict Sol/high Gate V narrow recheck PASS: malformed lower monochrome lobe corrected, actual path/stroke bounds regression added, design-system heading corrected. No remaining P0/P1/P2.

- Four aliases are coupled through `AccentColor.aliasName` and `AppIconManager`; names/order stay.
- Existing adaptive icon foregrounds are accent vectors, monochrome is a vector, and legacy density
  PNGs are retained but unreachable on supported minSdk 36.
- Updater/release convention and portable UUID namespaces deliberately retain `ValerochkaGym`.

## Command results

- Root final `testDebugUnitTest` with temporary `forkEvery=1`: PASS, 1041 tests / 166 suites / 0 failures / 0 errors / 1 skipped, 7m58s. Log `/private/tmp/yarumo-rebrand-final-fixed-tests.log`.
- Root `assembleDebug`: PASS (`/private/tmp/yarumo-rebrand-final-debug.log`). APK package unchanged, label Yarumo coach, version35/1.3.27, matching debug certificate.
- Root `assembleRelease` attempted: blocked by absent local release keystore, not code failure (`/private/tmp/yarumo-rebrand-final-release.log`). Separate `minifyReleaseWithR8`: PASS, 1m54s (`/private/tmp/yarumo-rebrand-final-r8.log`). Signed artifact/certificate continuity remains final GitHub CI gate.
- Emulator isolated user10 `YarumoReleaseCheck`: canonical34→35 `install -r` PASS; DB/WAL hashes identical before first launch; cold launch succeeds and process stays alive; accessibility XML confirms Yarumo coach and login. Original user0 restored; test profile retained for subsequent checks. No screenshots.
- Original user0 DB is an unsupported historical dev schema: version14 identity96f233e6f2229fd0f4509cd505e75dd9, missing canonical equipment tables and containing experimental health tables. Canonical14 identitya03c9f43c708a8e06223e6e697cea4a2 differs. Existing migration crash predates branding. Original data preserved; read-only backup `/private/tmp/yarumo-upgrade-existing.db`; no bespoke normalization/reset was performed or claimed successful.

- `./gradlew :app:testDebugUnitTest --tests '*AppIconManagerTest' --tests '*LauncherBrandResourcesTest' --tests '*PostUpdateRelaunchTest' --tests '*AccountFormComposeTest'` — PASS (17 tests).
- `./gradlew :app:compileDebugKotlin` — PASS.
- `./gradlew spotlessCheck` — PASS after `./gradlew spotlessApply` reformatted the two changed tests.
- `./gradlew :app:testDebugUnitTest --tests '*AccountFormComposeTest'` — PASS after the Gate T
  correction; `./gradlew spotlessCheck` — PASS.
- `./gradlew :app:testDebugUnitTest --tests '*LauncherBrandResourcesTest'` — PASS after the
  monochrome geometry correction; `./gradlew spotlessCheck` — PASS.

## Residual risks

- Root: первый полный прогон `/private/tmp/yarumo-rebrand-final-tests.log` остановлен вручную: тестовый JVM завис с высокой CPU на существующем `RoutineDetailScreenTest`, диагностический attach не ответил. Прогон не считается успешным. После исправления векторной геометрии повтор запущен с временным `forkEvery=1`, без исключения тестов, лог `/private/tmp/yarumo-rebrand-final-fixed-tests.log`.
- Root: APK установленной версии 27 и baseline 34 имеют одинаковый debug certificate SHA-256 `556d9f2035e16b63b985daaa6b23287750e0e98856dad02203ca01cc6ff6da83`. `adb install -r` baseline 34 успешен без очистки. `gym.db` и существующие DataStore/документы присутствуют; файл backend-session отсутствовал, поэтому сохранение действующей авторизации на этом эмуляторе не подтверждается.

- Launcher crop/themed rendering differs by OEM; adaptive safe inset, round/monochrome resource
  checks, and available-device validation cover supported evidence without screenshots.
- Google OAuth console display name is external; README checklist records it as manual, not shipped.

Gate P strict: PASS. Все P1/P2 по исходным ресурсам, геометрии, совместимости, R8 и откату закрыты.
