# Трекер: безопасная замена недельного расписания

План: `vibe/weekly-schedule-safe-replacement-plan.md`
Статусы: `pending | in_progress | done | blocked`

## Tasks

| Task | Статус | Owner | Dependencies | AC | Evidence / done condition |
|---|---|---|---|---|---|
| T-001 Wire/journal/auth contracts | done | android_feature_implementer | — | AC-003, AC-005, AC-009, AC-010 | DTO omits nullable id; journal validation/typed read; 32-hex IDs; account-bound token/consent request tests pass |
| T-002 Durable repository state machine | done | android_feature_implementer | T-001 | AC-001, AC-002, AC-003, AC-004, AC-005, AC-006, AC-008, AC-010 | Fault/recreation/owner/unreadable tests pass; old active preserved; cancellation replays same ID as 409 |
| T-003 DataStore + WorkManager + backup recovery | done | android_feature_implementer | T-002 | AC-002, AC-004, AC-005, AC-006, AC-007, AC-010 | Dedicated qualified DataStore; unconstrained APPEND_OR_REPLACE worker chain can finish local markers offline; startup enqueue; journal-only exclusions |
| T-004 UI busy/messages | done | android_feature_implementer | T-002, T-003 | AC-001, AC-007, AC-008, AC-009, AC-010 | Shared ViewModel gate tested under suspension; successful Google consent wakes paused recovery; both actions consume the same busy state and repository messages pass through |
| T-005 Architecture + version 11/1.3.3 | done | android_feature_implementer | T-001..T-004 | AC-005 | ARCHITECTURE updated; base version exactly 11/1.3.3; testVersion overrides untouched |
| T-006 Full gates, review, Git/PR | done | tester + reviewer + main agent | T-005 | AC-001..AC-010 | 717/717 unit tests + debug passed; Gate T/V passed без P0/P1/P2; release blocked только отсутствующими signing inputs; version 11/1.3.3 fresh vs main 10/1.3.2; commit/push/PR выполняет main agent |

## AC -> task -> test traceability

| AC | Tasks | Автоматическое доказательство | Статус |
|---|---|---|---|
| AC-001 insert failure сохраняет old local/remote | T-002, T-004, T-006 | `WeeklyScheduleRepositoryTest`: insert IOException; old active unchanged; old IDs absent in deletes; distinct failure message | done |
| AC-002 failed-new cleanup durable | T-002, T-003, T-006 | middle-insert test proves attempted-only cleanup; failed cleanup survives recreation; worker recovery does not append successors | done |
| AC-003 old delete only after all new confirmed | T-001, T-002, T-006 | call-order test; pending removed only after insert return/409; client 32-hex request IDs | done |
| AC-004 delete-old retry without inserts/duplicates | T-002, T-003, T-006 | delete 500 and applied-then-timeout -> 404 recreation; insert count unchanged | done |
| AC-005 process-death recovery | T-001, T-002, T-003, T-005, T-006 | caught insert IOException cleanup; cancellation -> same-ID 409; local-only CREATE/CLEANUP/DELETE markers bypass auth; terminal windows before/after active commit | done |
| AC-006 404/410 delete success | T-002, T-003, T-006 | executable Response 404/410 and HttpException 404 tests; 401/403/429/IOException preserve pending | done |
| AC-007 shared busy single-flight | T-003, T-004, T-006 | Save/Save, Save/Clear, Clear/Clear and reset tests; UI-vs-worker Mutex test; worker Retry/Paused never self-enqueues | done |
| AC-008 distinct UI result messages | T-002, T-004, T-006 | save/clear success, consent and repository-specific old-preserved/deferred/account messages | done |
| AC-009 ad-hoc unaffected | T-001, T-004, T-006 | `CalendarEventDtoTest` omits `id`+`recurrence`; `CalendarRepositoryTest` and CalendarViewModel regression pass | done |
| AC-010 safe resumable clear | T-001, T-002, T-003, T-004, T-006 | legacy owner adoption, mismatch/no-email gate, account-bound token, empty immediate success and no insert | done |

## Frozen decisions

