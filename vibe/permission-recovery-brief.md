# Gate R: восстановление разрешений (#10, этап 06)

Root исследовал текущие WorkoutsScreen и ActiveWorkoutScreen 10.09.2026.
После этапов 03–05, меняющих эти экраны; один владелец интеграции.

## AC

- AC-001: POST_NOTIFICATIONS и BLUETOOTH_SCAN/CONNECT запрашиваются в контексте старта
  тренировки/подключения датчика. Первый запрос не классифицируется как постоянный отказ.
- AC-002: решение учитывает актуальный grant, per-permission факт прошлого запроса и rationale;
  повторный отказ без rationale предлагает настройки приложения с возможностью отказаться.
- AC-003: возврат из настроек перепроверяет grants на ON_RESUME. Продолжение возможно только
  для всё ещё актуального намерения и не дублирует старт service, навигацию или BLE scan.
- AC-004: отказ уведомлений не блокирует тренировку; отказ Bluetooth оставляет тренировку
  доступной и показывает штатное состояние датчика. Частичный grant не считается полным.
- AC-005: восстановление UI/отмена/исчезновение активной тренировки не запускают устаревшее
  действие. Уже выданное разрешение не вызывает повторный системный запрос.

## Код и проектирование

WorkoutsScreen использует RequestPermission и продолжает start service/navigation из callback;
startEvents при denied всегда открывает showNotificationRationale. ActiveWorkoutScreen использует
RequestMultiplePermissions, при denied всё равно вызывает scanHeartRate для PermissionRequired;
startHeartRateSearch проверяет обе grants, но всегда показывает одинаковый rationale при отказе.
На текущем пути отсутствуют shouldShowRequestPermissionRationale и recovery settings.

Вынести тестируемое решение granted/request/rationale/settings, платформенный адаптер grants и
истории запросов; не дублировать логику в двух экранах. Хранить только нужную историю, не токены
доступа. URI настроек package берётся из context.packageName. Не добавлять permission в manifest,
не менять Calendar OAuth и APK installation flow. UI по дизайн-системе, без forced modal loop.

Strict: разрешения/lifecycle/system intents. Тесты таблицы переходов, первого/повторного/частичного
отказа, возврата grant/denied, stale pending intent, rotation, single-flight navigation/scan.
Существующие guards BleHeartRateMonitor и WorkoutSessionService сохранить.

Официальный источник сверён 10.09.2026:
[Request runtime permissions](https://developer.android.com/training/permissions/requesting).
Он требует контекстного запроса, проверки grant при операции, учёта rationale и доступного
сценария отказа; не полагаться на объединение permissions в одну системную группу.
