# CAL-02 — синхронизация локальных планов с Google Calendar

Slug: `google-calendar-sync`. Это строгий план после CAL-01 и этапа 08. Он переносит только будущие локальные планы в явно подключённый Google Calendar и превращает внешние изменения только принадлежащих приложению событий в предложения. CAL-01 остаётся источником правды и единственным portable-контрактом; новые поля в `PortableData` или backend не добавляются.

## Цель, scope и non-goals

После явного подключения одного Google account приложение выгружает все будущие one-off plans и recurring masters, а затем безопасно сверяет изменения. Автопроверка при открытии ограничена одним запуском за шесть часов на account; ручное обновление обходит throttle. Local edit/cancel всегда сначала остаётся локальным и может работать offline.

Не входят: импорт foreign events, создание calendar, push/watch, прошлые one-off планы, AI, изменение всего series через одиночную exception, grant/claim при backend login, перенос OAuth ownership, изменения completed/history snapshot и новые backend/portable schema fields. Неподдержанные RRULE или all-day события остаются видимым конфликтом без записи в Google.

## Acceptance criteria

| ID | Criterion |
|---|---|
| AC-001 | После explicit connect каждый future one-off и master с хотя бы одним future occurrence имеет ровно одно owned remote event; offline/retry/crash/two devices не создают дубликат. One-off eligible при `startsAt >= now`; master экспортируется вместе с CAL-01 rule/exceptions, даже если DTSTART в прошлом. |
| AC-002 | Remote ID для one-off/master детерминирован: `c` + lowercase SHA-256 hex of UTF-8 `Yarumo.calendar.v1|<planUuid>|<kind>`. Exception использует только natural Google instance ID/ETag из paged `events.instances`, сопоставленный по masterId/originalStartTime с CAL-01 instanceKey. Fixture фиксирует inputs/output. Один и тот же план на двух устройствах того же Google account получает один ID; `409` принимается только после `get` и независимой проверки metadata + account-bound mapping. |
| AC-003 | Каждая link, cursor, throttle, proposal и journal привязаны к normalized explicitly connected email и ownerEpoch. A→B/sign-out/revocation не читает, не пишет и не подтверждает A под B. Backend login сам не выдаёт/не переносит Google grant; B может выгрузить local plans только после нового explicit connect. Legacy ownerless state карантинируется без API call. |
| AC-004 | Local CAL-01 plan является SSOT: transport/auth/network/cancel failure не очищает local plan, link, last confirmed ETag или proposal и не создаёт empty remote snapshot. Metadata хранится отдельно для каждого Calendar account. |
| AC-005 | Auto-open sync максимум раз в 6h на account; manual refresh bypass. `events.list` всегда unfiltered `singleEvents=false`, `showDeleted=true`, fixed page size and stream-discard foreign events. Cursor commit атомарен только после всех pages; 410 очищает только cursor/derived remote observations и запускает full verified reread. |
| AC-006 | Foreign events не попадают в Room/DataStore и не изменяются. Minimal cancelled one-off/master требует ранее verified mapping. Новая cancelled exception принимается без прежней instance link, если master verified owned и originalStartTime соответствует каноническому экземпляру локального правила; иначе discarded. Ownership requires both private app marker/plan UUID/kind/account source metadata and matching local account-bound link. |
| AC-007 | Remote one-off move/delete создаёт versioned proposal (old/new, remote ETag, ownerEpoch, whole-aggregate canonical content fingerprint and local revision). Confirm сравнивает все captured values before local apply; mismatch refreshes/re-proposes. No proposal touches active workout/rest. |
| AC-008 | Proposal default **Defer** is an in-screen card, not a popup, and makes no write. **Keep local** records suppression for exactly `(ownerEpoch, remoteETag, local aggregate fingerprint)` and never repairs Google implicitly. A new local edit or newer ETag requires explicit new reconciliation; no periodic popup. |
| AC-009 | Recurring pulls match master by ID and exception by `(recurringEventId, originalStartTime)`. A moved instance retains the CAL-01 original instance key and absolute moved instant; a cancelled instance stays cancellation. An individual exception never mutates master/RRULE/other instances. |
| AC-010 | Remote master delete offers explicit series cancellation with future affected-instance count. A supported weekly remote time/date/zone change offers series-replacement preview: ordinal occurrence remapping preserves cancellations and moved absolute instants, displays affected count, and applies only after explicit confirmation. Unsupported RRULE/all-day is visible conflict/no write; history stays immutable. |
| AC-011 | Every app update/delete carries last verified ETag in `If-Match`; 412/get mismatch creates or refreshes proposal, never overwrites. Before any proposal confirmation or outbound operation, compare ownerEpoch + remote ETag + canonical fingerprint of full aggregate (master rule plus all exceptions) + local revision. |
| AC-012 | Durable account-bound journal is written before remote call, stores deterministic target identity/revision/ETag/step and replays single-flight. Unknown/corrupt/dead-letter journal fails closed, blocks new remote operation and exposes recovery state; ambiguous network replay uses the same deterministic ID and verification, not a second insert. |

