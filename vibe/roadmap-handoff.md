# Передача полной реализации roadmap — 10.09.2026

## Продолжение после перехвата (актуальнее исторического состояния ниже)

По запросу пользователя backend checkout перемещён в
`/Users/raul/ItmoProjects/ValerochkaGymBackend`, рядом с Android. Git HEAD650baf2 и
ветка feat/coach-relations сохранены, status clean после переноса. Старый временный
checkout больше не существует; не создавать его снова. Пока работаем локально.


Последнее обновление: #43 **done_local 5c96376**, version36/1.3.28 Room17,
full1083tests0failures/errors1skip/debugPASS. После двух final-write P1 и старого
миграционного fixture исправления independent T/V/narrow reviews PASS. Логи:
`/private/tmp/yarumo-calendar-final-unit-rerun.log`, `/private/tmp/yarumo-calendar-final-debug.log`.
Серверное здоровье **done_local 8bc1fae** на feat/manual-health-ledger, migration011,
full120tests0failures/errors/skips/checkbootJarPASS;4reviewfindings исправлены и перепроверены.
Guest sync **done_local52bfd43**, Room18,37/1.3.29. Final full1110tests0failures/
errors1skip (6m14s) и debugPASS(12s). ПятьP1/дваP2 review исправлены; после двух
неполных writerpasses root закрыл composite account-action и недостающие tests,
отдельный Sol/high narrowreviewPASS. Full обнаружил исторический DDL leak в14→15,
исправлен восстановлением SyncSchema.create и независимым narrow migrationPASS.
Логи `/private/tmp/yarumo-guest-final-unit-rerun.log` и
`/private/tmp/yarumo-guest-final-debug.log`. Следующий CAL-01 Android отRoom18→19;
старая Room17 account-link table сохраняется рядом с новой UUID-link.

Backend coach **done_local650baf2**, migration012, independentT/V PASS,
root checkbootJar139tests0failures/errors/skips PASS (50s), log
`/private/tmp/yarumo-coach-final-check.log`. В новом постоянном checkout planner
`calendar_ai_plan` готовит только backendcalendarAI21 plan/fixture. Production
writer сейчас не работает; запрещённый edited-preview patch остаётсянетронутым.
Актуальная coach fixture f5960d8a8fd269518aa6347b18607a778e4eff7f64aa5ecd9a2dc529aa501460.

PLAN01 edited-preview patch остаётся отдельно заблокированным; ответа на конкретный
запрос нет. Safe subset7f27801: backup assertion11 + exactLiquibasehistory equality,
root96tests0failures/checkbootJar PASS. Патч не применялся, `git apply --check` PASS.
Androidorigin/main cc590a4, backendorigin/main dba59ae при последней проверке.
GitHub Androidpush=true, backendpush=false; публикации/deploy/release этойработы нет.
Guest-access strict narrow GateP PASS, implementation ждётdependencies.

Шесть старых зависших Gradle daemons/testexecutor пар этого checkout (старше2часов)
проверены по daemonлогам завершённых/оборванных клиентов и остановлены root по точным
PID после неуспешногоTERM; повторныйps подтвердил отсутствиеGradle. ЧужиеJVM нетронуты.
Root последнийGradlesession24851 успешно завершён. Новый запуск согласовать с solewriter.

## Поручение и границы

Пользователь поручил автономно реализовать все доработки активного плана, работать мультиагентно со скиллом Feature Implement, сохранять возможность отката каждой фичи, после полного выполнения опубликовать изменения в GitHub и проверить успешный релиз. Не заканчивать работу после одной фичи и не ограничиваться планом. Рутинные решения принимать самостоятельно, не просить повторных подтверждений уже разрешённых действий. Сейчас пользователь попросил передать работу новому агенту; прежние исполнители остановлены на безопасных точках.

На вопрос о прогрессе сообщена приблизительная оценка **20% общего плана**. Это не измерение трудозатрат: завершены локально этапы01–07 и четыре серверные части дальнейших фич; крупные блоки ещё впереди. **Ни одна из новых фич этой сессии пока не опубликована, успешного финального релиза ещё нет.**

