# CAL-01 — local calendar plans and backend contract

## Goal, scope and boundaries

Replace the current Google-first ad-hoc schedule and DataStore-only weekly template with Room-owned local plans, recurrence rules and instance exceptions. Local persistence succeeds for a guest, offline, and without a Google grant; backend sync is separately pending/error. This is CAL-01 only: no reverse Google polling or event creation, no AI, and no new month/nearest/history presentation beyond adapting existing Calendar controls to the new source.

**Scope:** one-off plans, weekly rules, cancellation/move exceptions, guest transfer, backend sync capability negotiation, legacy migration, current CalendarViewModel controls, and a quarantined legacy Google recovery journal. **Non-goals:** CAL-02 external identity/reconciliation, Google authorization/grant transfer, automatic routine-reference reassignment, new calendar views, and non-calendar conflict policy.

## Acceptance criteria

| ID | Criterion |
|---|---|
| AC-001 | A guest can create, edit and cancel one-off or weekly plans offline; the Room commit is success and sync status is independently pending/error. |
| AC-002 | Plan/rule/exception identities are UUIDs independent of Google and local numeric IDs; duplicate command/recreation is idempotent. |
| AC-003 | `scheduled_workouts`, DataStore weekly rules and the pending Google journal migrate safely: references and links survive, no source is deleted, and recovery never replays a foreign/legacy Google operation. |
| AC-004 | An authenticated owner syncs the three new kinds and guest claim retains them; legacy clients neither receive nor send them. |
| AC-005 | A cancelled/moved instance affects only its original instance key; it never edits the rule, other instances, or a completed workout. DST behavior is deterministic. |
| AC-006 | Owner changes, Google-account changes and active-workout/race paths cannot apply calendar or Google work for another owner. |
| AC-007 | Backend validation, references, tombstones, GET `/sync`, `/sync/changes`, `/records`, and writes support the capability additively, without a blanket protocol/version gate. |

## Frozen shared contract

### Records and references

All IDs are canonical lowercase UUID strings. Cloud record `id` is the aggregate UUID; payloads contain no SQLite IDs, Google token, grant, or event ID.

| Kind | Exact payload | Rules |
|---|---|---|
| `calendar_plan` | `routineId: UUID`, `startsAtMillis: epoch-ms`, `timeZoneId: IANA ZoneId`, `legacyScheduleId: UUID?` | `startsAtMillis` is the fixed one-off instant. `routineId` must refer to a live routine. `legacyScheduleId`, if present, is unique per owner. |
| `calendar_rule` | `routineId: UUID`, `isoDay: 1..7`, `localTime: "HH:mm"`, `timeZoneId: IANA ZoneId`, `startLocalDate: YYYY-MM-DD`, `legacyRuleKey: String?` | Local wall time recurs weekly from inclusive start date. `legacyRuleKey`, if present, is unique per owner. |
| `calendar_exception` | `ruleId: UUID`, `instanceKey: "YYYY-MM-DDTHH:mm[ZoneId]"`, `kind: "CANCELLED"|"MOVED"`, `movedAtMillis: epoch-ms|null` | Record UUID is `UUID.nameUUIDFromBytes("ValerochkaGym.calendar-exception:v1:<ruleId>:<instanceKey>".toByteArray(UTF_8))`. Key is derived from the rule's original local date/time/zone. `CANCELLED` requires null; `MOVED` requires non-null. A unique `(ruleId, instanceKey)` represents one exception. |

Local Room tables mirror those records with UUID primary keys and `routineSyncId`/`ruleId` references resolved in transactional repository operations. A `calendar_google_links` table is local-only: `objectKind`, object UUID, `ownerEmail`, `calendarId`, `eventId`, status/error/revision metadata. It is owner-bound and excluded from `PortableData`; a backend login is never a Google grant. CAL-02 will use a deterministic external identity/lookup from the plan UUID so a second device without a local link cannot create a duplicate event.

