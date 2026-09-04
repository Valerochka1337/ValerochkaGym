# План: сохранить завершённую тренировку как программу

**Slug:** `save-completed-workout-as-program`  
**Статус:** готов к реализации (Gate P пройден)  
**Исполнитель:** один implementation writer — все перечисленные production/test/build файлы и обновление трекера; не менять чужие файлы.

## Цель и рамки

Из итогов только что завершённой тренировки и из открытой истории пользователь может нажать ровно «Сохранить», назвать новую программу и сохранить независимый снимок выполненной части тренировки.

В scope: shared domain use case, два ViewModel/Compose entry point, UI-обратная связь, unit-тесты и ровно один version increment. Не в scope: изменения Room schema/DAO/entity/migration, исходной тренировки или её routine, немедленное редактирование новой программы, Calendar/расписание, новая навигация, разрешения и зависимости.

### Допущения

- Loaded workout считается доступным для действия только при `finishedAt != null` и хотя бы одном `WorkoutSetEntity.isCompleted`; normal summary already receives a finished workout, history is still explicitly gated.
- Названия могут совпадать. Каждое успешное подтверждение создаёт новую `RoutineEntity` с новым default `syncId`; связь с source `routineId` не копируется.
- Диалог хранит в `SavedStateHandle` только восстановимый draft (`visible`, entered name, retryable error) текущего destination. In-flight `saving` не переживает recreation: новая VM открывает тот же редактируемый/закрываемый диалог без залипания; успех и snackbar остаются one-shot event и не воспроизводятся.

## Acceptance criteria

| AC | Критерий | Проверка |
|---|---|---|
| AC-001 | Summary и history detail показывают «Сохранить» только для загруженной завершённой тренировки с completed set. | T-002, T-003; VM tests |
| AC-002 | Тап открывает «Сохранить как программу» с prefill `WorkoutEntity.name`, доступными полем/действиями и validation. | T-003; Compose semantics/unit state tests |
| AC-003 | Confirm trim-ит имя, не допускает blank, блокирует повторный tap и создаёт ровно одну программу; cancel/dismiss не пишут. | T-001–T-003; use-case/VM tests |
| AC-004 | Новая программа сохраняет exercise `position`; содержит completed sets по `setIndex` и их weight/reps/duration/speed/incline. | T-001; domain test |
| AC-005 | Incomplete sets и упражнения без completed sets исключаются; source workout не меняется. | T-001; domain test |
| AC-006 | Независимая программа создаётся без gym restrictions (`RoutineConfigurationDraft.gymIds = emptySet()`); `GymRepository.saveRoutineConfiguration` даёт atomic write и validation остальных инвариантов без partial write. | T-001–T-003; fake repository/use-case + VM error tests |
| AC-007 | Только `Saved` ставит `RoutineUploadScheduler.schedule()` ровно один раз с fresh routine `syncId`. | T-001; domain test |
| AC-008 | Duplicate names разрешены; каждая success-операция независима, `restSeconds=null`, без source-routine relationship. | T-001; domain test |
| AC-009 | Success закрывает диалог, оставляет текущий result screen и показывает короткое подтверждение; навигация не меняется. | T-003; VM event/Compose collector test |

## Поток и зафиксированные контракты

### Сейчас

`finish → WorkoutDao/Room WorkoutFull → WorkoutSummaryViewModel → WorkoutSummaryScreen`; позже `Calendar → WorkoutDetailViewModel → WorkoutDetailScreen`. `RoutineUpdateUseCase` умеет перезаписать source routine, а `GymRepository.saveRoutineConfiguration(draft)` уже валидирует gyms и транзакционно сохраняет routine/exercises/`RoutineGymEntity`.

### Цель

`WorkoutFull (Room SSOT) → [Save] UI event → VM immutable save state + SavedStateHandle draft → SaveCompletedWorkoutAsRoutineUseCase → GymRepository.saveRoutineConfiguration(transaction) → Saved → RoutineUploadScheduler.schedule(syncId) → VM one-shot acknowledgement → same screen`.

`SaveCompletedWorkoutAsRoutineUseCase` — единственный mapper: sort exercises by `WorkoutExerciseEntity.position`, discard sections without completed sets, sort their sets by `setIndex`, map all five execution fields to `PlannedSet`, set new `RoutineExerciseEntity(id=0, routineId=0, position=source position, restSeconds=null)`, and always set `RoutineConfigurationDraft.gymIds = emptySet()`. Новый независимый routine не наследует исторические gym restrictions; пользователь может задать их позже в редакторе. It builds `RoutineEntity(name=trimmedName, note="")` only after nonblank validation. Source Workout/Routine is read-only.