| ID | Решение | Состояние |
|---|---|---|
| D-001 | Active SSOT остаётся ключом `weekly_schedule` существующего settings DataStore | frozen |
| D-002 | Journal — отдельный machine-local Preferences DataStore file `weekly_schedule_operations.preferences_pb`, один JSON aggregate | frozen |
| D-003 | Межфайловой транзакции нет: journal empty-pending marker -> atomic active-file edit -> journal clear; terminal replay idempotent | frozen |
| D-004 | Prepared request snapshot + 32 lowercase hex client event ID | frozen |
| D-005 | `CalendarEventDto.id` nullable/omitted; ad-hoc не передаёт ID | frozen |
| D-006 | State machine: CREATE_NEW -> CLEANUP_NEW или DELETE_OLD | frozen |
| D-007 | Insert 409 same ID; delete 2xx/404/410 — confirmed success | frozen |
| D-008 | Pending delete ID убирается только после confirmed success | frozen |
| D-009 | App-start + unique unconstrained WorkManager APPEND_OR_REPLACE/backoff chain: local terminal markers finish offline, network steps return Retry; REPLACE only compile fallback + test | frozen |
| D-010 | Singleton repository Mutex сериализует UI/worker; CancellationException propagates | frozen |
| D-011 | `WeeklySchedule.ownerEmail`; atomic legacy adopt; separate `AccountBoundGoogleAuth` via `AuthorizationRequest.setAccount`; mismatch/sign-out pause | frozen |
| D-012 | Clear использует DELETE_OLD и commit empty только после remote completion | frozen |
| D-013 | Нет Room/dependency/permission/navigation/chart changes | frozen |
| D-014 | Version bump ровно один: 10/1.3.2 -> 11/1.3.3, если main не обгонит ветку | frozen |
| D-015 | Commit/push/PR делает только main agent после gates; PR closes #8 | frozen |
| D-016 | Before insert add attempted ID to cleanup set but keep pending; only 2xx/409 removes pending; unattempted never cleanup | frozen |
| D-017 | Journal reads are typed Absent/Present/Unreadable; JSON/IO/corruption fail-closed | frozen |
| D-018 | Journal only excluded from legacy/cloud/device-transfer backup; active settings remains included | frozen |
| D-019 | DELETE_OLD empty-pending terminal commit/clear precedes and bypasses email/token gates; gates apply only to future Calendar API steps | frozen |
| D-020 | Interactive authorization/consent request is account-bound to persisted/explicit expected owner; request account tested | frozen |

## Command results