## Current and target data/execution flow

```text
CAL-01 Room plans/rules/exceptions (SSOT)
 → CalendarGoogleSyncRepository serializes one account + writes durable operation journal
 → AccountBoundGoogleAuth(expectedEmail) → CalendarApi list/get/insert/update/delete
 → Room transaction: verified link/remote observation/proposal/cursor only
 → worker/app-open request → immutable CalendarSyncUiState → Calendar card/actions
```

`CalendarPlanRepository` retains all local mutations and uses no Google transport. `CalendarGoogleSyncRepository` is the only owner of Google I/O and exposes flows; it captures a CAL-01 aggregate snapshot on the compute dispatcher before building a DTO. `CalendarSyncWorker` invokes that repository under unique account-qualified work; it never accesses Compose state. A repository `Mutex` serializes UI/manual/worker requests, checks connected account and ownerEpoch before/after every suspend boundary, and rethrows `CancellationException`.

Every app-created event uses private extended properties: app marker/version, canonical plan UUID, kind, optional original instance key, and a stable accountSourceFingerprint: lowercase SHA-256 of UTF-8 `Yarumo.calendar-account.v1|<authenticatedBackendOwnerUuid>|<normalizedGoogleEmail>`. The local ownerEpoch is never exported. The verifier is kind-specific: one-off/master require stable metadata, deterministic ID and the expected local plan/account (an existing link must agree; verified first-insert/409 recovery may create it). A recurring instance instead requires its natural ID, verified owned master, canonical originalStartTime/account source and matching existing instance link when present; never require a deterministic instance ID. Test positive natural instances and foreign-master/source negatives. Calendar event title contains no identity. No credentials go in Room or journal.

The Room `calendar_google_account_state`, `calendar_google_event_links` and `calendar_google_proposals` tables are derived per normalized email + ownerEpoch. A link includes kind/original key, event ID, last verified ETag, remote observation and suppression tuple. The journal is DataStore/AtomicFile consistent with the existing weekly-operation mechanism, excluded from backup, but stores no token; invalid decode/validation writes a durable dead-letter marker and pauses all sync for that account until explicit recovery. Actual Room predecessor version is `N`, found directly before source edits; one writer makes `N→N+1`, database registry and exported schema together.

## Frozen reconciliation rules

1. **Initial upload and idempotency.** On explicit connect only, enumerate future eligible local aggregates. Write journal before insert. On insert timeout/retry or 409, `get` deterministic ID and verify full ownership before creating/updating link; mismatched/foreign 409 becomes visible conflict. Never infer a successful write from a network error.
2. **Pull.** Full and incremental list requests keep identical unfiltered parameters (`singleEvents=false`, `showDeleted=true`, no time/property filters). Page processing streams; it stores neither foreign rows nor foreign IDs. `nextSyncToken` is committed only with all verified page effects. `410` retains local plan/link/proposal, clears cursor plus derived remote observation then full-reads; it does not generate deletion from omission.
3. **Deleted responses.** A minimal cancelled one-off/master needs a previously verified owned link; it cannot be required to contain metadata already removed by Google. A minimal cancelled object without retained verified link is discarded. A new cancelled recurring exception is accepted without a prior instance link when recurringEventId resolves to a currently verified owned master and originalStartTime canonically resolves to that rule occurrence/account source. Create the natural-ID link and versioned proposal; foreign master or invalid occurrence is discarded. For outbound instance move/cancel use paged events.instances lookup and its natural ID/ETag, never insert an independent deterministic event.
4. **Proposal version gates.** Capture one canonical UTF-8 fingerprint of the *whole* local aggregate, ownerEpoch, local revision and remote ETag for any one-off or series action. Confirm applies only when all remain equal. Defer retains the record/card. Keep Local records exact suppression and never schedules repair; a later user local edit clears suppression and queues ordinary conditional outbound reconciliation, while a newer ETag creates a new explicit proposal.
5. **Series.** Supported weekly master changes are a replacement preview, never a cascade of generated events. Map each retained exception by ordinal occurrence from old to new weekly rule: cancellations remain cancellations; moved records retain their absolute `movedAt` and receive the new original-instance key. Master delete offers future series cancel, retaining historical facts. If remap cannot be total, source RRULE is unsupported, or all-day fields appear, leave a conflict with no outbound/local change.
6. **Account gate.** Every DB apply, journal replay and API request checks exact connected email, current authenticated Calendar identity and ownerEpoch. Different/missing/revoked consent pauses, without cursor reset or mutation. Link recovery on another device is allowed only after private app marker + canonical plan UUID + expected account-source metadata verifies ownership; backend sign-in alone cannot establish it.

