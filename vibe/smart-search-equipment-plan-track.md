# Трекер

- [x] Проверить чистую main и создать feat/smart-search-equipment-picker.
- [x] Прочитать архитектуру, правила UI и найти существующие текстовые поиски.
- [x] Реализовать общий поиск и компактный выбор оборудования.
- [x] Добавить и выполнить регрессионные тесты.
- [x] Выполнить форматирование, общий unit-прогон и debug-сборку (исключения ниже).
- [x] Увеличить версию до 25 / 1.3.17.
- [x] Проверить итоговый diff.

## Проверка

- Целевые тесты TextSearch, EquipmentCatalog, ExerciseCatalogProjection,
  ExerciseLibraryViewModel, GymEditorViewModel и ExerciseEditorSheetComposeTest — успешно.
- Общий testDebugUnitTest: 899 тестов, 898 успешны, 1 skipped, 0 failures/errors.
  AnalysisRenderTest и MeasurementsRenderTest исключены временным Gradle init-script:
  они создают PNG, что запрещено правилами проекта без явного запроса пользователя.
  Скриншоты не создавались и не анализировались.
- Compose проверяет посимвольный ввод при fontScale=2, множественный выбор,
  пустую выдачу, очистку поиска, закрытие списка, удаление выбранного и сохранение.
  Существующие проверки неизвестных требований и expanded-редактора также успешны.
- assembleDebug, spotlessCheck, git diff --check — успешно.
- Версия относительно локальной ValerochkaGym/main: 24 / 1.3.16 → 25 / 1.3.17.
- Изменения остаются локальными, без commit/push.
