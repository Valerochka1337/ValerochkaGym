# План: сохранить завершённую тренировку как программу

**Slug:** `save-completed-workout-as-program`  
**Статус:** post-review remediation готов к реализации (Gate P пройден повторно)
**Исполнитель:** один implementation writer — все перечисленные production/test/build файлы и обновление трекера; не менять чужие файлы.

## Цель и рамки

Из итогов только что завершённой тренировки и из открытой истории пользователь может нажать ровно «Сохранить», назвать новую программу и сохранить независимый снимок выполненной части тренировки.

В scope: shared domain use case, два ViewModel/Compose entry point, UI-обратная связь, unit-тесты и ровно один version increment. Post-review scope добавляет идемпотентность уже начатого сохранения через durable `syncId`, disabled-состояние blank confirm и единственное primary action в summary. Не в scope: изменения Room schema/entity/migration, исходной тренировки или её routine, немедленное редактирование новой программы, Calendar/расписание, новая навигация, разрешения и зависимости.

### Допущения

- Loaded workout считается доступным для действия только при `finishedAt != null` и хотя бы одном `WorkoutSetEntity.isCompleted`; normal summary already receives a finished workout, history is still explicitly gated.
- Названия могут совпадать. Каждый новый показ диалога создаёт один UUID operation `syncId`; он передаётся в `RoutineEntity.syncId` и остаётся одинаковым при повторе именно этого operation после recreation. После `Saved` или cancel/dismiss ключ очищается, поэтому следующий явный Save создаёт независимую программу.
- Диалог хранит в `SavedStateHandle` восстановимый draft (`visible`, entered name, retryable error) и operation `syncId` текущего destination. In-flight `saving` не переживает recreation: новая VM открывает тот же редактируемый/закрываемый диалог без залипания; успех и snackbar остаются one-shot event и не воспроизводятся.

## Acceptance criteria

| AC | Критерий | Проверка |
|---|---|---|
| AC-001 | Summary и history detail показывают «Сохранить» только для загруженной завершённой тренировки с completed set. | T-002, T-003; VM tests |
| AC-002 | Тап открывает «Сохранить как программу» с prefill `WorkoutEntity.name`, доступными полем/действиями и validation. | T-003; Compose semantics/unit state tests |
| AC-003 | Confirm trim-ит имя, не допускает blank, блокирует повторный tap и создаёт ровно одну программу; cancel/dismiss не пишут. | T-001–T-003; use-case/VM tests |
| AC-004 | Новая программа сохраняет exercise `position`; содержит completed sets по `setIndex` и их weight/reps/duration/speed/incline. | T-001; domain test |
| AC-005 | Incomplete sets и упражнения без completed sets исключаются; source workout не меняется. | T-001; domain test |
| AC-006 | Независимая программа создаётся без gym restrictions (`RoutineConfigurationDraft.gymIds = emptySet()`); `GymRepository.saveRoutineConfiguration` даёт atomic write и validation остальных инвариантов без partial write. | T-001–T-003; fake repository/use-case + VM error tests |
| AC-007 | Только `Saved` вызывает `RoutineUploadScheduler.schedule()` с `syncId` сохранённой routine; повтор того же operation безопасен, так как scheduler уже unique/idempotent. | T-001, T-005; domain test |
| AC-008 | Duplicate names разрешены; каждая success-операция независима, `restSeconds=null`, без source-routine relationship. | T-001; domain test |
| AC-009 | Success закрывает диалог, оставляет текущий result screen и показывает короткое подтверждение; навигация не меняется. | T-003; VM event/Compose collector test |
| AC-010 | Если Room уже зафиксировал routine, а процесс завершился до очистки `SavedStateHandle`, повтор confirm с тем же operation `syncId` возвращает эту же routine и не создаёт второй; следующая новая операция остаётся независимой. | T-005, T-006; repository/use-case и оба VM process-recreation tests |
| AC-011 | Confirm в диалоге недоступен для `""` и whitespace-only имени, доступен для непустого имени и остаётся недоступным во время сохранения. | T-007; `SaveWorkoutAsProgramDialogTest` |
| AC-012 | На summary «Готово» остаётся единственным full-width primary `PillButton`; «Сохранить» использует вторичный M3 `TextButton` и сохраняет семантику/48dp target. | T-007; summary Compose semantics test |
| AC-013 | Идемпотентный возврат по `syncId` применяется только к create-draft (`routine.id == 0L`); editor draft с nonzero ID сохраняет существующие validation/upsert/replace routine, exercises и gyms. | T-005; repository regression test |
| AC-014 | Восстановленный visible/name draft без operation token считается orphaned: VM атомарно закрывает и очищает его, не генерируя token при restore/confirm. | T-006; оба VM restoration tests |

## Поток и зафиксированные контракты

### Сейчас