For a legacy `scheduled_workouts` row, compute its current portable `legacyScheduleId` from `calendarEventId`, then compute plan UUID `UUID.nameUUIDFromBytes("ValerochkaGym.calendar-plan:v1:<legacyScheduleId>".toByteArray(UTF_8))`; never use its local `Long id`. A DataStore `DayRule` rule UUID/source key is exactly `UUID.nameUUIDFromBytes(listOf("ValerochkaGym.calendar-rule:v1", owner, routineSyncId, isoDay.toString(), hhMm).joinToString(separator = 0x1F.toChar().toString()).toByteArray(UTF_8))`, where `0x1F.toChar()` is the single U+001F unit-separator character, `<owner>` is `normalizeEmail(ownerEmail)` or literal `∅` when null, `routineSyncId` is canonical lowercase UUID, and `hhMm` is zero-padded `HH:mm`. It likewise never uses `routineId`. The migration captures its `ZoneId`, nullable legacy owner, and weekly `startLocalDate` anchor **once** in durable `calendar_migration_metadata` before any copy. One-offs retain their original instant; each weekly rule uses the captured device ZoneId and captured local-date anchor, never a restart/current zone/date. A remote canonical record with the same deterministic UUID is applied by normal sync conflict policy; migration never recomputes it after restart. Legacy ad-hoc rows lack an owner-bound Google identity too: their event IDs are unbound/quarantined and no current Google account is inferred.

### Time and lifecycle

One-offs preserve an instant plus IANA zone for display. Rules preserve wall time. At a DST gap, resolve to the first valid instant after the gap; at overlap, choose the earlier offset. The exception key remains the original local time/zone, even for a moved occurrence. New input is limited to useful representable dates `1970-01-01` through `2100-12-31`: a plan instant must render to a local date in its declared zone within that inclusive range; a rule `startLocalDate`, exception original key date, and moved instant rendered in the rule zone meet the same range. Backend validation resolves the referenced rule to validate an exception. Legacy records outside this range are preserved in the legacy projection/quarantine and are not converted, dropped, or synthesized into new kinds. Editing/cancelling a plan cannot delete a completed workout. A rule routine-only edit preserves exceptions. Any edit of a rule's local time, zone, or start anchor creates a new rule UUID and atomically tombstones all old exceptions, then the old rule, before adding the replacement; stale exception keys therefore cannot land on a different instance. Deleting a rule likewise sends exception tombstones before the rule tombstone; a deletion is valid only when the post-batch graph has no live child reference. Routine deletion is rejected while live plans/rules reference it unless an explicit future migration handles it.

### Capability negotiation

`calendar-plans` is an optional capability, not a sync-version bump. Android sends `X-Gym-Capabilities: calendar-plans` with GET `/sync`, GET `/sync/changes`, GET `/records/*`, and POST `/sync`; server responds with the accepted intersection in the same header. Android persists last accepted capabilities **scoped to the current backend owner** in Room sync state. It clears that cache on owner change, absent response header, or accepted-capability downgrade, then filters new kinds from snapshot, changes and outgoing batches until accepted. Dirty plan data remains local for a later capable server. A durable outbox containing calendar kinds while capability is absent is retained byte-for-byte, reported pending/unsupported, and not sent, deleted, or rebuilt until capability returns.

Server visibility is `legacyKinds` for absent capability and `legacyKinds + calendarKinds` when accepted. It applies the same filter before `/sync` snapshot construction, changes pagination/cursor construction, list records and single-record lookup. It rejects a POST containing calendar kinds without the capability (`426 capability_required`) but leaves all legacy payloads and the existing v2/v3 guards unchanged. There is no account-wide min version or blanket protocol gate.

### Migration gate and execution

The Room migration creates plan/rule/exception/link/quarantine/migration-state/metadata tables. A singleton `CalendarLegacyMigration` first writes captured metadata (source fingerprint, captured ZoneId, captured weekly start anchor and observed nullable owners), then owns `PENDING → ROOM_COPIED → DATASTORE_MARKED → READY`. It copies source rows idempotently by unique source UUID/key, writes an owner-bound raw legacy journal quarantine (owner can be null but is never inferred), writes a DataStore completion marker, then re-fingerprints both sources before `READY`. It serializes retry against the captured metadata and never deletes `scheduled_workouts`, `weekly_schedule`, or `weekly_schedule_operations`.

