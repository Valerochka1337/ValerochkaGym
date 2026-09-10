# CAL-01 local calendar tracker

Strict Gate P final recheck PASS: все 11 замечаний и уточнение UTF-8/границ дат закрыты.
Backend slice разрешён раньше Android Room slice после заморозки общего контракта.
Канонический fixture: `vibe/contracts/cal01-sync-contract.json`, SHA-256
`d841e2a65037ef94993575ac2dea4172ffaa272cdde63baa2a6867ba0ed29911`.
Backend `src/test/resources/cal01-sync-contract.json` byte-identical (`cmp` PASS).
Android writer копирует эти же байты в `app/src/test/resources/cal01-sync-contract.json`
при старте T-002; пока fixture остаётся в vibe, чтобы не смешивать app-тесты разных фич.

| Task | Status | Owner | Depends | AC | Evidence / done condition |
|---|---|---|---|---|---|
| T-001 | done | Root contract owner | — | AC-001–007 | Canonical vibe fixture и backend fixture совпадают, SHA-256 выше; Android test-resource copy обязательна перед T-002. |
| T-002 | done | Android Room writer | T-001 | AC-001–006 | Room v19, source switch, portable kinds, startup/capability gate, quarantine and local calendar UDF are implemented with durable migration capture and restart coverage. |
| T-003 | done_local | Backend writer | T-001 | AC-004,005,007 | Отдельный checkout /Users/raul/ItmoProjects/ValerochkaGymBackend, feat/calendar-plan-contract; backend integration tests + bootJar обязательны. |
| T-004 | done | Android Room writer | T-002,T-003 fixture | AC-001–007 | Matrix closed: all-three guest claim, canonical legacy conflict, child-first tombstones/references/temporal bounds, Room17 known/unknown owner coexistence, captured bridge zone, and migration/claim/sync/active/stale-owner race guards have focused regression coverage. |
| T-005 | done | Android Room writer | T-004 | AC-001–007 | `versionCode` 38 / `versionName` 1.3.30 is the single CAL-01 increment; Kotlin compile passes. |
| T-006 | done | Independent tester + Sol/high reviewer | T-003,T-005 | AC-001–007 | Consolidated Gate T/V verdict; no open P0/P1. |
| T-007 | done | Original owner(s), Root integration | confirmed T-006 findings | affected | Bounded fix and recheck evidence. |
| T-008 | done_local | Root | T-006/T-007 | AC-001–007 | Android full units then debug assembly; independent backend test, bootJar and Docker evidence. |

## AC traceability

| AC | Tasks | Tests |
|---|---|---|
| AC-001 | T-002,T-004,T-008 | offline guest plan/rule CRUD; independent pending/error sync state; ViewModel states. |
| AC-002 | T-001,T-002,T-004 | repeated commands/recreation; deterministic plan UUID from portable legacyScheduleId; exact UTF-8/U+001F rule namespace with normalized/null owner; exception UUID from rule/key; captured zone/anchor and no local-ID dependence. |
| AC-003 | T-002,T-004,T-006 | incremental/full migration, captured-metadata and phase fault injection, source fingerprints, startup/claim/sync gate, owner-bound journal/unbound ad-hoc quarantine and no replay. |
| AC-004 | T-001–004,T-008 | all three `SyncSchema.trackedTables` calendar kinds in portable order, guest claim and A→B clearing; Google metadata excluded; owner-scoped capability-off/downgrade retained outbox; accepted header sync/new kinds; legacy schedule projection/upsert/delete bridge; legacy visibility fixtures. |
| AC-005 | T-001–004,T-006 | 1970–2100 temporal validation with preserved out-of-range legacy, DST gap/overlap, stable original instance key and deterministic exception UUID, rule-edit exception replacement, live-reference/tombstone graph, completed workout preservation. |
| AC-006 | T-002,T-004,T-006 | active apply race, stale backend owner, Google owner/account switch, recovery gate worker tests. |
| AC-007 | T-001,T-003,T-004,T-008 | backend validator/changes/records/write capability tests, additive Liquibase, Android portable/compatibility tests. |

## Frozen findings

- Existing `CalendarRepository` writes Google before Room, and `WeeklyScheduleRepository` owns a replayable Google journal; CAL-01 reverses local authority and quarantines the journal before CAL-02.
- Existing `PortableData.schedule` UUID derives from `calendarEventId`. Migration must derive the new plan from that portable UUID, never the Room auto-ID, and keep the legacy schedule cloud projection while UI de-dups it. New rule IDs use an exact UTF-8 U+001F namespace with normalized/null owner marker.
- Backend `RecordValidator.kinds`, SyncService snapshot/changes and DataController records endpoints currently expose six kinds without capability filtering; all four paths require the same capability policy. Android capability cache is owner-scoped and clears on absent/downgraded response.

## Commands and deviations

- Planning only: no Gradle, backend test, Git or production-file mutation ran.
- `<N>` and new backend changelog number are resolved by their respective writer immediately before implementation.
- No deviation accepted.

## Android implementation evidence (T-002/T-004/T-005, 10.09.2026)

- `cmp vibe/contracts/cal01-sync-contract.json app/src/test/resources/cal01-sync-contract.json` and
  both SHA-256 values: PASS, `d841e2a65037ef94993575ac2dea4172ffaa272cdde63baa2a6867ba0ed29911`.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:compileDebugKotlin`: PASS.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests '*CalendarPlan*' --tests '*Calendar*' --tests '*WeeklySchedule*' --tests '*BackendSyncTest'`: PASS.
