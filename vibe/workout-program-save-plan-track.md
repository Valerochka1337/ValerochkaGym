# Трекер: сохранение программы после тренировки (#45/#46)

Статус: только подготовка; код, тесты, Gradle, schema, build и версия не менялись.

| Task | Статус | Owner | Зависимости | AC | Evidence / done condition |
|---|---|---|---|---|---|
| T-001 Q-001 | done | Product owner + planner | Gate R | AC-007 | Выбран zero-completed UX; единственный открытый product choice. |
| T-002 Repository | pending | Один implementer | Frozen contract | AC-002–005,007 | `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests "*GymRepositoryImplTest" --tests "*RoutineDaoTest"`; fingerprint-before-write, child-only conflict/no write, atomicity/rollback/history/STANDARD. |
| T-003 Use cases | pending | Один implementer | T-002 | AC-002–007 | `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests "*SaveCompletedWorkoutAsRoutineUseCaseTest" --tests "*RoutineUpdateUseCaseTest"`. |
| T-004 Summary UI | pending | Один implementer | T-001,T-003 | AC-001,004–008 | `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests "*WorkoutSummaryViewModelTest" --tests "*WorkoutSummaryScreenTest"`. |
| T-005 Strict review | pending | Tester + read-only Sol/high reviewer | T-002–004 | AC-001–008 | Использовать valid evidence; JDK-21 test только для coverage gap/invalidated area; findings пакет зарегистрирован. |
| T-006 Fixes/version | pending | Один implementer, затем read-only reviewer + targeted-test owner | T-005 | AC-001–008 | Findings fixed; affected recheck + только нужный JDK-21 test после writer stop; P0/P1 нет; code/patch version поднята раз до final stable diff. |
| T-007 Final gates | pending | Final agent | T-006 | AC-001–008 | Последовательно `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest`, затем `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:assembleDebug`. |

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

## Command results

- Gradle не запускался: задача документационная.
- `git diff --check` для owned files прошёл до финального editorial update.
- main/origin/main/HEAD равны `cc590a4`; JDK 17/21 установлены, будущие Gradle commands закрепляют JDK 21.

## Residual risks

- Q-001 меняет видимое поведение и ждёт owner decision.
- SavedStateHandle не durable operation journal; arbitrary force-stop не покрыт.
- Source может измениться при открытом dialog: authoritative validation — transaction-time.

## Старт реализации по поручению владельца

Владелец разрешил автономно реализовать весь согласованный план, выполнять Git-публикацию и
проверить успешный релиз. Прежний статус «только подготовка» выше — история подготовки.
Q-001 разрешён исполнителем в рамках этой делегации: при нуле выполненных подходов «Готово»
выходит без сохранения/перезаписи программы; завершённая история остаётся. Это сохраняет
существующие данные и не навязывает пустую программу. Gate P допускает реализацию.