`GymApplication` gates BackendSync startup, `AccountViewModel` gates interactive claim/sign-in, and `WeeklyScheduleRecoveryWorker`/old `WeeklyScheduleRepository` gate every Google call on this state: before `READY` they run/await only the serialized migration; no calendar upload, remote legacy apply, claim, or Google replay occurs. Existing legacy outbox bytes remain untouched. After `READY` the old journal is permanently quarantined and is never replayed. `SyncSchema.trackedTables` explicitly adds only `calendar_plans`, `calendar_rules`, and `calendar_exceptions`; `calendar_google_links`, migration state/metadata and quarantine are excluded. `PortableData` emits/apply-orders `calendar_plan → calendar_rule → calendar_exception`, deletes in reverse, and keeps the legacy `schedule` projection. Guest claim includes all three new tracked kinds; `OWNED(A)→B` clears their personal rows, baseline/outbox and owner-scoped capability cache together, while local Google metadata is cleared/quarantined locally and never crosses the wire. UI de-duplication is display-only: `PortableData.snapshot`, tombstone inference, and cloud `schedule` projection continue to include legacy schedules. A post-READY `CalendarLegacyBridge` transactionally converts an old-client legacy schedule upsert/delete to its matched deterministic plan (or applies ordinary conflict policy if it diverged), records the bridged remote revision, and preserves both representations; it never hides data from sync. CAL-02 may reconcile only a confirmed same-owner quarantined link/journal.

## Current and target flow

Current: CalendarRepository calls Google insert/delete before `scheduled_workouts`; weekly rules live in a single DataStore JSON and a Google create/delete journal; `PortableData` serializes `schedule` from Google event ID.

Target:

```text
Calendar UI event → CalendarPlanRepository → Room transaction (plan/rule/exception + dirty trigger)
   → Room Flow → immutable CalendarViewModel state → existing screen
   → BackendSync (only after accepted calendar-plans capability) → durable outbox → ACK baseline

legacy Room + DataStore + pending Google journal → migration gate/quarantine → Room plans
                                                ↘ CAL-02 only: same-owner Google reconciliation
```

Room is the calendar SSOT. ViewModel keeps immutable loading/migrating/empty/content/error state and one-shot messages; it uses `stateIn(WhileSubscribed(5000))`, `SavedStateHandle` only for current UI draft, and no domain state in Compose. CalendarPlanRepository owns its injected dispatcher and cancellation; transaction writes run main-safe, `CancellationException` is rethrown. BackendSync's existing mutex/owner and active-workout apply guards remain authoritative.

## Tasks and ownership

