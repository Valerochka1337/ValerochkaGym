# Источники медицинских записей и архив — Feature Brief

Этап18 / #19. Основание: read-only research после принятого manual-health плана. Зависимости: stage17 ledger/consent и AI-01 Android/backend. Пользователь делегировал детали реализации; эти ограничения являются инженерными пределами обработки файлов, а не лимитами использования AI (#63 отложен).

## Объём

Локальные оригиналы фото/PDF, извлечение одной страницы/изображения в редактируемый AI-черновик и явный локальный ZIP-архив с восстановлением вложений. Не добавлять локальный OCR, автоматическое сохранение отчёта/активацию ограничения, постоянное серверное хранение исходников или дубли body_measurements.

## Повторное использование

`MeasurementEditorScreen` содержит PickVisualMedia/TakePicture и узкий FileProvider; `AndroidInBodyPhotoEncoder` — EXIF, обработку вне Main, максимум3072px/6MiB JPEG. Существующий InBody удаляет временный camera файл в finally: это образец cleanup, но не владелец постоянных health sources. AI-01 заменяет прямой BYOK/debug logger; использовать только его backend boundary и stage17 disclosure gate. `DatabaseExporter` копирует только gym.db и не является архивом health-вложений.

## Предлагаемый контракт

URI сразу потоково копируется в приватный staging по сгенерированному attachmentId. Metadata содержит относительное внутреннее имя, MIME, размер, SHA256, тип источника, время, owner phase, ссылку на draft/report и выбранную страницу. Внешние URI/абсолютные пути не являются durable identity. Private source storage исключается из неявной системной облачной резервной копии; перенос исходников выполняется явным архивом.

Поддержать декодируемые JPEG/PNG/WEBP/HEIC и PDF; проверять сигнатуру/декодирование, не расширение. Оригинал ≤25MiB, PDF ≤50 страниц. Пользователь выбирает одну страницу; PdfRenderer локально растеризует только её в bounded image. AI получает JPEG ≤3072px/6MiB через новый сценарий AI-01, но не оригинальный PDF. Новые/изменённые платформенные API проверяются по официальной документации при планировании реализации.

Кнопка извлечения явно разрешает запрос при действующем health-disclosure consent. Проверки до кодирования, серверная авторизация перед обработкой, отзыв/owner change/поздний ответ инвалидируют операцию. Ответ только заполняет черновик; Save подтверждает ledger. Распознанное ограничение остаётся текстовым кандидатом до отдельного подтверждения. Источники/рендеры/ответы/ошибки не попадают в debug, outbox, DataStore или structured sync.

Отмена удаляет временные изображения и не создаёт подтверждённых записей; repository владеет cleanup незавершённых draft sources. Явно сохранённый оригинал остаётся связан с отчётом до удаления. GUEST→CLAIMED сохраняет файл и меняет metadata вместе с health claim; A→B сразу скрывает старые ссылки, физический cleanup идёт после безопасного owner transition.

## Архив

Версионированный ZIP через SAF: JSON manifest + `attachments/<generatedId>`, структурированные ledger records и связи. Manifest содержит идентификаторы/версии, владельца/phase, MIME, размеры и SHA256. Максимум100MiB распакованных данных/100вложений; потоковый учёт фактических размеров. Reject duplicates, absolute/parent paths, неизвестные entry, missing/surplus attachments, unsupported version, invalid reference/hash/owner до изменения live state. Чужой owned archive нельзя импортировать в другого владельца; guest archive — только GUEST. Checksum проверяет повреждение, не подлинность.

Gate P обязан разрешить атомарность Room+filesystem: durable import journal/staging и идемпотентный recovery, чтобы metadata никогда не объявляла доступным ещё не опубликованный файл. Нельзя полагаться на «Room commit, затем rename» без восстановления при смерти процесса. До успешной проверки весь импорт изолирован; crash/ошибка не теряют текущие данные. Exact merge/duplicate policy для append-only version IDs должна совпадать с stage17 и не переписывать существующий аудит.

## Acceptance criteria

- AC-001: приватный оригинал и metadata переживают recreation; внешний URI больше не нужен.
- AC-002: unreadable/corrupt/oversize/unsupported input отклонён до AI и подтверждённой записи.
- AC-003: оригинальный PDF сохраняется локально; AI получает только явно выбранную страницу.
- AC-004: consent/revoke/owner/cancellation/late response guards покрывают frontend и backend; нет скрытого запроса.
- AC-005: AI → editable draft → explicit save; ограничения подтверждаются отдельно.
- AC-006: исходники и чувствительный AI-контент отсутствуют в sync/outbox/логах/диагностике/неявном backup.
- AC-007: owner switch запрещает cross-owner source access, claim сохраняет источники.
- AC-008: adversarial ZIP и crash на каждой границе не публикуют неполный архив и не повреждают live store.
- AC-009: восстановленные record+source открываются; повтор импорта не дублирует immutable versions.
- AC-010: UI page/status/error states доступны TalkBack/48dp/fontScale2 и используют существующие дизайн-токены.

Один Android writer владеет attachment DAO/Room actualN→N+1/schema/repository/backup/UI/DI/version. Отдельный backend writer сначала реализует типизированный health-document draft endpoint в AI-01 с stage17 privacy authority. PLAN/fixtures должны указать точные поля ответа, bounds и content checks, а не переиспользовать InBody DTO для медицинского отчёта. Тесты: source copy cleanup, PDF selection, migration, disclosure+invalid provider output, ZIP adversarial+crash recovery, owner guards, Compose semantics; full unit/debug и relevant release/FileProvider gate после стабильного diff.