The use case returns a small sealed result: `Saved(routine)` / `BlankName` / `Conflict(exercises)` / `GymNotFound` / `Failure`; cancellation is rethrown. It calls scheduler only after repository `Saved`, never for any other result; scheduler failure is best-effort and does not convert the durable Saved result into Failure. Constructor injection (`@Inject`) uses existing singleton `GymRepository` and `RoutineUploadScheduler`; no new Hilt binding/scope is needed.

Both ViewModels cache their sorted `WorkoutFull` only after load, expose immutable `canSaveAsProgram`, dialog/draft/saving/error state, and accept `openSaveAsProgram`, `changeSaveAsProgramName`, `confirmSaveAsProgram`, `dismissSaveAsProgram`. Confirm uses an atomic non-blocking mutex acquisition before capturing the immutable workout/name snapshot and setting saving, making concurrent taps no-op. Screen input calls VM events; no repository/Room access from Compose. A buffered Channel/flow emits only success acknowledgement; error remains state so the dialog stays open. Cancel and `onDismissRequest` just clear transient state, with no coroutine/write.

No navigation route or back stack changes: system back/dismiss closes only the dialog. Existing scaffold margins and adaptive policy remain; the summary action lives with its existing bottom action, history action is an accessible M3 action in the existing detail layout. Use `PillButton` for the primary visible action, standard `AlertDialog` + `OutlinedTextField` + M3 text actions; all UI text stays Kotlin literals. Visible label is exactly `Сохранить`; semantic description is `Сохранить тренировку как программу`.

## Tasks

### T-001 — Domain persistence seam and mapping

- **Owner / depends on:** implementation writer; no dependency.
- **Files:** `app/src/main/java/com/valerochka1337/valerochkagym/domain/SaveCompletedWorkoutAsRoutineUseCase.kt` (new); `app/src/test/java/com/valerochka1337/valerochkagym/domain/SaveCompletedWorkoutAsRoutineUseCaseTest.kt` (new).
- **Actions:** Implement the frozen use-case/result contract above using `GymRepository.saveRoutineConfiguration`, fresh `RoutineEntity`, and existing `RoutineUploadScheduler`. Preserve coroutine cancellation; do not add DAO/schema/migration/DI changes. Test with private handwritten `GymRepository by NoOpGymRepository` and scheduler fakes: trim/blank/no call, mapping/order/all fields, omit incomplete rows, `gymIds = emptySet()` even with historical gyms, null rests/fresh identities/source unchanged, repository result mapping, and scheduler once only after Saved (while schedule exception keeps Saved).
- **Automated verification:** `./gradlew :app:testDebugUnitTest --tests "*SaveCompletedWorkoutAsRoutineUseCaseTest"`.
- **Done when:** one domain entry point makes exactly one validated atomic repository request per invocation and every AC-004–AC-008 behavior is assertion-backed.
- **AC:** AC-003, AC-004, AC-005, AC-006, AC-007, AC-008.

### T-002 — Summary integration

- **Owner / depends on:** implementation writer; T-001.
- **Files:** `app/src/main/java/com/valerochka1337/valerochkagym/ui/summary/WorkoutSummaryViewModel.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/ui/summary/WorkoutSummaryScreen.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/WorkoutSummaryViewModelTest.kt`.
- **Actions:** Inject/use T-001; add immutable save state, restoration keys, success event, completed/finished gate, and duplicate-confirm guard alongside the existing update-routine flow. Add exact-label Save control and reusable/local dialog rendering with trim/blank error, progress disabled semantics, cancel/dismiss, error, haptic `tap()`/`success()` as appropriate, full semantic description, 48dp targets and no inline motion/colors. Collect acknowledgement as a snackbar/concise in-screen message without `onDone` or navigation.
- **Automated verification:** `./gradlew :app:testDebugUnitTest --tests "*WorkoutSummaryViewModelTest"`.
- **Done when:** summary only exposes the action for an eligible loaded completed workout and its state/event behavior satisfies T-001 outcomes without changing update-routine behavior.
- **AC:** AC-001, AC-002, AC-003, AC-006, AC-009.

### T-003 — History-detail integration and UI accessibility regression