Активный источник порядка: `vibe/github-issues-implementation-plan.md`, его tracker и более актуальный `vibe/roadmap-execution-plan-track.md`. **Не подменять этот объём другим старым `superapp-roadmap-plan.md` с отдельными приложениями и версиями1.4–2.0.** Социальные #54–62 оставлены на будущее и исключены из реализации; AI usage/cost limits #63 также отложены. Условные REC/weekly расширения конкретизируются по принятому roadmap, не выдаются за выполненные автоматически.

## Обязательные правила для нового агента

1. Сначала прочитать актуальные `AGENTS.md`, `ARCHITECTURE.md`, полностью `docs/design-system.md`, `.codex/skills/android-feature-implementation/SKILL.md` и его `references/android-quality-gates.md`. Все правила пользователя, проекта и скилла сохраняются. Общаться по-русски, давать краткие содержательные обновления, на вопросы о статусе отвечать и продолжать исходную работу.
2. Мультиагентный workflow: исследование/план → проверка строгого плана → один ответственный implementer на Android и один в отдельном backend checkout → независимые T/V → один пакет исправлений → финальные gates. Делегирование с `fork_turns:none`, точными файлами/AC/T и предупреждением о других авторах. Один writer владеет Room/entity/DAO/migration/schema, навигацией и версией. Не запускать Gradle в checkout одновременно с правкой его кода. Отдельный backend checkout допускает независимый Gradle.
3. Именованные Android роли использовать по скиллу. Для строгого review нужен Sol/high; если именованная роль не допускает такой override, использовать независимого default Sol/high. В старой задаче runtime начал возвращать `agent thread limit reached` даже для новых агентов при завершённых прежних. Поэтому использовались доступные агенты с документированным fallback ролей; это не отменяло независимость проверки. В новой задаче попробовать обычный workflow, не зацикливаться на отказе runtime.
4. Не смешивать и не терять уже существующие dirty/untracked изменения. Перед Git-действиями проверить branch/status/log/diff. Каждый законченный app feature/fix — отдельный русский смысловой commit и сохраняемая feature branch; не squash/delete ветки. Авторизованы итоговые push/PR/integration/release, но только после завершения и проверок. Версию увеличивать один раз на фичу: code+1, patch+1, перед commit/push/merge сравнить с актуальным target. Документы не требуют версии.
5. Планы и трекеры — `vibe/`. Не запускать полный Gradle ради одних docs/CI/Git. После стабильной кодовой фичи — полный `:app:testDebugUnitTest`, затем `:app:assembleDebug`; условные gates по изменённому слою. Не повторять уже прошедшие проверки без изменения/непокрытого риска. Backend — targeted, независимый review, затем `check bootJar`.
6. Kotlin UI strings, MaterialTheme colours, GymMotion, GymHaptics, Hilt/KSP/catalog, один app module. Без Log.*, мок-библиотек, destructive Room fallback и сторонних анимационных/графических библиотек. Рукописные миграции, fakes, JUnit4 backtick-тесты, ComputeDispatcher, live collector для WhileSubscribed. Остальные детали — AGENTS.
7. **Не создавать/запрашивать/анализировать UI screenshots/video без явной просьбы.** Использовать Compose semantics, accessibility/UIAutomator/ADB, фильтрованный logcat. AnalysisRenderTest можно запускать при нужных правках, но не открывать его снимки без просьбы. Оригинальный предоставленный branding PNG — отдельный разрешённый исходник, не UI screenshot.
8. Не читать/печатать секреты, не отправлять сообщения другим людям без явного разрешения. Не обходить sandbox/automatic approval review. Общая автономия пользователя не разрешает повторять отвергнутую операцию другим инструментом. Ниже есть конкретный остающийся auto-review block: он не снят ответом пользователя о процентах.
9. Не создавать новую задачу/automation/goal как обход продолжения. Пользователь сейчас попросил **промпт для перехвата**, а не создание задачи инструментом. Продолжать после передачи в новой задаче, не параллельно со старой.