| Когда | Команда | Результат | Примечание |
|---|---|---|---|
| planning | Gradle не запускался | not_run | Изменены только plan/tracker; по AGENTS.md сборка не нужна |
| planning | `javap ... androidx.work.ExistingWorkPolicy` из локального `work-runtime:2.11.2` AAR | passed | enum содержит `APPEND_OR_REPLACE`; REPLACE fallback не планируется штатно |
| implementation | `./gradlew :app:testDebugUnitTest --tests "*CalendarEventDtoTest" --tests "*WeeklyScheduleOperationTest" --tests "*GoogleAuthManagerTest"` | passed | T-001 |
| implementation | `./gradlew :app:testDebugUnitTest --tests "*WeeklyScheduleRepositoryTest"` | passed | T-002, including insert IOException cleanup, delete retry, cancellation/same-ID 409, adoption/mismatch/unreadable |
| implementation | `./gradlew :app:testDebugUnitTest --tests "*WeeklyScheduleRecoveryWorkerTest" --tests "*WeeklyScheduleRecoverySchedulerTest" --tests "*GymApplicationTest" --tests "*WeeklyScheduleBackupRulesTest"` | passed | T-003 |
| implementation | `./gradlew :app:testDebugUnitTest --tests "*CalendarViewModelTest" --tests "*ScheduleEditorScreenTest"` | passed | T-004 |
| implementation | `./gradlew :app:testDebugUnitTest --tests "*CalendarRepositoryTest" --tests "*CalendarEventDtoTest"` | passed | AC-009 ad-hoc regression |
| implementation | `./gradlew :app:compileDebugKotlin` | passed | Production/Hilt compilation |
| implementation-final | `./gradlew :app:testDebugUnitTest --tests "*CalendarEventDtoTest" --tests "*WeeklyScheduleOperationTest" --tests "*GoogleAuthManagerTest" --tests "*WeeklyScheduleRepositoryTest" --tests "*WeeklyScheduleRecoveryWorkerTest" --tests "*WeeklyScheduleRecoverySchedulerTest" --tests "*GymApplicationTest" --tests "*WeeklyScheduleBackupRulesTest" --tests "*CalendarViewModelTest" --tests "*ScheduleEditorScreenTest" --tests "*CalendarRepositoryTest"` | passed | Combined targeted T-001..T-004 + ad-hoc regression after final changes |
| fix-pass-1 | `./gradlew :app:testDebugUnitTest --tests "*WeeklyScheduleRepositoryTest"` | passed | Local marker normalization, no worker self-enqueue, DataStore failures, expanded fault/recreation/status/mutex coverage |
| fix-pass-1 | `./gradlew :app:testDebugUnitTest --tests "*WeeklyScheduleRepositoryTest" --tests "*WeeklyScheduleOperationTest" --tests "*WeeklyScheduleRecoveryWorkerTest" --tests "*WeeklyScheduleRecoverySchedulerTest" --tests "*CalendarViewModelTest" --tests "*ScheduleEditorScreenTest"` | passed | Focused repository/worker/UI regression after P1/P2/P3 fixes |
| fix-pass-1 | `./gradlew :app:testDebugUnitTest --tests "*CalendarViewModelTest" --tests "*ScheduleEditorScreenTest"` | passed | Save/Save, Save/Clear, Clear/Clear, failure/cancellation busy reset and Compose enabled semantics |
| fix-pass-1-final | `./gradlew :app:testDebugUnitTest --tests "*WeeklyScheduleRepositoryTest" --tests "*WeeklyScheduleOperationTest" --tests "*WeeklyScheduleRecoveryWorkerTest" --tests "*WeeklyScheduleRecoverySchedulerTest" --tests "*CalendarViewModelTest" --tests "*ScheduleEditorScreenTest" --tests "*GoogleAuthManagerTest" --tests "*CalendarEventDtoTest" --tests "*CalendarRepositoryTest"` | passed | Combined repository/worker/UI/auth/DTO/ad-hoc regression after all pass-1 fixes |
| fix-pass-1-final | `./gradlew :app:compileDebugKotlin` | passed | Production/Hilt compile after pass-1 fixes |
| final Gate T | `./gradlew :app:testDebugUnitTest` | passed | 717 tests, 0 failures/errors/skipped |
| final Gate T | `./gradlew :app:assembleDebug` | passed | debug APK собран |
| final Gate T | `./gradlew :app:assembleRelease` | blocked | `:app:validateSigningRelease`: отсутствуют `RELEASE_KEYSTORE_FILE`, store/key passwords и alias; `kspReleaseKotlin` прошёл, `compileReleaseKotlin` и R8/minify не достигнуты |
| final Gate T/V | `git diff --check` | passed | whitespace errors отсутствуют |
| final version freshness | `git fetch ValerochkaGym main`; compare `ValerochkaGym/main` | passed | remote main 10/1.3.2, ветка 11/1.3.3; база не обогнала feature branch |
| PR review follow-up | `./gradlew :app:testDebugUnitTest --tests "*WeeklyScheduleRecoverySchedulerTest" --tests "*WeeklyScheduleRepositoryTest" --tests "*SettingsRecoverySchedulingTest" --tests "*SettingsViewModelTest"` | passed | offline terminal scheduling и NeedsConsent -> Granted wake-up |
| PR review follow-up | `./gradlew :app:testDebugUnitTest` | passed | полный unit-регрессионный прогон после inline-review fixes |
| PR review follow-up | `./gradlew :app:assembleDebug` | passed | Hilt/ViewModel и WorkManager wiring собраны после fixes |
| PR review follow-up | `./gradlew :app:assembleRelease` | blocked | тот же внешний blocker `:app:validateSigningRelease`: release signing inputs отсутствуют; `kspReleaseKotlin` прошёл |

