# Gate R: редактирование выполненного подхода (#44, этап 03)

Исследование next_workout_fixes_research, 10.09.2026. Реализация разрешена автономно.
Текущее основание: глобальный план, AGENTS, ARCHITECTURE и дизайн-система.

## Результат и AC

- AC-001: тап по выполненному подходу открывает черновик фактических чисел для strength/timed/cardio;
  отмена не меняет данные. «Отметить невыполненным» остаётся отдельным явным действием.
- AC-002: сохранение обновляет только подходящие типу числовые поля поверх свежей записи;
  isCompleted и completedAt сохраняются.
- AC-003: правка не вызывает CompleteSetUseCase, не меняет фокус текущего подхода и не
  запускает/не сбрасывает/не останавливает отдых.
- AC-004: удалённый подход или уже завершённая тренировка отклоняют отложенное сохранение с
  понятным результатом; конкурентные несвязанные поля сохраняются.
- AC-005: общая очередь WorkoutSetMutator остаётся единственным писателем; отмена UI/пересоздание
  не подвешивает очередь и не доставляет результат в чужой открытый черновик.
- AC-006: UI использует проектные поля/диалоги, 48dp, semantics и крупный шрифт; нет новых зависимостей.

## Текущий путь и границы

CompletedSetPill в ui/active/ActiveWorkoutScreen.kt сейчас вызывает SetActions.uncomplete;
ViewModel.uncompleteSet вызывает repository.toggleSetCompleted. WorkoutSetMutator.edit уже
сериализует трансформации, но не возвращает исход сохранения. DAO.updateSet без guard способен
перезаписать подход после завершения тренировки; проверка только наличия ID недостаточна.
currentFocus вычисляется централизованно, отдых запускает CompleteSetUseCase.

Владелец будущей реализации один: ui/active/ActiveWorkoutScreen.kt, ActiveWorkoutViewModel.kt,
domain/WorkoutSetMutator.kt, ActiveWorkoutRepository.kt, data/ActiveWorkoutRepositoryImpl.kt,
data/db/dao/WorkoutDao.kt только для guarded update. Схема/миграция не нужны.
Тесты: ui/active/ActiveWorkoutScreenTest.kt, ui/ActiveWorkoutViewModelTest.kt,
domain/WorkoutSetMutatorTest.kt, domain/ActiveWorkoutRepositoryTest.kt; real Room для guard при необходимости.

Не включать видимые кнопки завершения #47 в этот commit: это следующий этап 04 со своим bump.
При последующем #47 сохранение чисел не меняет predicate allCompleted. Не переписывать
summary/use cases программы, где параллельно завершается этап 01–02.

## Gate и примеры

Strict из-за очереди, concurrency и сохранения после окончания сессии. Аналоги тестов:
`edit runs an arbitrary transform in the same queue`, `an edit for a missing set is dropped without
blocking the queue`, `uncompleting a set only clears the completion flag`, существующий тест
CompleteSetUseCase о запуске отдыха. Targeted filters: WorkoutSetMutatorTest,
ActiveWorkoutViewModelTest, ActiveWorkoutRepositoryTest, ActiveWorkoutScreenTest.
Тесты в research не запускались. Продуктовых блокирующих вопросов нет; минимальный UX —
проектный редактируемый диалог с сохранением/отменой и отдельным действием снятия выполнения.
