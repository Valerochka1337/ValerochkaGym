# Gate R: гостевой перенос и конфликты (#51, этапы 11–12)

backend_release_discovery, 10.09.2026. Исследованы BackendSync, PortableData, CloudMerge,
SyncSchema, AccountViewModel и BackendSyncTest. Схема базы на исходной main — 16;
реальный номер новой миграции проверяется перед реализацией.

## AC

- AC-001: первая авторизация сохраняет и объединяет все локальные portable категории с
  серверными; локальные данные не удаляются до acknowledgement полного initial merge.
- AC-002: прошлый кэш аккаунта A никогда не становится гостевым для B. Прежний безопасный
  A→B purge сохраняется, standard каталог остаётся.
- AC-003: durable GUEST → CLAIMED(targetUser,mergeId) → OWNED после ACK переживает restart,
  sign-out, lost response и concurrent local edit. Незавершённый claim B не присваивается C.
- AC-004: конфликт исторического workout UUID оставляет один серверный snapshot, без копии
  и двойного учёта в статистике/AI.
- AC-005: конфликт routine оставляет серверную под исходным syncId и одну полную личную локальную
  копию с новым устойчивым syncId; plannedSets/rest/exercises/gyms сохраняются. Повтор не создаёт третью.
- AC-006: активная тренировка запрещает remote/catalog/apply мутацию; проверка повторяется внутри
  apply-транзакции. Устаревший worker/token callback не меняет нового owner.
- AC-007: ручная миграция сохраняет старые данные/baseline/outbox; sync продолжается exact-operation
  retry после смерти процесса. Добавление будущих категорий обновляет trackedTables и portable schema.

## Evidence и контракты

BackendSync.claim сейчас очищает всё при previous != user, включая null→user; заменить тест
`first account discards legacy local history instead of importing it` на сохранение данных.
AccountViewModel.accept → signIn → scheduler. Mutex сериализует in-process sync. send хранит exact
CloudPush в backend_outbox до HTTP; BackendTokenStore.replaceIfCurrent защищает поздний refresh.

Нужны Room-owned claim и durable mapping разрешённых conflicts: mergeId/kind/originalSyncId/
remoteRevision/localPayloadFingerprint → localCopySyncId. Копирование локальной routine и
PortableData.apply серверной версии выполняются атомарно; local numeric IDs сохраняются по syncId.
Детерминированный UUID допустим как дополнительная защита, но mapping сохраняется в БД.
Не клонировать exercises/gyms/history вместе с routine. Будущий план пока сохраняет исходную ссылку
на server original, не переписывается неявно на новую копию.

Token store — отдельный AtomicFile/Keystore, поэтому нельзя обещать единую Room+token транзакцию.
Gate P обязан описать порядок durable claim → token install → restart recovery без cross-owner окна.
CLAIMED(B) + попытка C не уничтожает dataset и не отправляет его C; UI объясняет незавершённый
перенос и позволяет вернуться к B. Не спрашивать владельца проекта для реализации этого guard.

CloudMerge generic local/server и удаление outbox при run(resolve) не обеспечивают согласованную
автоматическую политику. Применять её к routine/workout конфликтам, а не только прятать UI выбора.
WeeklySchedule пока DataStore и требует CAL-01 переноса в tracked local aggregate.

## Тесты и ownership

Один владелец BackendSync/CloudMerge/PortableData, state entities/DAO/Room migration/schema,
account integration и BackendSyncTest. Freeze контракт до другого Room writer. Targeted real Room:
unique guest/server union, все типы/ссылки, claim/restart/ACK границы, A→B, claimed B→C, workout
server wins, routine full clone+retry exactly2, local edit during upload, lost response same operation,
active deferral и race before apply. Migration test incremental и полный supported path.
Новая миграция не меняет сохранённые baseline/outbox байты. Strict sync/security/concurrency/data.