## Findings

| ID | Severity | Source | Finding | Resolution | Status |
|---|---|---|---|---|---|
| F-001 | P1 | Gate R | Текущий save удаляет old до новых insert и при сбое сохраняет частичный new как active | Заменён durable two-phase state machine по T-002 | resolved |
| F-002 | P1 | Gate R | Между API и DataStore checkpoint возможны orphan/lost IDs | Preassigned ID и durable attempted checkpoint до API; 409/404/410 idempotency | resolved |
| F-003 | P1 | Gate R | Нет process-death recovery и единого writer для UI/worker | Journal + unique worker + singleton Mutex по T-002/T-003 | resolved |
| F-004 | P2 | Gate R | Save/Clear можно нажать параллельно, UI не показывает общий busy | Shared ViewModel gate и disabled actions по T-004 | resolved |
| F-005 | P2 | Gate R | Текущие сообщения не различают old-preserved/deferred cleanup/delete | Typed state/result mapping и разные hardcoded сообщения по T-002/T-004 | resolved |
| F-006 | P1 | Plan review | Pre-insert перенос ID из pending ошибочно означал confirmation и cleanup мог спутать unattempted | D-016: pending сохраняется до 2xx/409, отдельный attempted cleanup set; crash-before-API test в T-002 | resolved_in_plan |
| F-007 | P1 | Plan review | `KEEP` теряет wakeup между last journal read и worker finish | D-009: WorkManager 2.11.2 `APPEND_OR_REPLACE`; adversarial successor test; compile-only REPLACE fallback с cancellation test | resolved_in_plan |
| F-008 | P1 | Plan review | Persisted email check не доказывает account token и имеет race; legacy active ownerless | D-011/D-020: nullable owner, atomic heuristic adoption, account-bound token+consent; legacy/mismatch/sign-out/race tests; historical-owner uncertainty accepted below | resolved_with_residual |
| F-009 | P1 | Plan review | Простые fakes не моделируют ambiguous IOException vs crash/cancellation и terminal windows | Stateful remote fake: caught insert IOException -> CLEANUP_NEW; crash/cancel after apply -> CREATE_NEW/409; delete timeout -> 404; no-auth terminal replay | resolved_in_plan |
| F-010 | P2 | Plan review | Нет app-start/HiltWorkerFactory integration evidence | Robolectric `GymApplicationTest` в T-003 | resolved_in_plan |
| F-011 | P2 | Plan review | Busy доказан только ViewModel, не semantics обеих кнопок | Robolectric Compose `ScheduleEditorScreenTest` в T-004 | resolved_in_plan |
| F-012 | P2 | Plan review | Journal corruption/IO мог быть принят за absence | Typed Absent/Present/Unreadable + JSON/IOException/corruption fail-closed tests T-001/T-002 | resolved_in_plan |
| F-013 | P2 | Plan review | Backup stale journal и ложное обещание cross-DataStore atomicity | Dedicated journal file, truthful marker protocol, journal-only backup exclusions + XML tests | resolved_in_plan |
| F-014 | P1 | Repeat review | Terminal replay был ошибочно подчинён account mismatch и мог не завершить local commit | D-019 + terminal-before/after tests with null/mismatched email | resolved_in_plan |
| F-015 | P2 | Repeat review | Generic interactive consent мог авторизовать B и не позволить восстановить owner A | D-020: persisted/explicit expected account in authorization request + request-account test | resolved_in_plan |
| F-016 | P2 | Repeat review | Legacy owner adoption могла быть представлена как полная account safety | Явно зафиксирована backward-compat heuristic; A-series/B-first-upgrade orphan risk принят | accepted_residual |
| F-017 | P1 | Review pass 1 | Worker recovery сам ставил successor при Retry/Paused и мог бесконечно раздувать APPEND chain | `ExecutionOrigin`; enqueue разрешён только interactive Save/Clear boundary/результат, recovery лишь возвращает Retry/Paused | resolved |
| F-018 | P1 | Review pass 1 | Empty CLEANUP/DELETE и confirmed CREATE markers могли блокироваться account gate до local commit | Все local-only состояния нормализуются до email/token; null/mismatch recreation tests для трёх фаз | resolved |
| F-019 | P2 | Review pass 1 | Adoption/initial journal DataStore failures могли выйти исключением из Save/Clear | Non-cancellation interactive boundary возвращает distinct safe Failure; old active/API invariants tested | resolved |
| F-020 | P2 | Review pass 1 | Недоставало executable fault/status/concurrency/UI evidence | Добавлены middle insert, failed cleanup recreation, applied delete timeout, terminal after-commit, 401/403/429/IO, clear cancellation/save-empty, UI busy variants и UI-worker Mutex tests | resolved |
| F-021 | P3 | Review pass 1 | Disabled Clear сохранял явный error text color | Error задан через `ButtonDefaults.textButtonColors`; disabled color наследуется из Material button colors | resolved |
| F-022 | P2 | PR inline review | `NetworkType.CONNECTED` не запускал worker офлайн для чисто локального terminal commit | Recovery work теперь без network constraint; API-нужда возвращает Retry; scheduler test требует `NetworkType.NOT_REQUIRED` | resolved |
| F-023 | P2 | PR inline review | После `Paused(NeedsConsent)` успешный consent в том же процессе не будил journal recovery | `SettingsViewModel` enqueue-ит recovery на `AuthorizeOutcome.Granted`; Robolectric regression проходит NeedsConsent -> Granted без restart | resolved |

