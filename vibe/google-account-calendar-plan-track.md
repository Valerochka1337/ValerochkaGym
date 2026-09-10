# Google account and Calendar connection — implementation tracker

## Уточнение поведения — 2026-09-11

По запросу владельца AC-003/AC-004 изменены: подключение из настроек всегда
открывает выбор Google-аккаунта, затем сразу запрашивает доступ для выбранного адреса.
Аккаунт входа в приложение не определяет выбор Calendar. После подключения доступна
кнопка «Сменить аккаунт» с тем же потоком. Предпочтительный адрес больше не показывается
и не используется основной кнопкой. Старое подключение сохраняется до проверки нового
токена; отмена, nonce-защита, восстановление операции и принадлежность событий сохранены.

Оптимизированный план: локальная правка Settings → регрессии выбора/отмены/смены →
один финальный полный прогон и debug APK. База — 076585f (текущая версия приложения),
ветка fix/calendar-account-picker, версия 1.3.38 (46). Бэкенд и Room не меняются.
Целевые SettingsRecoverySchedulingTest и SettingsScreenTest прошли.
Финальные testDebugUnitTest и assembleDebug: PASS (37 с); 1265 тестов,
0 failures, 0 errors, 1 skip. Реальный OAuth на телефоне в этой задаче не проверялся.


## Task status

| Task | Status | Owner | Dependencies | AC | Automated check |
|---|---|---|---|---|---|
| T-001 | completed | implementation writer | — | AC-001–AC-005, AC-007 | Google auth/account/identity targeted tests pass |
| T-002 | completed | implementation writer | T-001 | AC-003–AC-007 | Calendar/link/migration/PortableData targeted tests pass; schema 17 exported |
| T-003 | completed | implementation writer | T-001–T-002 | AC-004, AC-005, AC-007 | weekly/recovery connected-identity tests pass |
| T-004 | completed | implementation writer | T-003 | AC-003–AC-008 | Settings state/Compose tests, compile and Spotless pass; version 36 / 1.3.28 |
| T-005 | completed | tester + readonly Sol/high reviewer | T-004 | AC-001–AC-008 | independent GateT and Sol/high GateV PASS after final-write recheck; no remaining P0/P1/P2 |
| T-006 | completed | root session | T-005 | AC-001–AC-008 | full1083tests0failures/errors1skip PASS after legacy-fixture repair; assembleDebug PASS; version36/1.3.28 |

## AC traceability

| AC | Tasks | Evidence |
|---|---|---|
| AC-001 | T-001 | backend-accept ordering and no Calendar side-effect test |
| AC-002 | T-001 | password-flow identity invariance tests |
| AC-003 | T-001, T-002, T-004 | exact target/no-resolution/non-null-token grant, failure retention, transaction, recreation/cancel/stale/concurrent Settings tests |
| AC-004 | T-001, T-003, T-004 | picker-before-authorize, exact pending target, and A→B owner mismatch tests |
| AC-005 | T-001, T-003 | account+scope revoke, backend/local/remote retention, disconnect zero-API weekly/recovery tests |
| AC-006 | T-002 | real Room owned/unknown migration, quarantine, and off-wire metadata tests |
| AC-007 | T-001–T-004 | recreation plus missing/different/revoked connected-account and weekly-regression tests |
| AC-008 | T-004 | Compose semantics/state/target/font/adaptive and busy-gate tests |

## Deviations

None. Record any change to the frozen CAL-01 payload, identity boundary, migration predecessor, or
revoke semantics before implementation.

## Findings

- Current `google_email` is legacy weekly-schedule identity, not the preferred Calendar identity.
- Current one-off event IDs are unbound; migration must quarantine, not infer/adopt, their owner.
- Calendar authorization result has no selected-email identity; explicit picker precedes Other-account
  authorization. Existing weekly ownerEmail/journal semantics are already separate and frozen.