| Task | Exact files / owner | Depends | Action and done condition | Automated verification | AC |
|---|---|---|---|---|---|
| T-001 | **Root contract owner**, creates byte-identical fixtures in the two implementation checkouts: Android `app/src/test/resources/cal01-sync-contract.json`; backend `src/test/resources/cal01-sync-contract.json` | — | Freeze the three payloads, UUID derivation, DST, capability header/filter matrix, reference/tombstone order, captured metadata, migration/startup gate, legacy bridge and Google boundary. The fixture is UTF-8 LF canonical JSON with sorted keys and exactly this semantic content: `{"capability":"calendar-plans","kinds":["calendar_exception","calendar_plan","calendar_rule"],"calendar_plan":{"routineId":"UUID","startsAtMillis":"epoch-ms","timeZoneId":"IANA","legacyScheduleId":"UUID?"},"calendar_rule":{"routineId":"UUID","isoDay":"1..7","localTime":"HH:mm","timeZoneId":"IANA","startLocalDate":"YYYY-MM-DD","legacyRuleKey":"String?"},"calendar_exception":{"id":"nameUUID(ruleId,instanceKey)","ruleId":"UUID","instanceKey":"YYYY-MM-DDTHH:mm[ZoneId]","kind":["CANCELLED","MOVED"],"movedAtMillis":"epoch-ms|null"}}`. | `cmp` the two fixture paths; `shasum -a 256` recorded in both trackers | AC-001–007 |
| T-002 | **Android Room writer, own Android checkout** — `data/db/{GymDatabase.kt,dao/CalendarPlanDao.kt,entity/CalendarPlanEntity.kt,entity/CalendarRuleEntity.kt,entity/CalendarExceptionEntity.kt,entity/CalendarGoogleLinkEntity.kt,entity/CalendarMigrationStateEntity.kt,entity/CalendarMigrationMetadataEntity.kt}`; `data/calendar/{CalendarPlanRepository.kt,CalendarLegacyMigration.kt,CalendarLegacyBridge.kt,CalendarTimeResolver.kt}`; `data/backend/{PortableData.kt,SyncSchema.kt,BackendModels.kt,BackendApi.kt,BackendSync.kt,BackendSyncWorker.kt}`; `data/google/CalendarRepository.kt`; `data/schedule/{WeeklyScheduleRepository.kt,WeeklyScheduleOperation.kt}`; `worker/WeeklyScheduleRecoveryWorker.kt`; `ui/calendar/{CalendarViewModel.kt,CalendarScreen.kt,CalendarSheets.kt}`; `di/{DataModule.kt,GoogleModule.kt}` only as required; `GymApplication.kt`; schema `<N+1>.json`; migration/tests below | T-001 | Sole Room owner implements captured metadata, entity/DAO/migration/schema, startup/claim/recovery gate, durable gate/quarantine/bridge, local plan repository/UDF source switch, retained legacy cloud projection, portable adapters and owner-scoped client capability filtering. Existing Google calls are gated/quarantined; no reverse polling/event create. Done when guest offline local commit, source preservation, capability-disabled retained outbox and no partial apply compile. | `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests "*CalendarPlan*" --tests "*Calendar*" --tests "*WeeklySchedule*" --tests "*BackendSyncTest"` | AC-001–006 |
| T-003 | **Backend writer, own backend checkout** — `/Users/raul/ItmoProjects/ValerochkaGymBackend/src/main/kotlin/tech/valerochkagym/{controller/data/DataController.kt,service/data/SyncService.kt,service/data/RecordValidator.kt}`; `src/main/resources/db/changelog/007-calendar-plans.sql`, `master.yaml`; `src/test/kotlin/tech/valerochkagym/BackendIntegrationTest.kt` | T-001; parallel with T-002 only after contract acknowledgement | Add additive Liquibase kind/CHECK migration, validation/reference/tombstone rules, capability parser/response header and visibility filtering across snapshot/changes/records/write paths. Done when old no-header fixtures see exact legacy contract and capable fixtures validate new kinds/atomic references. | `./gradlew test --tests '*BackendIntegrationTest*'`; `./gradlew bootJar` | AC-004,005,007 |
| T-004 | **Android Room writer** — `app/src/test/.../data/{CalendarPlanRepositoryTest.kt,CalendarLegacyMigrationTest.kt,CalendarPlanDaoTest.kt,BackendSyncTest.kt,CalendarTimeResolverTest.kt,CalendarLegacyBridgeTest.kt}`; `ui/CalendarViewModelTest.kt`; `worker/WeeklyScheduleRecoveryWorkerTest.kt`; `data/db/Migration<N>To<N+1>Test.kt`, `Migration1To<N+1>Test.kt` | T-002 and backend contract fixture from T-003 | Real Room/fault tests: exact UTF-8/U+001F weekly UUID for normalized/null owner; captured zone/anchor/nullable owner survives restart and cross-device canonical conflict; every migration boundary/recreation; legacy UUID dedup across local IDs; DataStore marker/quarantine/owner switch; startup/claim/sync gate and untouched legacy outbox; all three tracked categories portable/guest-claim/A→B clearing while Google metadata stays off-wire; capability-off owner-cache downgrade and retained outbox/accepted upload; legacy cloud upsert/delete bridge; accepted 1970–2100 temporal bounds and preserved out-of-range legacy; DST gap/overlap; deterministic exception UUID; rule-edit exception replacement; references/tombstones; active race. Done when all named regressions pass with handwritten fakes. | targeted Android test command from T-002 plus migration classes | AC-001–007 |
| T-005 | **Android Room writer, shared choke point** — `app/build.gradle.kts`, tracker | T-004 | Make one coherent Android `versionCode` and patch `versionName` increment; record actual `N`, evidence and deviations. | `./gradlew :app:compileDebugKotlin` | exactly one increment | AC-001–007 |
| T-006 | **Independent Android tester** (sole Android Gradle owner) + **Sol/high reviewer** (read-only), tracker; backend writer runs backend checks only | stable T-005 and T-003 | Parallel AC audit and strict review of migration, capability leak, owner/Google quarantine, time/DST, references/tombstones and UI accessibility. Return one consolidated findings packet. | only missing/invalidated targeted commands | no open P0/P1 | AC-001–007 |
| T-007 | original owner(s), only files named by confirmed finding; root resolves cross-checkout integration | T-006 if needed | One bounded fix batch, targeted rerun and reviewer recheck. | affected command | no unresolved P0/P1 | affected |
| T-008 | **Root** | T-006/T-007 | Integrate checkouts; run Android final gates once sequentially. Backend owner independently runs its final checks and Docker validation before handing evidence to Root. | Android: `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug`. Backend (in backend checkout): `./gradlew test --tests 'tech.valerochkagym.BackendIntegrationTest'`; `./gradlew bootJar`; `docker compose config`; `docker compose up --build --wait` then `docker compose down`. | final tracker evidence includes independent backend command results | AC-001–007 |