## Plan-review finding verification

| Finding | Required evidence | Command/task | Статус |
|---|---|---|---|
| F-006 | Pre-API crash keeps ID both pending and cleanup-eligible; unattempted ID absent; only 2xx/409 confirms | `*WeeklyScheduleRepositoryTest`, T-002 | passed |
| F-007 | Work 2.11.2 uses APPEND_OR_REPLACE; two enqueues remain separate chain entries; repository worker-origin Retry/Paused never append | `*WeeklyScheduleRecoverySchedulerTest`, `*WeeklyScheduleRepositoryTest`, T-003 | passed_contract; exact worker-finish race remains residual |
| F-008 | Old JSON owner null; atomic heuristic adoption before API; missing/mismatch/sign-out block; token+consent bound to expected account under settings race | `*WeeklyScheduleOperationTest`, `*GoogleAuthManagerTest`, `*WeeklyScheduleRepositoryTest`, T-001/T-002 | passed |
| F-009 | Stateful backend separates caught insert IOException -> CLEANUP_NEW from crash/cancel after apply -> CREATE_NEW/409; applied delete timeout -> 404 without insert | `*WeeklyScheduleRepositoryTest`, T-002 | passed |
| F-010 | App source contract keeps HiltWorkerFactory and startup enqueue; Hilt production compile passes | `*GymApplicationTest`, `:app:compileDebugKotlin`, T-003 | passed_static |
| F-011 | Suspended ViewModel proves common busy lifecycle; Compose semantics proves both production action components disable and re-enable | `*CalendarViewModelTest`, `*ScheduleEditorScreenTest`, T-004 | passed |
| F-012 | Absent/Present/Unreadable are distinct; invalid JSON and DataStore read IO are unreadable and never start replacement | `*WeeklyScheduleOperationTest`, `*WeeklyScheduleRepositoryTest`, T-001/T-002 | passed |
| F-013 | XML excludes only `datastore/weekly_schedule_operations.preferences_pb` in legacy/cloud/device transfer; active settings remains included; terminal marker replays | `*WeeklyScheduleBackupRulesTest`, `*WeeklyScheduleRepositoryTest`, T-002/T-003 | passed |
| F-014 | Empty CLEANUP clears, confirmed CREATE advances/commits, empty DELETE commits before auth; terminal before/after active commit replay with null/mismatch | `*WeeklyScheduleRepositoryTest`, T-002 | passed |
| F-015 | Interactive/token request builder account is expected A (`name`, `type=com.google`) | `*GoogleAuthManagerTest`, T-001 | passed |
| F-016 | Plan/docs call legacy adoption a heuristic and track A-owner/B-first-upgrade orphan possibility as accepted residual | T-005 documentation + reviewer inspection | passed |

