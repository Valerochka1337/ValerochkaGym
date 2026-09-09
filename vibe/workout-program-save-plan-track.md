# Трекер: сохранение программы после тренировки (#45/#46)

Статус: реализовано и проверено локально. Версия 30 / 1.3.22; Room schema не менялась.

| Task | Статус | Owner | Зависимости | AC | Evidence / done condition |
|---|---|---|---|---|---|
| T-001 Q-001 | done | Product owner + planner | Gate R | AC-007 | Выбран zero-completed UX; единственный открытый product choice. |
| T-002 Repository | done | Один implementer | Frozen contract | AC-002–005,007 | Typed create/replace transaction, canonical expected/predicted aggregate fingerprints, replay/no-write and child-only conflict covered by `GymRepositoryImplTest`; targeted JDK-21 gate passed. |
| T-003 Use cases | done | Один implementer | T-002 | AC-002–007 | Done-set mapping, empty-target guard, typed outcomes, cancellation and isolated post-commit scheduler covered by `SaveCompletedWorkoutAsRoutineUseCaseTest` and `RoutineUpdateUseCaseTest`; targeted JDK-21 gate passed. |
| T-004 Summary UI | done | Один implementer | T-001,T-003 | AC-001,004–008 | Done now opens choice before any write; zero done exits normally; choice/create/replace, restore draft and touch-target screen coverage pass in `WorkoutSummaryViewModelTest`/`WorkoutSummaryScreenTest`. |
| T-005 Strict review | done | Tester + read-only Sol/high reviewer | T-002–004 | AC-001–008 | Finding-only recheck PASS; все P0/P1/P2 implementation-review закрыты; layered coverage limitation записано ниже. |
| T-006 Fixes/version | done | Один implementer, затем read-only reviewer + targeted-test owner | T-005 | AC-001–008 | Consolidated P1/P2 fixed: restored replace uses frozen context, Done exit is one-shot, stale prepare is epoch-gated, choice remains actionable on preflight failure and wraps actions at large font. Version already 30 / 1.3.22. |
| T-007 Final gates | done | Root | T-006 | AC-001–008 | Full unit: 985 tests, 0 failures/errors, 1 skipped, 1m07; затем assembleDebug PASS 42s. Mac worker isolation и точные команды ниже. |

## AC → task → test traceability

| AC | Tasks | Evidence |
|---|---|---|
| AC-001 | T-004 | `WorkoutSummaryViewModelTest`, `WorkoutSummaryScreenTest`: choice до записи, dismiss/Back без изменения. |
| AC-002 | T-002,T-003 | `GymRepositoryImplTest`, `SaveCompletedWorkoutAsRoutineUseCaseTest`: fresh identity, null rest/no gyms, source unchanged, 4→3. |
| AC-003 | T-002,T-003 | `GymRepositoryImplTest`, `RoutineUpdateUseCaseTest`: atomic 4→3, ID/syncId/rest/gyms/history; child-only mutation при том же parent timestamp → Conflict/no write. |
| AC-004 | T-002,T-004 | `GymRepositoryImplTest`, `WorkoutSummaryViewModelTest`: STANDARD/deleted/unavailable/missing UI+transaction guard. |
| AC-005 | T-002–004 | Repository/use-case/VM: typed outcomes, cancellation, post-commit scheduler. |
| AC-006 | T-002–004 | Repository replay/no-write conflict; use-case/VM repeated confirm + saved-state recreation, no force-stop promise. |
| AC-007 | T-001–004 | Approved Q-001 screen test + no empty create/clearing tests. |
| AC-008 | T-004,T-005 | Screen semantics/state; review target/font/adaptive/insets/Back. |

## Deviations

Нет. Schema/migration/navigation/Hilt/worker/dependency/manifest change до T-006 требует amendment и gates.

## Findings

- Strict plan review / recheck 10.09.2026: **conditional pass**. Устранены три замечания:
  expectedSourceFingerprint полного aggregate и сохранение gyms; проходимый fix loop с recheck;
  повтор targeted evidence только при gap/инвалидации. Это ревью плана, не подтверждение тестов/кода.
- Gate R complete для #45/#46.
- Q-001 открыт, блокирует только свою UI-ветку.
- Replace replay frozen: source ID/syncId + `expectedUpdatedAt` + canonical expected/predicted full fingerprints; predicted target → `Saved(replayedWithoutWrite)`, иначе expected fingerprint + version → write, иначе `Conflict`.
- Consolidated implementation review (10.09.2026): **Gate V fail** with 3 P1 and 2 P2. T-006 resolves all findings: persisted replace context now rebuilds only child rows and preserves expected/predicted fingerprints; missing context fails closed; a `finishAfterSaving` entrypoint emits one completion after create/replace or explicit «Не сохранять»; stale replacement preparation is epoch-gated; failed preflight keeps choice and Create available; choice actions use a vertical accessible layout. Affected recheck remains required.

## Command results