`N` is the actual predecessor Room version immediately before T-002. The Android writer alone owns all Room entity/DAO/migration/schema files; the backend writer never edits Android files. No Gradle process runs while an Android writer is editing.

## Test matrix and gates

- Android: migration incremental and full supported path; one-time captured zone/start anchor/nullable owner and cross-device canonical record; Room transaction/fault injection across every gate; DAO foreign keys/unique source keys; PortableData legacy schedule plus new-kind projection/apply/guest merge and post-ready bridge; backend startup/interactive-claim/recovery gate with preserved legacy outbox; owner-scoped capability cache downgrade and durable unsupported outbox; no foreign Google replay; timezone gap/overlap, deterministic instance key/exception UUID and rule-edit exception replacement; ViewModel loading/error/content/migrating, semantic actions, 48dp, `fontScale=2.0`, compact/expanded layout.
- Backend: additive Liquibase migration; no-header snapshot/changes/records remains six legacy kinds and byte-compatible legacy payload; header acceptance exposes only new kinds; unsupported write rejects without partial revision; valid graph and co-batch tombstone order; wrong UUID/zone/time/reference/exception shape rejected; owner authorization/idempotent operation ledger preserved.
- Final: independent tester and Sol/high reviewer after both stable slices; root alone runs `:app:testDebugUnitTest` then `:app:assembleDebug`. `assembleRelease` only if a release-sensitive dependency/build/manifest change appears. No chart, runtime permission, foreground-service, reverse-Google, or AI gate applies.

## Risks, unresolved decisions and rollback

The Room/DataStore boundary cannot be atomic, so captured metadata, phased marker, source preservation and fault tests are mandatory. A calendar-capable app connected to an old server retains dirty plans locally until an accepted response; it must not send or discard them under a legacy capability. Backend capability filtering must cover changes cursors and records endpoints, or records can leak despite a safe snapshot. Legacy cloud projection is retained deliberately; only UI hides matched duplicates.

No migration deletes source records or replays a legacy Google journal. Rollback retains legacy source and migrated Room rows; durable tombstones preserve backend deletion semantics. CAL-02 owns external deterministic identity, actual Google writes/reads and reconciliation. Routine-copy reference reassignment and manual conflict policy are intentionally unresolved and do not block the frozen CAL-01 contract. Temporal bounds are frozen above as 1970–2100 inclusive.

## Gate P self-check

PASS: AC-001…007 map to named tasks and checks; calendar payload, references, UUID migration, capability filtering and final-ACK-compatible outbox semantics are frozen before parallel writers; Room/schema has one Android owner; backend is isolated to its checkout; migration/legacy Google safety, DST, guest merge, tombstones and compatibility gates are all explicit; no CAL-02/AI/month UI scope leaked in.

## Preflight после #43 и перед Android T-002 (10.09.2026)

T-002 начинается только после принятия guest-sync Room18; фактическая следующая
миграция CAL-01 — 18→19, schema19, `Migration18To19Test` и `Migration1To19Test`.
Это уточнение базы, без изменения замороженного wire-контракта.

Room17 уже содержит `calendar_event_account_links` с PK `scheduledWorkoutId`,
nullable owner и состояниями OWNED/LEGACY_OWNER_UNKNOWN. CAL-01 создаёт отдельную
`calendar_google_links` с UUID объектов рядом с этой таблицей. Старые immutable
triggers и источник сохраняются; существующее доказательство владельца переносится
или карантинируется транзакционно через deterministic legacy plan identity,
никогда через SQLite ID или текущий Google account. Добавить регрессии обоих
состояний, coexistence/cascade и отсутствия коллизий имён triggers.

Миграционный gate обязан сработать до существующего adoption nullable weekly owner
в `WeeklyScheduleRepository`: capture читает исходные DayRule/owner и сырой журнал,
не вызывает adoption. Account-link evidence следует тому же legacyScheduleId,
который вычисляет PortableData из calendarEventId. Расширить RoomDaoTest настройкой
новых invariants, сохранив проверки Room17 и Room18. Канонический fixture копируется
байт-в-байт в Android test resources только после стабильной базы Room18.