## Deviations

Пока нет. Любое изменение frozen contract, новый dependency, Room/schema/manifest/navigation файл
или отклонение версии записать здесь до реализации с причиной, влиянием на AC и одобрением main
agent.

| ID | Task | Отклонение | Причина | AC impact | Решение |
|---|---|---|---|---|---|
| DEV-001 | T-001/T-003 | Startup enqueue is source-contract + Hilt compilation, and auth tests exercise the real request builder rather than live GMS authorization | Full Hilt Application and live GMS authorization integration are not established/offline-testable in this project; injected `Provider` avoids early WorkManager resolution | No frozen behavior change; integration confidence comes from production compilation plus boundary tests | accepted residual for T-006 review |

## Review passes

| Pass | Reviewer/tester verdict | P0/P1 | P2 | Routed fixes | Recheck |
|---|---|---|---|---|---|
| 1 | initial Gate T/V: revise | F-017/F-018 | F-019/F-020; F-021 P3 | origin split, marker normalization, safe interactive boundary, expanded tests, disabled colors | fix pass 1 выполнен |
| 2 | Gate T/V: pass | none | none | повторных исправлений не потребовалось | 717/717 unit, debug build, independent review passed; release signing blocker documented |
| PR inline | two Medium findings fixed | none | F-022/F-023 resolved | unconstrained recovery + consent wake-up, executable regressions | full unit/debug passed; release signing blocker unchanged |

## Git/PR checklist (main agent only)

- [x] Ветка `fix/weekly-schedule-safe-replacement`, intended diff без чужих изменений.
- [x] `git fetch ValerochkaGym main`; версия ветки строго выше актуальной `ValerochkaGym/main`.
- [x] Базовая версия изменена ровно один раз: 10/1.3.2 -> 11/1.3.3.
- [x] Все AC имеют done evidence; P0/P1/P2 закрыты; residual risks записаны.
- [x] Final test, debug и release signing blocker записаны выше.
- [ ] Один русский semantic commit, рекомендуемо `fix: сделать замену расписания безопасной`.
- [ ] Push только `fix/weekly-schedule-safe-replacement` без force.
- [ ] PR в `main` содержит summary, tests, AC evidence и `Closes #8`.
- [ ] PR checks проверены; merge/удаление ветки не выполняются без отдельного запроса.

## Residual risks

- Accepted integration residual: реальный Calendar API не вызывался; 32 lowercase hex ID входит в
  документированное Google base32hex-подмножество, а unit fake доказывает клиентскую state machine.
- Required before merge: локальный `assembleRelease` остановлен на `validateSigningRelease` из-за
  отсутствующих release signing inputs; CI/среда с keystore должна завершить compileRelease + R8.
- Pending: journal намеренно не переносится; если исходное устройство окончательно потеряно после
  remote insert, применённого до timeout, новый device не сможет cleanup orphan new ID. Active old
  переносится и защищён ownerEmail; автоматический cross-device cleanup вне scope без server listing.
- Accepted: legacy `ownerEmail=null` adoption — эвристика. Если historical remote series создана
  аккаунтом A, но первая operation после upgrade выполняется при persisted B, series A может
  остаться orphan; безопасно определить владельца без remote metadata невозможно.
- Accepted test residual: exact enqueue-in-the-last-read-to-worker-finish timing is not driven through
  a live worker chain. Compiled `APPEND_OR_REPLACE` policy and repository worker-origin no-enqueue
  tests cover the two sides of the race; a deeper live harness remains outside the current test stack.
- Accepted integration residual: `GymApplicationTest` verifies source wiring plus successful Hilt
  production compilation, and auth tests verify the real `AuthorizationRequest` account; no live
  Hilt-Application/GMS authorization integration is executed locally.