- Consolidated Gate P corrections: `google_email` is candidate-only; all weekly interactive/recovery
  gates use connected identity; a connection commits only after exact account-bound noninteractive
  token success (no resolution plus token). SavedState keeps only kind/normalized target/token/busy,
  reissues that target after recreation and rejects stale/concurrent replies. Migration tests live
  under `data/db`; the eight-column task table makes T-003 strictly precede T-004.
- Consolidated Gate V final-write repair: a Settings operation clears its SavedState nonce inside
  the same Settings DataStore edit that writes its verified identity, so a cancelled A has no final
  write after B. Weekly owner comparison and target persistence share one Settings DataStore edit:
  a mismatch preserves the journal. A successful owner-bound target edit is the operation's
  linearization point; later journal clearing has no remote side effect. If clearing fails, the
  journal remains and recovery under a different connected account fails that same conditional
  target commit and pauses.

## Command results

- T-001–T-004 targeted filters: 94 tests passed across Google auth, backend account,
  identity, Calendar repository, link DAO, 16→17 and 1→17 migrations, PortableData, weekly
  schedule/recovery, Settings SavedState and fontScale 2 Compose coverage.
- `./gradlew :app:testDebugUnitTest --tests "*CalendarRepositoryTest" :app:compileDebugKotlin
  --no-daemon`: passed after the final recoverable local-link failure handling.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew
  :app:compileDebugKotlin --no-daemon`: passed.
- `./gradlew spotlessCheck --no-daemon`: passed.
- Final T-004 repair with JDK 21: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew
  --no-daemon :app:testDebugUnitTest --tests "*GoogleAuthManagerTest" --tests
  "*AccountViewModelTest" --tests "*CalendarAccountIdentityTest" --tests
  "*CalendarRepositoryTest" --tests "*CalendarEventAccountLinkDaoTest" --tests
  "*Migration16To17Test" --tests "*Migration1To17Test" --tests "*PortableDataTest"
  --tests "*WeeklyScheduleRepositoryTest" --tests "*WeeklyScheduleRecoveryWorkerTest" --tests
  "*SettingsViewModelTest" --tests "*SettingsScreenTest" --tests
  "*SettingsRecoverySchedulingTest"`: 114 tests passed.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew --no-daemon
  :app:compileDebugKotlin spotlessCheck`: passed.
- Gate V final-write regression with JDK 21: `JAVA_HOME="$(/usr/libexec/java_home -v 21)"
  ./gradlew --no-daemon :app:testDebugUnitTest --tests "*CalendarAccountIdentityTest" --tests
  "*SettingsRecoverySchedulingTest" --tests "*WeeklyScheduleRepositoryTest"`: 46 tests passed.
- Gate V final quality with JDK 21: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew
  --no-daemon :app:compileDebugKotlin spotlessCheck`: passed.
- Full unit and debug assembly remain T-006 root gates by ownership.

## Residual risks

- Root final first run:1083tests/3failures/1skip; shared legacy fixture attempted Callback16 after creating Room17. Test-only repair reads actual PRAGMA version and removes v17 link objects before legacy reconstruction. Three targeted regressions and independent narrow review PASS. Final full rerun:1083tests/0failures/errors/1skip,8m22s (`/private/tmp/yarumo-calendar-final-unit-rerun.log`); `:app:assembleDebug` PASS,17s (`/private/tmp/yarumo-calendar-final-debug.log`). No production changes after accepted T/V. Final Gate T/V PASS, no remaining P0/P1/P2.

- External OAuth revocation/expiry is handled as unavailable linked work; it never authorizes another
  account or deletes a record.
- Calendar event creation before local transaction can orphan a remote event on local fault; do not
  infer its owner or issue cleanup under a different identity.

Gate P strict Sol/high: PASS after narrowed recheck. Prior connection retained until verified replacement; SavedState stores only opaque operation nonce, never credentials. Root restored explicit one version bump/compile/spotless ownership in T-004 after table consolidation.