`finish → WorkoutDao/Room WorkoutFull → WorkoutSummaryViewModel → WorkoutSummaryScreen`; позже `Calendar → WorkoutDetailViewModel → WorkoutDetailScreen`. `RoutineUpdateUseCase` умеет перезаписать source routine, а `GymRepository.saveRoutineConfiguration(draft)` уже валидирует gyms и транзакционно сохраняет routine/exercises/`RoutineGymEntity`.

### Цель

`WorkoutFull (Room SSOT) → [Save] UI event → VM immutable save state + SavedStateHandle draft → SaveCompletedWorkoutAsRoutineUseCase → GymRepository.saveRoutineConfiguration(transaction) → Saved → RoutineUploadScheduler.schedule(syncId) → VM one-shot acknowledgement → same screen`.

`SaveCompletedWorkoutAsRoutineUseCase` — единственный mapper: sort exercises by `WorkoutExerciseEntity.position`, discard sections without completed sets, sort their sets by `setIndex`, map all five execution fields to `PlannedSet`, set new `RoutineExerciseEntity(id=0, routineId=0, position=source position, restSeconds=null)`, and always set `RoutineConfigurationDraft.gymIds = emptySet()`. Новый независимый routine не наследует исторические gym restrictions; пользователь может задать их позже в редакторе. It builds `RoutineEntity(name=trimmedName, note="")` only after nonblank validation. Source Workout/Routine is read-only.

The use case returns a small sealed result: `Saved(routine)` / `BlankName` / `Conflict(exercises)` / `GymNotFound` / `Failure`; cancellation is rethrown. It calls the scheduler for either repository `Saved` (new or idempotently replayed), never for any other result; scheduler failure is best-effort and does not convert the durable Saved result into Failure. Constructor injection (`@Inject`) uses existing singleton `GymRepository` and `RoutineUploadScheduler`; no new Hilt binding/scope is needed.

Both ViewModels cache their sorted `WorkoutFull` only after load, expose immutable `canSaveAsProgram`, dialog/draft/saving/error state, and accept `openSaveAsProgram`, `changeSaveAsProgramName`, `confirmSaveAsProgram`, `dismissSaveAsProgram`. Confirm uses an atomic non-blocking mutex acquisition before capturing the immutable workout/name snapshot and setting saving, making concurrent taps no-op. Screen input calls VM events; no repository/Room access from Compose. A buffered Channel/flow emits only success acknowledgement; error remains state so the dialog stays open. Cancel and `onDismissRequest` just clear transient state, with no coroutine/write.

No navigation route or back stack changes: system back/dismiss closes only the dialog. Existing scaffold margins and adaptive policy remain; the summary action lives with its existing bottom action, history action is an accessible M3 action in the existing detail layout. Use `PillButton` for the primary visible action, standard `AlertDialog` + `OutlinedTextField` + M3 text actions; all UI text stays Kotlin literals. Visible label is exactly `Сохранить`; semantic description is `Сохранить тренировку как программу`.

### Post-review frozen remediation contract

`RoutineEntity.syncId` is the durable idempotency key for this create flow; no new table, entity, migration or destructive fallback is permitted. `SaveCompletedWorkoutAsRoutineUseCase` receives a nonblank `operationSyncId` supplied by the destination VM and creates `RoutineEntity(id = 0L, syncId = operationSyncId, ...)`. Only when `draft.routine.id == 0L`, `GymRepositoryImpl` first calls `RoutineDao.getRoutineBySyncId(operationSyncId)` inside its existing Room transaction; if found, it returns that routine and performs no mutation. Otherwise it commits the current atomic routine/exercises/gyms create. A nonzero-ID RoutineEditor draft bypasses this lookup and retains the existing validation, upsert and replace behavior for its routine, exercises and gyms. The use case maps either create result to UI-level `Saved` and calls the existing unique/idempotent `RoutineUploadScheduler.schedule(routine.syncId)` for either result: process death may have occurred after Room commit but before enqueue. This makes an uncertain create transaction safe to replay without changing editor updates.

Both ViewModels generate a UUID only in `openSaveAsProgram`, persist it beside the dialog draft, require it before confirm, pass it unchanged through recreation, and clear it only on `Saved`, cancel/dismiss, or ineligible source. A restored visible/name draft without its operation token is orphaned: during restoration/confirm it must atomically close and clear the draft, never manufacture a fresh token for ambiguous prior state. A new dialog after a prior success/cancel/orphan cleanup uses a new UUID. The mutex remains only an in-process admission guard; it is not the durable correctness mechanism. Tests must reproduce the critical ordering: repository commits one routine, the first coroutine is held before clearing the handle, then a recreated VM using that same handle confirms and leaves exactly one routine.

