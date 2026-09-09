# Gate R: видимое завершение тренировки (#47, этап 04)

Исследование next_workout_fixes_research, 10.09.2026. После отдельного выпуска #44.

- AC-001: завершение доступно в шапке активной тренировки без открытия overflow,
  с accessibility label «Завершить тренировку» и touch target не меньше 48dp.
- AC-002: дополнительная кнопка внизу появляется только при непустом наборе подходов,
  когда все выполнены; добавление/снятие выполнения немедленно скрывает её.
- AC-003: обе точки открывают существующее обязательное подтверждение. Отмена не меняет
  тренировку, таймер отдыха и навигацию.
- AC-004: повторные подтверждения не дублируют завершение, upload и переход; ошибки
  показываются штатно, повтор возможен после завершения неудачной попытки.

Текущий путь: ActiveWorkoutContent.showFinishDialog (rememberSaveable), пункт завершения
в DropdownMenu, ActiveWorkoutViewModel.finish, идемпотентный repository.finish. Нужен
single-flight ViewModel вокруг всей операции с навигацией. Predicate allCompleted выводится
из актуального Room-состояния, отдельный mutable флаг не нужен.

Один владелец ui/active/ActiveWorkoutScreen.kt и ActiveWorkoutViewModel.kt и соответствующих
ui/active/ActiveWorkoutScreenTest.kt, ui/ActiveWorkoutViewModelTest.kt. Изменения #44 уже должны
быть приняты, сохранены отдельным коммитом и учитываться без переписывания их логики.
Схема не меняется. UI — дизайн-система, GymMotion/GymHaptics, без изображений/новых библиотек.
Strict: concurrency finish/nav. Targeted проверки подтверждения, пустого/all-completed состояния,
повторного finish и failure retry. Полные gates выполняет root после стабильного diff.