## Readiness and transactional race contract

Stable remote ownership and local ABA protection are distinct. Remote accountSourceFingerprint is
identical for the same authenticated backend owner UUID and Google email across devices/reconnects;
ownerEpoch is a local-only monotonic binding token used by journal/proposal/Room CAS. A different
backend owner sharing the same Google email must not adopt a previous owner's event.

Before new list/mutation/journal work require current owner's accepted `calendar-plans` capability,
settled OWNED state and no active workout. An account-wide list/pull starts only when ALL eligible
local calendar aggregates are acknowledged and the owner has no pending outbox or unresolved conflict.
A targeted outbound/approval operation requires its complete aggregate acknowledged and likewise no
owner pending outbox/conflict. Dirty/conflicted state permits zero reconciliation advancement and
preserves cursor, links, proposals and journal bytes until readiness returns. Guest local planning remains available; cloud Calendar work waits for this
readiness. Missing/downgraded capability preserves cursor, links, proposals and journal byte-for-byte,
makes zero Google calls, and pauses ambiguous journals until the same exact operation can resume.

Recheck owner/readiness and active-workout absence inside each Room transaction applying a proposal,
series remap, link/cursor or other reconciliation-state advancement. If a workout starts between the
precheck and transaction, defer without local-plan/history/rest mutation or advancing sync state.
Already dispatched HTTP may complete remotely; retain exact durable journal and verify its outcome
on resume, never claim a cancelled request guarantees no remote effect.

Mandatory regressions: same owner/account two devices; reconnect with new local epoch; two backend
owners sharing Google email; natural-ID instance lookup paging and conditional move/cancel; newly
cancelled unmapped instance under verified master and foreign-master negatives; capability
accepted→missing→accepted with clean and ambiguous states; pending-outbox and unresolved-conflict
account-wide list/targeted-operation zero-call, zero-advancement, byte-preservation tests; check→active-start→transaction races
for one-off and series approval and cursor advancement.

## Tasks