The shared dialog enables confirm exactly when `name.isNotBlank() && !isSaving`; the VM/use case blank check remains defense in depth. In summary, `PillButton("Готово")` is the sole primary bottom action; the eligible save entry is a secondary M3 `TextButton`, keeps its content description and has a 48dp target.

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

## Post-review remediation tasks

### T-005 — Durable operation idempotency at the repository boundary

- **Owner / depends on:** implementation writer; existing T-001 contract.
- **Files:** `app/src/main/java/com/valerochka1337/valerochkagym/data/db/dao/RoutineDao.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/domain/GymRepository.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/data/GymRepositoryImpl.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/domain/SaveCompletedWorkoutAsRoutineUseCase.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/data/GymRepositoryImplTest.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/domain/SaveCompletedWorkoutAsRoutineUseCaseTest.kt`; every test fake implementing `RoutineDao`: `ui/ActiveWorkoutViewModelTest.kt`, `ui/CalendarViewModelTest.kt`, `ui/RoutineEditorViewModelTest.kt`, `ui/WorkoutSummaryViewModelTest.kt`, `ui/WorkoutsViewModelTest.kt`, `worker/RoutineUploadSchedulerTest.kt`.
- **Actions:** Add `RoutineDao.getRoutineBySyncId`; only when `draft.routine.id == 0L`, call it inside `GymRepositoryImpl`'s existing transaction before any write and, when it finds a row, return that existing routine without delete/replace/update mutation. A nonzero-ID editor draft must bypass lookup and retain current validation/upsert/replace behavior. Add a trivial `null` override to every handwritten test fake implementing `RoutineDao`, except fakes whose test needs lookup behavior. Pass the VM operation UUID through the use case to `RoutineEntity.syncId`; call the existing unique/idempotent scheduler for either `Saved` result so replay repairs a possible commit-before-enqueue gap. Keep cancellation rethrowing and no schema/migration/DI work. Add Room idempotency/no-rewrite coverage for same-key create replay → one routine/exercise set and a distinct key → a second routine; add a nonzero-ID editor-shaped draft regression proving routine fields, exercises and gyms are updated; add a use-case token test asserting the supplied UUID becomes `RoutineEntity.syncId` and same-token replay re-enqueues that same UUID without a second durable row.
- **Automated verification:** `./gradlew :app:testDebugUnitTest --tests "*GymRepositoryImplTest" --tests "*SaveCompletedWorkoutAsRoutineUseCaseTest"`.
- **Done when:** the create transaction is safely repeatable by operation key, nonzero-ID editor updates retain their existing replacement semantics, and all AC-010/AC-013 persistence assertions pass.
- **AC:** AC-007, AC-008, AC-010, AC-013.

### T-006 — Restore the same operation in both destinations

- **Owner / depends on:** implementation writer; T-005.
- **Files:** `app/src/main/java/com/valerochka1337/valerochkagym/ui/summary/WorkoutSummaryViewModel.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/ui/history/WorkoutDetailViewModel.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/WorkoutSummaryViewModelTest.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/WorkoutDetailViewModelTest.kt`.
- **Actions:** Persist/restore the operation UUID with the existing dialog draft; clear it on `Saved`, cancel/dismiss, and ineligible source. Treat restored visible/name without a token as orphaned: atomically close/clear it on restore or confirm and never generate a token there. Retain the mutex behavior. In each VM test, let the first confirm commit a durable fake entry then suspend before cleanup, recreate with the same `SavedStateHandle`, confirm again, and assert one durable entry with the same operation UUID; cover an orphaned restored draft in both destinations; then open a fresh dialog and prove it gets a different UUID and may create another routine.
- **Automated verification:** `./gradlew :app:testDebugUnitTest --tests "*WorkoutSummaryViewModelTest" --tests "*WorkoutDetailViewModelTest"`.
- **Done when:** both entry points survive the commit-before-cleanup window without a duplicate, clear an orphaned restore without a write, and preserve the existing error/cancel/navigation behavior.
- **AC:** AC-003, AC-009, AC-010, AC-014.

### T-007 — Correct secondary save affordance and blank confirmation semantics

- **Owner / depends on:** implementation writer; no production dependency beyond the existing shared dialog.
- **Files:** `app/src/main/java/com/valerochka1337/valerochkagym/ui/components/SaveWorkoutAsProgramDialog.kt`; `app/src/main/java/com/valerochka1337/valerochkagym/ui/summary/WorkoutSummaryScreen.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/components/SaveWorkoutAsProgramDialogTest.kt`; `app/src/test/java/com/valerochka1337/valerochkagym/ui/summary/WorkoutSummaryScreenTest.kt` (new only if the current Compose harness can render the screen without new infrastructure).
- **Actions:** Gate confirm by `name.isNotBlank() && !isSaving` and extend semantics tests for empty, whitespace-only, valid and saving states. Replace the summary save `PillButton` with a secondary M3 `TextButton` while retaining the semantic description and ≥48dp target; keep «Готово» the only full-width `PillButton`. Assert those roles/actions through the semantics tree at the existing compact/fontScale configuration; no screenshots.
- **Automated verification:** `./gradlew :app:testDebugUnitTest --tests "*SaveWorkoutAsProgramDialogTest" --tests "*WorkoutSummaryScreenTest"` (omit the second selector only when no test is created; its targeted evidence is then in the existing screen test class).
- **Done when:** whitespace cannot start a save and the summary has one clear primary completion action with accessible secondary save.
- **AC:** AC-002, AC-003, AC-011, AC-012.