- **Owner / depends on:** implementation writer; T-001.
- **Files:** `app/src/main/java/com/valerochka1337/valerochkagym/ui/history/WorkoutDetailViewModel.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/ui/history/WorkoutDetailScreen.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/WorkoutDetailViewModelTest.kt`; optionally (only if the existing Compose test harness can assert the dialog semantics without Android-only additions) `app/src/test/java/com/valerochka1337/valerochkagym/ui/history/WorkoutDetailScreenTest.kt` (new).
- **Actions:** Mirror the frozen VM contract without sharing mutable state across destinations; keep upload-status collection and delete navigation intact. Render the same behavior and exact Russian literals in detail; assert/review semantics tree for full action, title, editable routine name, validation/error, Cancel/Save, disabled saving state. Exercise compact and medium/expanded margins/width through current layout, fontScale 2.0, and dialog/back dismissal; do not use screenshots.
- **Automated verification:** `./gradlew :app:testDebugUnitTest --tests "*WorkoutDetailViewModelTest"` (and `--tests "*WorkoutDetailScreenTest"` only if created).
- **Done when:** reopened eligible history gets the same distinct routine result; ineligible/missing workout, cancel/dismiss, errors and rapid confirm preserve source/history and do not navigate.
- **AC:** AC-001, AC-002, AC-003, AC-005, AC-006, AC-009.

### T-004 — Release bookkeeping and integrated validation

- **Owner / depends on:** implementation writer; T-001, T-002, T-003.
- **Files:** `app/build.gradle.kts`; `vibe/save-completed-workout-as-program-plan-track.md`.
- **Actions:** Compare the version against target branch before editing; raise `versionCode` once and only once, and patch `versionName` once. Record executed commands, AC evidence, deviations/findings and residual risks in tracker. Do not alter catalog/dependencies, manifest, Room, worker configuration, CI, or navigation.
- **Automated verification:** targeted T-001–T-003 tests during implementation; final owner runs once, sequentially: `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug`.
- **Done when:** single version increment is present, all traceability rows are updated with real results, final gates pass (or precise blocker is recorded).
- **AC:** AC-001–AC-009.

## Ownership and waves

| Area | Exclusive owner | Files |
|---|---|---|
| Mapping/persistence/sync seam | implementation writer | T-001 files |
| Summary vertical UI | implementation writer | T-002 files |
| History vertical UI | implementation writer | T-003 files |
| Shared release choke point/tracker | implementation writer | T-004 files |

One writer avoids overlap in shared domain and release choke points. Wave 1: T-001. Wave 2: T-002 and T-003 may be completed sequentially by that writer after the contract is frozen. Wave 3: T-004 and final gates. No Room owner/migration wave exists because no persistent schema changes; no parallel implementers are required.

## Quality gates

- Relevant: UDF immutable StateFlow/Channel events; `viewModelScope` cancellation; Room remains SSOT behind repository; direct-construction tests with private handwritten fakes; WorkManager enqueue only through existing scheduler and only after Saved; Compose loading/content/validation/error, TalkBack, target size, font scale, adaptive layout and dialog/back restoration.
- Not relevant: Room migration/schema export tests (no entity/DAO/version change), permissions/manifest, foreground service, new dependency/R8/release assembly, charts/screenshots.
- Final project gates exactly once after stable production diff: `./gradlew :app:testDebugUnitTest`, then `./gradlew :app:assembleDebug`.

## Risks, questions, rollback

- **Historical gyms:** a new independent routine deliberately starts unrestricted (`gymIds = emptySet()`), so deleted or changed historical gym configurations cannot block this save. Gym restrictions can be set later in the routine editor.
- **Concurrent UI taps:** a VM-local atomic mutex admission gate permits only one in-flight confirmation; a second confirmation after a completed first is intentionally a new independent routine per AC-008.
- **Scheduler process failure after transaction:** routine is durable and `Saved` stays a user-visible success if best-effort enqueue throws; no compensating delete and no duplicate retry are attempted. The established upload-all recovery can enqueue the durable routine later.
- **Source safety:** no writes target `WorkoutEntity`, workout exercises/sets, or source routine; rollback of this feature is safe because it only leaves independently created routine rows, removable by normal program deletion (and its tombstone flow).
- **Unresolved blockers:** none. Product copy for generic `Failure` is implementation-local but must state that saving failed and allow retry.

## Gate P self-check

- Every AC maps to T-001–T-004 and an executable test/final command.
- Contracts (mapping, validation, atomic save, scheduler timing, UI/event/state restoration) are frozen before UI work.
- One implementation owner owns every non-plan file; no overlapping ownership or Room split.
- Only applicable conditional gates are listed; no migration/release/screenshots gate is claimed.