| Task | Files / owner | Depends | Action | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|
| T-001 | Root contract owner: `vibe/google-calendar-sync-contract.json`, Android `app/src/test/resources/google-calendar-sync-contract.json`, plan/tracker | CAL-01 UUID fixture, stage 08 | Freeze canonical UTF-8 LF fixture: one-off/master deterministic IDs, stable remote fingerprint vs local epoch, natural instance IDs/ETags and lookup pages, private metadata, DTO event shapes, unfiltered list parameters, 409/412/410, minimal cancelled rules, fingerprint/suppression tuple and ordinal remap examples. Reference CAL-01 UUID/original-instance keys, do not define portable fields. | `cmp`; `shasum -a 256` | Fixture hash and CAL-01 fixture references are recorded. | AC-001,002,005,006,009–012 |
| T-002 | One Android writer, sole Room/schema owner: `data/db/entity/{CalendarGoogleAccountStateEntity,CalendarGoogleEventLinkEntity,CalendarGoogleProposalEntity}.kt`, `data/db/dao/CalendarGoogleSyncDao.kt`, `data/db/GymDatabase.kt`, `app/schemas/.../N+1.json`, migration tests | T-001; actual CAL-01/08/12 integration | Discover N; add account-keyed derived metadata/proposal/link tables, indexes/FKs, manual migration and atomic DAO transactions. Quarantine legacy ownerless metadata; preserve plan tables, backend state/outbox and CAL-01 portable bytes. | targeted migration/DAO tests | Incremental and full migration prove per-account isolation, foreign keys, no loss of plans/outbox, dead legacy quarantine and schema export. | AC-003,004,012 |
| T-003 | Same Android writer: `data/google/{CalendarApi.kt,CalendarEventDto.kt,CalendarGoogleSyncRepository.kt,CalendarGoogleOperation.kt}`, `data/settings/SettingsRepository.kt`, `di/{GoogleModule.kt,DataModule.kt}` only if needed | T-001,T-002, stage 08 exact auth API | Add list/get/paged events.instances/conditional update/delete DTO transport with ETag and private properties; account-bound auth gate; deterministic ID/ownership verifier, canonical aggregate fingerprint, journal/dead-letter and 6h account throttle. Implement streaming page/cursor/410/409/412 and no foreign persistence. Gate all work by settled acknowledged CAL-01/capability state; preserve exact journals on downgrade. Test remote stable ownership across devices/reconnects and natural-ID instance lookup/mutation. | targeted repository/transport/journal tests; `:app:compileDebugKotlin` | Every remote operation is journaled/idempotent/account-checked; no broad Calendar read filter or foreign storage exists. | AC-001–006,011,012 |
| T-004 | Same Android writer: `data/calendar/CalendarGoogleReconciler.kt`, CAL-01 repository integration, `worker/CalendarGoogleSyncWorker.kt`, scheduler/GymApplication only as required | T-002,T-003 | Add proposal creation/version comparison, one-off delete/move, recurring master and exception matcher, ordinal replacement/cancel preview, suppression semantics and unique worker/manual refresh. App-open enqueues auto check only when connected and throttle permits; manual bypasses. | targeted reconciler/worker tests | Local source remains intact on offline/revoke/cancel; verified-master newly cancelled instances are covered; series and one-off tests exercise active-start before transactional apply/advance, plus capability loss/resume. | AC-004,005,007–012 |
| T-005 | Same Android writer: `ui/calendar/{CalendarViewModel.kt,CalendarScreen.kt,CalendarSheets.kt}` and focused tests | T-004 | Render immutable loading/connected/paused/error/conflict/proposal state. Add Manual refresh and in-screen Defer/Keep Local/confirm controls; preview affected exception count; no popup for Defer and no action during active workout. Use existing theme, semantics, haptics/motion and adaptive scaffold. | targeted VM/Compose tests including fontScale 2.0 | UI makes state/action and retained conflict visible without direct transport from Compose. | AC-007–010 |
| T-006 | Same Android writer: `app/build.gradle.kts`, tracker | T-002–005 | Compare target branch, increment version once, record actual N and fixture hash/Gate I evidence. | `:app:compileDebugKotlin` | One feature version bump only. | AC-001–012 |
| T-007 | Independent tester (sole Android Gradle owner) + Sol/high strict reviewer, read-only source | stable T-006 | Audit fixture, migration, owner/Google gates, cursor paging, journal crashes, 409/412/410, no foreign import, proposal/suppression/remap and accessibility. | only missing targeted tests | Gate T/V reports no P0/P1 or one consolidated fix packet. | AC-001–012 |
| T-008 | Original writer, confirmed files only | T-007 findings | Apply one consolidated fix batch and rerun smallest invalidated test; reviewer checks only fixed findings. | affected tests | No open P0/P1; residual P2 documented. | affected AC |
| T-009 | Root integration owner | T-007/T-008 | Validate fixture copies and run final Android gates once on stable diff. | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` | Final evidence recorded; no real Google write is claimed. | AC-001–012 |

## Ownership, waves and quality gates

One Android writer owns every production file, including Room/entity/DAO/migration/schema, Calendar API/DTO, journal, Worker, navigation/DI/version and UI. This deliberately avoids transaction/contract splits. Tester and reviewer are read-only after a stable diff; the tester alone starts Gradle. Wave 1 T-001; Wave 2 T-002→T-006 sequentially; Wave 3 T-007 parallel review/test; Wave 4 conditional T-008; Wave 5 T-009. No Gradle runs while writer edits.

Applicable gates: handwritten Room incremental/full migration and newest schema; WorkManager unique work/retry/cancel and no direct app-open network; account-bound OAuth/permission denial/revocation; no token persistence; deterministic transport and ETag concurrency; DataStore corrupt journal/backup exclusion; immutable UDF UI, loading/empty/error/content, semantic labels, 48dp, fontScale 2.0 and compact/expanded layouts. No manifest, new runtime permission, foreground service, dependency, chart or release gate applies; run `assembleRelease` only if implementation introduces release-sensitive configuration.

## Risks, rollback and Gate P

The Room/journal split cannot be atomic; pre-call durable identity plus verification after ambiguous outcome prevents duplicate insertion but may pause for recovery. Google’s unfiltered incremental stream can be large, so processing must discard foreign pages immediately and never materialize them. Remote series replacement is only safe for frozen weekly forms; unsupported shapes deliberately remain conflicts. Keep Local intentionally does not repair Google and therefore may leave visible mismatch until an explicit newer reconciliation.

Rollback is forward-only: new derived metadata can be ignored by an older app, while local CAL-01 plans stay intact. No rollback deletes mappings/journals or treats missing remote results as cancellation. Gate P strict final recheck PASS after root corrections: AC-001…012 each maps to tasks and checks; CAL-01 UUID contract is reused; one writer owns all mutable Android/Room choke points; owner/capability/worker/ETag/process-death/UI gates are explicit.