- Narrow migration/UI/time check: `--tests '*Migration18To19Test' --tests '*CalendarTimeResolverTest' --tests '*CalendarViewModelTest'`: PASS.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests '*BackendSyncTest' --tests '*CalendarLegacyMigrationTest' --tests '*Migration18To19Test'`: PASS. This covers absent-header exact calendar outbox retention, owned A→B cleanup, terminal READY, injected zone/date capture, and disk Room/DataStore restart after the READY-marker fault.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests '*CalendarPlanRepositoryTest' --tests '*PortableDataTest' --tests '*CalendarViewModelTest'`: PASS. This covers saved command UUID retry/recreation, matching/mismatched Room idempotency, routine-only exception retention, child-first replacement, and post-READY remote legacy upsert/update/divergence/delete bridge behavior.
- CAL01 legacy quarantine/UI boundary: `WeeklyScheduleRepositoryImpl` is now a read-only
  DataStore facade. Pending migration pauses it; READY quarantines it; neither state adopts a
  nullable owner, clears/replays the journal, schedules recovery, or reaches Google. Calendar UI
  exposes preparing/error/retry separately from pending/unsupported cloud capability while Room
  history stays visible and local commits remain local successes.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests '*WeeklySchedule*' --tests '*WeeklyScheduleRecovery*' --tests '*CalendarViewModel*' --tests '*CalendarPlanRepository*' --tests '*CalendarStatusBannerTest' :app:compileDebugKotlin spotlessCheck`: PASS. Covers known/null legacy owners, raw-source/journal retention, no account recovery wake, migration UI retry, unsupported cloud local success, and semantic retry action.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew spotlessApply`: PASS. No deviation; it formatted CAL01 files already modified in this working tree.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests '*CalendarPlanRepositoryTest' --tests '*CalendarLegacyMigrationTest' --tests '*Migration17To19Test' --tests '*Migration18To19Test' --tests '*PortableDataTest' --tests '*CalendarViewModelTest' --tests '*BackendSyncTest' --tests '*WeeklyScheduleRecoveryWorkerTest' --tests '*WeeklyScheduleRepositoryTest' --tests '*CalendarTimeResolverTest' --tests '*CalendarInstancesTest' --tests '*CalendarStatusBannerTest' :app:compileDebugKotlin spotlessCheck`: PASS. Covers the final T-004 matrix, including owner-proof migration, captured bridge zone, stale-owner delayed write rejection, and user-facing one-off/recurring move/cancel commands.
- T-006 fix batch: `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests '*BackendSyncTest' --tests '*CalendarViewModelTest' :app:compileDebugKotlin spotlessCheck`: PASS. Generated `CloudPush` now creates `calendar_plan → calendar_rule → calendar_exception` and tombstones them in reverse; moved-instance commands retain the original rule-local date from `instanceKey`.
- T-007 fixture correction: historical v9/v10/v11 recovery fixtures now remove v19 calendar objects and the two v19 `backend_state` capability columns before the production migration chain. `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :app:testDebugUnitTest --tests '*Migration9To12Test' --tests '*Migration10To12Test' --tests '*Migration11To12Test' --tests '*Migration1To19Test' --tests '*Migration17To19Test' --tests '*Migration18To19Test' --tests '*CalendarLegacyMigrationTest' :app:compileDebugKotlin spotlessCheck`: PASS.

### T-004 matrix closed

Gate I is complete. The targeted regressions cover guest claim for all three kinds, canonical
legacy conflict, tombstones/references/1970–2100 bounds, Room17 known and unknown account-link
coexistence, and fail-closed recovery, interactive claim, sync, active-workout and stale-owner
write races. No deviation is accepted.

## Residual risks

- Legacy rows lacking a portable Google identity remain preserved/quarantined instead of being guessed across devices.
- CAL-02 must supply deterministic Google external identity and same-owner reconciliation before any external event operation is re-enabled.
- Legacy schedule projection remains synchronized for old clients; bridge behavior must not use UI suppression as a data-loss shortcut.
- A server that ignores the capability header leaves calendar changes locally dirty by design.

## Takeover preflight 10.09.2026

Read-only researcher сверил принятую модель с #43 и текущим guest-sync. В план
добавлены точные deltas: stable Room18 dependency, 18→19, coexistence старой
owner-link таблицы, migration-before-weekly-adoption, identity regressions и
RoomDaoTest setup. Новый продуктовый scope не добавлен. Android T-002 pending.
Backend T-003 фактически завершён локально ранее: c73c411, Gate T/V PASS,
full check/bootJar44tests0failures; публикация upstream ограничена READ правами.

## Final local acceptance — 2026-09-10

Independent T/V PASS after outgoing graph order and original recurring-date fixes.
First full suite exposed three historical fixture defects; fixtures corrected without changing
production migrations and independently rechecked. Final full unit suite:1127 tests,0 failures,
0 errors,1 skipped,6m18s PASS; debug assembly PASS8s. Commands: JDK21 `./gradlew --no-daemon
:app:testDebugUnitTest --init-script /private/tmp/yarumo-test-isolation.gradle --console=plain`,
then `./gradlew :app:assembleDebug --console=plain`. Logs:
`/private/tmp/yarumo-partial-cal01-full-recheck.log`, `/private/tmp/yarumo-partial-cal01-debug.log`.
Control APK `/private/tmp/yarumo-cal01-v38-debug.apk`. Room19,version38/1.3.30. No screenshots
opened, no publication/commit. Subsequent partial-feature work continues on local
`feat/partial-completion`; all CAL01 changes preserved. CAL02 remains excluded.