## Рабочие каталоги и Git

Android: `/Users/raul/ItmoProjects/ValerochkaGym`.

Backend: `/Users/raul/ItmoProjects/ValerochkaGymBackend`. Это важный отдельный checkout с неопубликованными commits и dirty PLAN01; не удалять и не пересоздавать его.

Android состояние на передачу:

- current `feat/google-account-calendar`, HEAD `968a0f2ba87dbeba8897d405adb35484f3ac98cc` (последние commits здесь — документы, app #43 ещё dirty);
- `main` и `origin/main` = `cc590a4`, версия29/1.3.21; последний fetch main сделан в этой части сессии, версия не менялась;
- `feat/roadmap-delivery` = `a1dc851`, принятая локальная интеграция по этап07;
- `feat/yarumo-rebrand` = `a1dc851`;
- `fix/workout-program-save` = `bb6cbe3`, основной кодовый commit `cdde249`;
- `fix/completed-set-edit` = `8c243e4`;
- `fix/workout-finish` = `26b2b96`;
- `fix/empty-workout-start` = `6ecb7a0`;
- `fix/permission-recovery` = `455f05a`.

Backend:

- current `feat/training-proposals`, HEAD/base `03a0c1ddc0deec53f30e8138b58eb85acc0edcfa`, PLAN01 код/010/tests ещё dirty/untracked;
- `main`/`origin/main` = `dba59ae`, опубликованы только changesets001–006;
- `feat/calendar-plan-contract` = `c73c411`;
- `feat/server-ai-drafts` = `a5b567a`;
- `feat/workout-notes-contract` = `f26de34`;
- `feat/basic-profile-contract` = `03a0c1d`.

Не checkout старую интеграцию поверх dirty фичи и не переносить неизвестные изменения. Ранее для обновления integration ref без переключения файлов применялись проверка ancestor и `git update-ref` с expected old SHA. Новую ветку начинать только с безопасной актуальной базы после сохранения текущего feature состояния. Не путать локальное завершение с публикацией.

## Завершено локально

| Этап | Commit | Версия / проверка |
|---|---|---|
|01–02 #45/46 сохранение программы из выполненных подходов|cdde249|30/1.3.22;985tests/debug PASS|
|03 #44 изменение выполненного подхода|8c243e4|31/1.3.23;1002tests/debug PASS|
|04 #47 завершение тренировки|26b2b96|32/1.3.24;1012tests/debug PASS|
|05 #5 старт без программ|6ecb7a0|33/1.3.25;1013tests/debug PASS|
|06 #10 восстановление разрешений|455f05a|34/1.3.26;1035tests/debug/T/V PASS|
|07 #48 ребрендинг Yarumo coach|a1dc851|35/1.3.27;1041tests/debug/R8/T/V/canonical device upgrade PASS|
|CAL01 backend|c73c411|44tests/check/bootJar PASS|
|AI01 backend|a5b567a|64tests/check/bootJar и15Python delivery tests PASS|
|notes backend|f26de34|68tests/check/bootJar PASS|
|basic profile backend|03a0c1d|72tests/check/bootJar PASS|

В app full suites был1 skipped test. Для #44/#47 принят документированный P2 ограничения Robolectric AlertDialog popup; VM/DAO/seam проверены. Ребрендинг сохранил package, namespace, authorities, OAuth values, UUID prefixes, gym.db/DataStore filenames, signing/update asset contract. Все P1 ребрендинга закрыты. Подписанный release локально не собран из-за отсутствия keystore; R8 отдельно прошёл, итоговая подпись ожидает CI.

## Немедленная работа1: Android Google #43 — НЕ ПРИНЯТ

Plan/track: `vibe/google-account-calendar-plan.md`, `vibe/google-account-calendar-plan-track.md`.

Версия уже ровно36/1.3.28, не повышать повторно. Room17, handwritten16→17 и1→17; исходный identityHash17 `d8f5457e7e902264f3ad4f36a8e5423b`. Последний SHA schema JSON `7e23716431defc3d91f65cf278bb983d07f424bf32bd6937020bb56b432307d1`.

Первый GateI прошёл94target tests + compile/spotless; затем T/V нашли3P1. **Эти прежние PASS не доказывают последний исправленный diff.**

Сейчас все dirty `app/**` этой фичи принадлежат остановленному Google writer. Включают GoogleAuth/Manager, CalendarRepository, WeeklyScheduleRepository, SettingsRepository/VM/Screen, AccountVM, DI, Converters, GymDatabase/Callback, новые identity/link entity/DAO/schema helper, schema17 и tests/fakes. `vibe/google-account-calendar-plan-track.md` — его tracker. Другие dirty docs — отдельная подготовка, не стирать.

Последний handoff writer:

1. **Settings P1:** код исправлен: persisted primitive kind/target/nonce/busy, синхронный attempt/disconnect claim, guarded continuations/current-only clear, cancellation invalidates only own op. Добавлены tests duplicate resume/result, disconnect×2+connect race, cancelA→B→lateA Granted/token/NeedsConsent, revoke failure/exception. **Новейшие edits не скомпилированы/не запускались.** Ещё нужен malformed SavedState/no-requireNotNull regression и tracker.
2. **One-off Calendar P1:** проверки identity после token/routine, сразу передAPI, послеAPI и внутри Room до insert. Cancel захватывает immutable scheduled/link tuple и перепроверяет его в transaction после2xx/404/410 response/exception до удаления. Добавлены gated-token disconnect, insert switch, response/exception tuple replacement tests, **ещё не запускались**.
3. **Weekly P1:** checks owner after token, до/после каждого create/delete/cleanup и перед commit/journal clear; mismatch сохраняет journal/current schedule. Добавлены6race/revoked/missing/different tests. Последний command `./gradlew :app:testDebugUnitTest --tests "*WeeklyScheduleRepositoryTest" --no-daemon`: compileTest прошёл,30tests,2failed из-за невалидных новых fixtures (second prepared rule/target отсутствовали). Fixtures исправлены, **rerun ещё не было**. Native hang не было.
4. **P2:** account-security methods GoogleAuth стали abstract, discovered fakes обновлены; SQLite insert/update triggers задают OWNED/non-null-normalized vsLEGACY/null и immutable link, есть corruption test. Успешные register/verify/password-login invariance tests добавлены, не запускались. **Medium/expanded UI semantics ещё не добавлены.**

Следующий writer: форматирование средствами проекта (предыдущий также оставил `/tmp/format_changed.py`, сначала прочитать), закончить два указанных тестовых пробела, малые filters SettingsRecoveryScheduling/CalendarRepository/WeeklyScheduleRepository/AccountViewModel/CalendarEventAccountLinkDao/migrations, JDK21compile и spotless, tracker. Затем **независимое узкое T/V по исправленным гонкам**, только потом root full unit/debug. Не считать3P1 закрытыми без повторной проверки. Нет активного Android процесса/Gradle на момент передачи.

## Немедленная работа2: Backend PLAN01 — частично готов, НЕ ПРИНЯТ целиком

Fixture Android `vibe/contracts/training-proposals-contract.json` и backend `src/test/resources/training-proposals-contract.json` идентичны. SHA `65254ebf9aaa4062ebf8ef71df99c76876685ce10ec0bd2fa8645fced56ea998`. Исходный strict freeze ca41f1f, позднее gymIds cap1000 согласован с RecordValidator. Не возвращать старый SHAa19….

Implemented: immutable proposal/version, exact raw approval-body SHA/operation ledger, atomic routine+calendar+receipt+head update, internal AI hook with final owner/catalog/session locks, recipient access/reject/revoke, default-deny coach authority, pagination bounded1MiB. Safe fixes независимо **V PASS**: active workout оставляет PENDING; raw integer conversion guards; gym1000/1001; PERSONAL TIMED/CARDIO; UUID cursor resolves only current recipient and no skips; historical author snapshot with FK/CHECK/live pointer and terminal no-reattach; confirmed owner deletion scoped and catalog→existinghead BEFORE users.lock, no head materialization for invalid code/deleted owner.24focused PostgreSQL tests PASS.

**Последний root full gate FAIL:**

```
JAVA_HOME="$(/usr/libexec/java_home -v 21)" DOCKER_HOST=unix:///Users/raul/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true ./gradlew --no-daemon check bootJar --console=plain
```

Log `/private/tmp/yarumo-training-proposals-final-check.log`.96tests,1failure: `BackendIntegrationTest.backup restores account records and Liquibase history into a separate database`, line503 **expected10, actual11** (Liquibase history count). `bootJar` task выполнилась, но весь gate FAILED. Следующий агент должен проверить expected changeset count относительно actual master/backup и исправить assertion, затем соответствующий targeted и полный gate после стабильности. Не скрывать этот failure. Root exec session42881 завершена/закрыта, работающих серверных Gradle нет.

### Отдельный незакрытый automatic approval block

Принятые PLAN01 AC004/005 разрешают локально отредактировать preview, сохраняя immutable author snapshot, и подтвердить валидный edited draft. Текущий код всё ещё сравнивает draft со stored version и отклоняет любые edits.

Автоматическая проверка разрешений **дважды отклонила удаление equality**, считая его ослаблением защиты: «removes the immutable stored-draft equality check, allowing approval of altered content and persistence of unintended routine/calendar data despite only validating the replacement draft». Независимый Sol review самого патча P1 не нашёл: envelope/source/version/permissions/freshness/live validation остаются, accepted raw bytes идут в ledger. Тем не менее **этот запрет не обходили и патч не применён**.

Конкретный reviewable unified diff: `/private/tmp/yarumo-proposal-edited-approval-proposed.patch`,130lines, SHA `e7f08d227af35b7555bf85bdb437d6cd3898ce369218cf77ea2f28caded53914`. Он содержал полные tests и проходил git apply --check на тогдашнем diff; после последующих edits проверить актуальность заново. P2 для будущего применения: invalid candidate сохраняет PENDING/sourceJSON; live-invalid exercise/gym edit; unknown author/source envelope rejection.

Пользователю был отправлен async запрос отдельного разрешения применить именно этот патч, с объяснением auto-review. **Разрешение ещё не получено**; его вопрос «сколько процентов выполнено» не является ответом. Не повторять отвергнутую правку другим инструментом/endpoint/маскировкой. Продолжать независимые работы; при необходимости запросить явное решение с этим конкретным объяснением. Не объявлять PLAN01 полностью принятым до решения.

Ранее также отклонялись broad CASCADE и простое удаление author FK. Их заменили действительно более безопасным вариантом, который уже применён и прошёл review: `training_proposal_authors(historical_account_id PK, live_account_id FK RESTRICT, CHECK live IS NULL OR live=historical)`; proposal.author_id сохраняет FK на historical table; bind INSERT ON CONFLICT DO NOTHING + locked equality, detached neverreattach; confirmeddelete обнуляет только собственную live pointer. Эти прежние альтернативы не возвращать.

## Следующий порядок и подготовленные материалы

1. Закончить #43, затем guest-sync11–12 (`vibe/guest-sync-plan.md`/track, strictPASS включая последний preflight).
2. CAL01 Android (`local-calendar-plan`), calendar month09, AI01 Android (`server-ai-plan`), CAL02 (`google-calendar-sync-plan`). Backend CAL01/AI01 уже приняты локально.
3. Guest access13, notes10 Android, profile14–15 Android, REC01 local replacement16. Notes/profile backend уже приняты.
4. PLAN01 Android после dependencies/server acceptance, calendarAI21, trainer23.
5. Manual health17 backend→Android, sources18, metrics/check-in19, explanations20, wider recommendations22, nutrition24; условный weekly25 отдельно конкретизировать. Supplements38 только после предусмотренной внешней профильной проверки; не выдумывать такую приёмку.

Детальные планы/briefs преимущественно готовы; сначала читать их и текущий код, не пересоздавать исследования. Новый Room predecessor всегда брать фактический, а не старый16 из planning baseline.

### Важные новые подготовительные результаты

- **guest-sync preservation amendment**, strictPASS: OWNED(A)→B до любых purge/token replacement сравнивает PortableData snapshot с baseline и проверяет `backend_outbox`, `catalog_state.originalOutbox`/pending transition, `configuration_tombstones`; будущие health journals/local-only data расширяют footprint независимо consent. Dirty/ambiguous blocks, UI resumeA/cancel, никакого неявного согласия на erase/sync. Clear transition в одной Room transaction, сохраняет STANDARD. Fingerprint включает owner/phase/generation и hashes полного footprint, не одну generation. CLAIMED(B) всегда B-only.
- **guest-access plan/track** сейчас untracked, writer закончил3root repairs и остановился; нужен краткий финальный root strictreview. T007 только targeted, full толькоT009. DatasetId persisted в существующем ownership row, сохраняется при same-dataset reauth/claim, меняется атомарно толькоreplacement. T002 включает BackendSync. T003 требует реального сброса NavBackStackEntry ViewModelStores/jobs и dataset-bound write guards/late A insert test, не только Compose key. GUEST/CLAIMED/OWNED localread различаются независимо tokens; AI gate доpicker/encoding; registration card и noautoAIafterlogin.
- **manual-health contract** принят root в commit968a0f2, SHA `33d74a1de76f9e444e02972f463a915b6e29c338c4c76bbac3aea4ce2305f5c0`. Files `vibe/contracts/manual-health-contract.json`, manual-health plan/track/review. Distinct healthRevision covers new version ingestion AND immutable head transitions; as-of-H combined paging; exact/equal retry before current report-liveness validation; new version/new head selection still validateslive report; missinghead/nonzero base rejects400; parentstored/earlierrequest only, noforwardcycles. Owner switch protects sole/unACK health data; acknowledged recoverable cache canclear onclearpreflight. Root schema/vector/hash validation PASS; backend code ещё не реализован.
- **backend manual-health plan/track** untracked in backend vibe, **root strictrepair ещё pending**. Нельзя начать по текущему тексту без исправления T004: AiController @RequestBody читает image раньше AiImageInput validation, значит нужен InBody disclosure/header guard в BearerFilter BEFORE chain/bodyread и config/Security.kt, включая ASYNC reauth; повторные service preprovider/postprovider checks без DB locks черезHTTP. TestInputStream zero reads приabsent/stale/off, fakeprovider zero. Dedicated health capability before rawreader. Root уже выбрал page tokens TTL24h с purpose separation, committed cursors noTTL while historyretained/keyrotation410; existing secretmanagement. Health storage quota (неAI usage): configurable200MiB logicalUTF8 across storedversions/rawops/results/history,100000versions, atomic preallocation/replaycost0, tinyquota tests. Добавить root final check/bootJar после independentV, reviewer не запускает дублирующий full. Actual migration nextN определяется после сохранения PLAN01; fixture ещё НЕ скопирован backend health resources.
- `vibe/calendar-ai-brief.md`: capture-based28calendar days `[startOfDay(capturedLocalDate−27days), capturedAt]` in requestedzone, includes today, excludesfuture facts; не окно от далёкого будущего плана. Server context only, goal first/coverage second, same-exercise completed observation or inferredweightnull, optionaldatedmass projection from existing measurements. Notes ≤20entries/16384UTF8bytes deterministic timestamp/kind/ID, opt-out. Numeric/goal policy/evals ещё GateP. Existing provider supports onlyexercise/InBody; new typed schema/service required. `AiContextReader` and atomic `TrainingProposalAiCreator` reuse; не finalcheck→separatewrite gap. Current PLAN01 history check onlyanyfinished same-exercise, stage21 must enforce actualcompletedset/window.
- `vibe/coach-relations-brief.md`: root choices tokeninvite7days/singleuse/noemailsearch, user shares manually, separate calendar/completed-workout consent, exercises+actualsets visible, notes/profile/health/AI excluded; both revoke, severalrelations allowed; defaultdeny untilstage23. Historical author snapshot neverauthority. Need firstbind/detach/deletion PostgreSQL race before enablingcoach routes. Dedicated projections, not `/sync` or `/records`.
- `vibe/expanded-metrics-research.md`: full #50 CLI body already sets scales; don't re-ask. Sessioneffort0–10, sleepduration/quality1–5, energy/stress/motivation1–5, pain0–10+region/side/trigger/exercise, bilateralleftarm/thigh/calves/neck. Root chose nonmedical TrainingCheckIn SESSION onepercompletedworkoutUUID; historicalcheckin retained onworkoutdelete. DAILY oneperexplicitlocaldate withzonecaptured. Height/newcircumferences remain existing BodyMeasurement SSOT, profileprojectiononly. Detailed GateP/history/capabilities stillneeded; no healthreportduplication.
- `vibe/health-sources-brief.md`: localoriginalPDF/photo, explicit archive/recoveryjournal, selectedpage→backendAI draft→confirm; no localOCR/autohealthsave. Exact attachment/archival semantics must be planned beforecode.

## Основные принятые продуктовые правила

- Все AI context и provider calls на backend в рамках запроса. Android только намерение/явно выбранные файлы, неprompt/history/providerkeys. Sync необходимых данных до AI; no-history weightnull, цельпервична.
- AI/человек-тренер сначала предлагают, клиент утверждает. Только подтверждение создаёт программу+будущийплан. Existingcompletedhistory и календарь доступны тренеру толькопоразрешению; revokeпрекращаетдоступ.
- Guest localworkouts/calendar, безAI; регистрация постояннойкарточкойвзаблокированнойсекции. ПрофильприAI, максимум3поля, skip/disable и72h послеactualshown; безобязательнойанкеты/автоповтораAIпослевхода.
- Первая регистрация объединяетвесьportableguestнабор сbackend. GUEST→CLAIMED(B,mergeId)Room BEFOREtokens→OWNED толькоfinalACK/nooutbox/freshbaselineequal. TokenlossнепревращаетA/Bвгостя.
- Workout history conflict = serverwins/одинsnapshot; routine config conflict = serveroriginal + ровнооднаlocalclone сновымstableUUID, безклонированияупражнений/залa/history. Другиеконфликты/удалениеvsedit требуютявногорешениявUI безпреждевременноймутации.
- Overwriteпрограммы — заменитьагрегатвыполненнымиподходами:4→3 значит3, неappend. CreateNew создаётновую.
- CAL localportableplans отдельныотGooglemetadata. Google pull onopen6h/manual; внешниеизмененияпредлагаются, неавтоприменяются. Owner-bound journals/cursors/ETags, recurrenceexceptions/DST, staleguards поCAL02.
- Медицинские записи/AI disclosure/sync независимы; BodyMeasurements/InBody не копируютсявhealthobservations. Исправлениясохраняютисторию; файлы/AIчерновикинесохраняютсякакподтверждённыебезклиента.

## Runtime, gates, emulator

JDK21: `/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home`; обычно `JAVA_HOME="$(/usr/libexec/java_home -v 21)"`. Test JVM21 обязательно; compilationtoolchain17, Gradle9.6. SDK `/Users/raul/Library/Android/sdk`, buildtools36.0.0, compile/target37, min36, Robolectric36.

Docker/Colima: `DOCKER_HOST=unix:///Users/raul/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true`. Не prune чужиеcontainers. Backend clock-dependent expired fixtures ранее заменены на timestamp2000; неотменять. TRUNCATE auth tableorder былисправленпротивdeadlock.

Mac Android fullsuite ранее застревалвnativegraphics безответаjcmd. Успешный полный1041suite выполнен с временным init-script `/private/tmp/yarumo-test-isolation.gradle`: forkEvery=1/maxParallelForks=1 иbeforeTestprintln, безexclusions/изменениярепозитория. Команда:

```
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew --no-daemon :app:testDebugUnitTest --init-script /private/tmp/yarumo-test-isolation.gradle --console=plain
```

~8минут на1041tests, потомassembleDebug. Не повторятьбезизменений и не завершать чужие Javaпроцессы. Последний Google targetedrun НЕ былnativehang; были исправленныеfixtures. Локальнаяподписьrelease отсутствует; не изобретатьновыйkeystore. CI долженподписатьсуществующимключом.

**Emulator5554:** глобальноустановленdebug35. Androiduser0 содержит **старую неподдерживаемую devDB**, version14identity `96f233e6f2229fd0f4509cd505e75dd9`, неcanonical14. Она уже падала до ребрендинга: нетexercise_equipment. **Не clear/reset/normalize автоматически.** Backup `/private/tmp/yarumo-upgrade-existing.db`, читатьsqlite `mode=ro&immutable=1`; некоммитить/публиковатьмедицинские/личныеданные. Canonical14identity `a03c9f43c708a8e06223e6e697cea4a2`.

Изолированный emulator user10 `YarumoReleaseCheck` создандлятестов; canonical34→35 upgradeиaccessibilityPASS, сейчасuser10остановлен, foregrounduser0восстановлен. Использоватьuser10дляcanonicalпроверок, неuser0. APK34backup `/private/tmp/yarumo-before-rebrand-v34.apk`, v27backup `/private/tmp/yarumo-installed-v27.apk`; debugcertSHA556d9f2035e16b63b985daaa6b23287750e0e98856dad02203ca01cc6ff6da83.

## GitHub и release blocker

CLI activeaccount `rurkk`. Androidrepo `Valerochka1337/ValerochkaGym` имеетWRITE. Backendrepo `Valerochka1337/ValerochkaGymBackend` — толькоREAD: SSHdryrunpushdenied, vars/secrets403, connectorpush/adminfalse. **Нельзяобходитьправа/искатьчужиетокены.** Разрешённая eventualальтернатива — forkподrurkk иupstreamPR; это НЕдаётправслить/deployupstream. Покаforkнесоздан. После всейлокальнойработыопубликоватьдоступнымпутём; неподтверждённыйbackendproductionreleaseнеобъявлятьуспешным.

Backendproduction `https://api.valerochkagym.tech/v1`, health `/health`. BackendCI включаетcheck/bootJar,adminnpm,DockerGHCRdigest,backup/deploy/health; image rollbackнеLiquibase downgrade. AIproviderdefaultoff; livekeysнечитались/ненастраивались.

AndroidmainprotectedPRstatus `Build and test`, обязательногоhumanapprovalнебыло. CI долженвыполнитьunit/signedR8/apksigner/GitHubRelease. Сохранитьasset naming `ValerochkaGym-v<version>.apk` и`.sha256` несмотрянабренд. Только docs не должнызапускатьbuild/AIreview/release иверсиюбамп.

CLI issueexports: `/private/tmp/yarumo-issues-replan.json`, `/tmp/yarumo-issues.json`, `/tmp/yarumo-issue-comments.json`. Полный #50 bodyважнееего сокращениявroadmap, но поздниеответыпользователяотменяютстарые PD003/005. Не закрыватьissueпочастичномуинкременту.

## Состояние исполнителей при передаче

Все прежние subagents подтвердили безопасную остановку. Backend writer —24focusedPASS/healthdocsrepairpending; Googlewriter —unverifiedlatestfixbatch безпроцессов; reviewer —backendsafesubsetPASS/guestplanrepairssaved; researcher —readonlycompleted. Rootfullbackendsession42881закрыта с96tests/1failure. Старыеagentsне должныпродолжатьзаписьпараллельносновойзадачей. Новыйагентназначаетвладельцевзаново иначинаетспроверкиGitstatus/файловэтогоhandoff, а не сreset/reclone.

## Latest steering

Пользователь поручил пока работать локально, GitHub write access выдаст отдельно.
Затем явно попросил перенести backend рядом с приложением; mv выполнен и HEAD/status
проверены. Не публиковать/не создавать fork/PR до снятия текущей паузы. Продолжать
остальную локальную реализацию и проверки, сохраняя отдельные feature commits.
