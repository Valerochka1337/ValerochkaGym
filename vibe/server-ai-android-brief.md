# AI-01 Android — предварительная карта переноса

Исследование root, 10.09.2026; Gate R ещё не закрыт. Серверный контракт и продуктовый объём —
[глобальный план](github-issues-implementation-plan.md), [backend brief](backend-roadmap-brief.md).

## Подтверждённые точки

- `data/ai/AiApi.kt`: прямые Retrofit dynamic-URL chat/completions и models, bearer пользовательского
  ключа. `di/NetworkModule.kt` создаёт отдельные AI OkHttp/Retrofit/API bindings.
- `AiApiConfiguration.kt`: StoredAiApiConfigurationProvider читает локальные baseUrl/model/key,
  экспортирует isConfigured/connection/requestConfiguration. Это текущий BYOK, не серверная готовность.
- `ExerciseAiGenerator.kt`: клиент читает ExerciseDao/MuscleDao, строит каталог и system/user prompt.
  После переноса сервер собирает каталог владельца, клиент отправляет только описание действия.
- `InBodyReportAiReader.kt`: локальный URI через InBodyPhotoEncoder → изображение, клиентский prompt,
  provider call → редактируемый InBodyReportDraft без записи. Сохраняется подтверждаемый черновик;
  prompt и предметная проверка переходят на сервер. Передача выбранного изображения остаётся явной.
- `AiResponseLogger.kt` сейчас содержит debug Log.d с текстом ответа/ошибки, включая InBody.
  При переносе этот путь нужно убрать: новый контракт не должен логировать здоровье или ответы AI.
- ExerciseLibraryViewModel и MeasurementEditorViewModel зависят от isConfigured; SettingsViewModel
  использует AiModelCatalog. Удаление только createCompletion оставит прямой models-запрос и неверный UX.
- `BackendApi.authorized(method,path,body)` — существующий авторизованный seam;
  `BackendSync.run` — существующий sync, но текущие active/conflict semantics требуют согласования
  с этапом12 перед обещанием «контекст синхронизирован».

## Критерии будущего Gate R

AC-001 все AI и каталог моделей идут через авторизованный backend, без client prompt/provider key.
AC-002 сервер собирает контекст текущего owner в рамках того же action-request, после нужного sync.
AC-003 generation/recognition возвращают проверенный редактируемый результат без записи до согласия.
AC-004 отмена/смена owner/поздний ответ не применяют черновик в другой экран/аккаунт.
AC-005 ручные сценарии доступны при offline/unconfigured/provider failure; ошибки не содержат secrets.
AC-006 BYOK UI/DI/логгер больше не вызывают провайдера; локальный ключ не передаётся серверу.
AC-007 тесты доказывают отсутствие прямого provider transport, чужого контекста и неявной записи.

Остаётся исследовать точный server provider/config deployment, action DTO/response validators,
consent/progress UI, cancellation и snapshot-revision handshake, тестовые seams и обработку файлов.
Без фактической конфигурации провайдера нельзя объявлять production AI работоспособным.

### Требование к результату sync

`BackendSync.run()` сейчас возвращает Unit и при активной тренировке/отсутствии сессии может просто
завершиться, записав статус. Поэтому «await run» недостаточно для AC-002. AI-01 нужен типизированный
результат Ready(owner, revision)/Blocked/Failure, полученный после ACK и проверки текущего owner.
Запрос AI должен передать ожидаемую ревизию как условие свежести, а backend при её изменении вернуть
повторяемый conflict вместо смешанного контекста. Клиент не передаёт содержимое собственного snapshot
вместо серверной сборки. До окончания гостевого claim или при активной тренировке предлагается
повторить позже; пользовательские ручные функции сохраняются. Точные API freeze после этапа12.

### Доступ к конфигурации

Проверка только имён repository-level Actions secrets/variables через `gh ... list --repo
Valerochka1337/ValerochkaGymBackend` получила HTTP403 (недостаточно permissions) в текущем CLI.
Значения не запрашивались и не читались. Это не блокирует реализацию/тесты, но возможность установить
production provider credential не подтверждена; нельзя считать её выполненной по одному deploy.