### T-008 — Revalidation and release bookkeeping

- **Owner / depends on:** implementation writer; T-005–T-007.
- **Files:** `vibe/save-completed-workout-as-program-plan-track.md`; no version/build file.
- **Actions:** Record exact commands and AC evidence. Preserve the already feature-owned version `versionCode 20` / `versionName 1.3.12`: compare it to target before final commit/push, but do not increment again for these review fixes. Run full validation once after the stable diff.
- **Automated verification:** targeted T-005–T-007 commands, then sequentially `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`.
- **Done when:** all post-review traceability rows have actual results, no second version bump exists, and final gates pass or the exact blocker is recorded.
- **AC:** AC-001–AC-014.

## Ownership and waves

| Area | Exclusive owner | Files |
|---|---|---|
| Mapping/persistence/sync seam | implementation writer | T-001 files |
| Summary vertical UI | implementation writer | T-002 files |
| History vertical UI | implementation writer | T-003 files |
| Shared release choke point/tracker | implementation writer | T-004 files |
| Post-review persistence + operation contract | implementation writer | T-005 files |
| Post-review destination restoration | implementation writer | T-006 files |
| Post-review shared/dialog summary UI | implementation writer | T-007 files |
| Post-review final validation | implementation writer | T-008 files |

One writer avoids overlap in shared domain and release choke points. Historical Wave 1–3 (T-001–T-004) is complete. Remediation Wave 4: T-005. Wave 5: T-006 and T-007, sequentially by the same writer. Wave 6: T-008 and final gates. No entity/schema/migration wave exists; DAO and transaction stay under the sole writer.

## Quality gates

- Relevant: UDF immutable StateFlow/Channel events; `viewModelScope` cancellation; Room remains SSOT behind repository; transaction-scoped unique-key idempotency and process-recreation race tests; direct-construction tests with private handwritten fakes; unique/idempotent WorkManager enqueue for every `Saved` result; Compose loading/content/validation/error, TalkBack, target size, font scale, adaptive layout and dialog/back restoration.
- Not relevant: Room migration/schema export tests (no entity/schema/version change), permissions/manifest, foreground service, new dependency/R8/release assembly, charts/screenshots.
- Final project gates exactly once after stable production diff: `./gradlew :app:testDebugUnitTest`, then `./gradlew :app:assembleDebug`.

## Risks, questions, rollback

- **Historical gyms:** a new independent routine deliberately starts unrestricted (`gymIds = emptySet()`), so deleted or changed historical gym configurations cannot block this save. Gym restrictions can be set later in the routine editor.
- **Concurrent UI taps:** a VM-local atomic mutex admission gate permits only one in-flight confirmation; a second confirmation after a completed first is intentionally a new independent routine per AC-008.
- **Scheduler process failure after transaction:** routine is durable and `Saved` stays a user-visible success if best-effort enqueue throws; no compensating delete and no duplicate retry are attempted. The established upload-all recovery can enqueue the durable routine later.
- **Source safety:** no writes target `WorkoutEntity`, workout exercises/sets, or source routine; rollback of this feature is safe because it only leaves independently created routine rows, removable by normal program deletion (and its tombstone flow).
- **Unresolved blockers:** none. Product copy for generic `Failure` is implementation-local but must state that saving failed and allow retry.

### Post-review rollback/data preservation

- The existing unique `routines.syncId` index is reused as the operation key only for create drafts (`id == 0L`). No migration is needed and no persisted create data is rewritten on duplicate replay; nonzero-ID editor updates retain their established replacement path.
- If this remediation must be reverted, it leaves all already created programs intact. A routine created during the small commit-before-cleanup window remains one valid independently deletable program rather than a duplicate.
- Version is intentionally not bumped again: the same feature branch already owns 20 / 1.3.12 relative to main 19 / 1.3.11. Re-check only if target changes before commit/push.

## Gate P self-check

- Every AC maps to T-001–T-008 and an executable test/final command.
- Contracts (mapping, validation, atomic save, scheduler timing, operation-id replay, UI/event/state restoration) are frozen before remediation work.
- One implementation owner owns every non-plan file; no overlapping ownership or Room split.
- Only applicable conditional gates are listed; no migration/release/screenshots gate is claimed.