- Gradle не запускался: задача документационная.
- `git diff --check` для owned files прошёл до финального editorial update.
- main/origin/main/HEAD равны `cc590a4`; JDK 17/21 установлены, будущие Gradle commands закрепляют JDK 21.
- Implementer Gate I (10.09.2026): `JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home" ./gradlew --no-daemon :app:compileDebugKotlin --console=plain` — **passed** (27s).
- Implementer targeted repository/use-case gate: `JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home" ./gradlew --no-daemon :app:testDebugUnitTest --tests "*SaveCompletedWorkoutAsRoutineUseCaseTest" --tests "*RoutineUpdateUseCaseTest" --tests "*GymRepositoryImplTest" --console=plain` — **passed** (14s).
- Implementer targeted summary/DAO gate: `JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home" ./gradlew --no-daemon :app:testDebugUnitTest --tests "*WorkoutSummaryViewModelTest" --tests "*WorkoutSummaryScreenTest" --tests "*RoutineDaoTest" --console=plain` — **passed** (10s).
- T-006 targeted recheck: `JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home" ./gradlew --no-daemon :app:testDebugUnitTest --tests "*WorkoutSummaryViewModelTest" --tests "*WorkoutSummaryScreenTest" --tests "*RoutineUpdateUseCaseTest" --console=plain` — **passed** (13s). New regression evidence: `recreated replacement keeps its original fingerprint after a child-only edit`; `skipping a done choice clears it and emits a single completion event`; `saving a new routine from done choice emits completion instead of a snackbar event`; `stale replace preparation cannot reopen a choice after choosing create`; `save choice keeps every action accessible at large font scale`.
- T-006 formatting recheck: `JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home" ./gradlew --no-daemon spotlessCheck --console=plain` — **passed** (3s).
- Full-suite compatibility follow-up: `JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home" ./gradlew --no-daemon :app:testDebugUnitTest --tests "*WorkoutDetailViewModelTest" --console=plain` — **passed** (10s). Three handwritten history fakes now map typed completed-workout create commands through their existing save behavior; all original history assertions pass.
- Full-suite isolation follow-up: `JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home" ./gradlew --no-daemon :app:testDebugUnitTest --tests "*ExerciseEditorSheetComposeTest" --console=plain` — **passed** (10s) in a fresh worker. The Compose editor test publishes and restores its equipment catalog fixture, removing its dependency on test execution order.
- Formatting follow-up: `JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home" ./gradlew --no-daemon spotlessCheck --console=plain` — **passed** (4s).

## Residual risks

- Q-001 закрыт решением D-001 (см. запуск ниже).
- SavedStateHandle не durable operation journal; arbitrary force-stop не покрыт.
- Source может измениться при открытом dialog: authoritative validation — transaction-time.

## Root final gate — текущая диагностика

Диагностика завершена: весь набор прошёл без исключения тестов; исходная причина зависания
общего JVM не установлена окончательно, изоляция каждых 16 классов устраняет воспроизведение.

- Finding-only Gate V recheck: PASS после root-исправления P2 двойного skip. Проверяется
  showSaveChoice до synchronous clear; тест вызывает skip дважды и ожидает один done event.
- Gate T recheck: P1 restore закрыт. Остаточный coverage P2 — нет отдельного end-to-end
  restored replace после успешной записи; replay в Room и восстановление immutable контекста
  проверены отдельными регрессиями. Root принимает это ограничение layered coverage.
- Первый полный `testDebugUnitTest` остановлен root через 7m49s: worker CPU100%, не отвечал
  на jcmd/SIGQUIT, stack sample показывает coroutine test runner. Это **не passed**.
- Повтор с временным init-script, печатающим имена тестов, локализовал остановку в
  `ActiveWorkoutRepositoryTest.toggleSetCompleted flips the completed flag` после NetworkModuleTest.
  Worker остановлен; причина исследуется отдельным tester без изменения production-кода.
  Журнал: `/private/tmp/yarumo-program-save-full-tests.log`; native sample:
  `/private/tmp/yarumo-program-save-test-sample.txt`.
- Третий полный прогон с forkEvery=16 завершился и выявил 5 legacy fake failures в
  WorkoutDetailViewModelTest и отсутствующий catalog fixture в ExerciseEditorSheetComposeTest.
  Фейки адаптированы к typed create, fixture явно устанавливается и восстанавливается;
  исходные assertions сохранены. Оба targeted класса прошли, spotlessCheck прошёл.
- Финальный full gate: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew --no-daemon :app:testDebugUnitTest --init-script /private/tmp/yarumo-test-isolation.gradle --console=plain`
  — PASS, 985 tests/160 suites, 0 failures/errors, 1 skipped, 1m07s. Временный init задаёт
  `forkEvery=16`, `maxParallelForks=1` и печать имён; production/build-конфигурация не меняется.
- Затем `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew --no-daemon :app:assembleDebug --console=plain`
  — PASS, 42s. APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Перед commit выполнен fetch origin: main по-прежнему cc590a4, 29 / 1.3.21;
  текущая версия 30 / 1.3.22 выше целевой и увеличена ровно один раз.

## Старт реализации по поручению владельца

Владелец разрешил автономно реализовать весь согласованный план, выполнять Git-публикацию и
проверить успешный релиз. Прежний статус «только подготовка» выше — история подготовки.
Q-001 разрешён исполнителем в рамках этой делегации: при нуле выполненных подходов «Готово»
выходит без сохранения/перезаписи программы; завершённая история остаётся. Это сохраняет
существующие данные и не навязывает пустую программу. Gate P допускает реализацию.
