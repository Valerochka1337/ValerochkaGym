# CAL-02 — Google Calendar sync: Feature Brief

**Status:** pass after root decisions below; implementation waits for strict plan review  
**Evidence date:** 10 September 2026  
**Files changed:** none (this brief is research only)

## Scope and non-goals

CAL-02 starts only after CAL-01 supplies offline local one-off plans, recurring rules, stable plan
identity/revision and single-instance exceptions. Once the user explicitly connects a Calendar
account, the app uploads **every future local plan** already present (one-off plans and recurring
masters), then reconciles changes to events it owns. Automatic checking runs on app open at most
once per six hours per Calendar account (D010); manual refresh bypasses that throttle.

The local plan store remains the offline source of truth. A Google change is a versioned proposal,
not a direct local mutation. A moved event may propose its new time; a deleted event may propose
cancellation. Completed workouts and their historical snapshots never change.

Out of scope: importing or persisting foreign Calendar events; continuous monitoring/push;
AI weekly planning; changing an entire series through an exception; creating another Google
calendar; uploading past one-off plans; and reconciling any Calendar data while authorization is
cancelled, revoked, unavailable, or bound to a different account.

## Candidate acceptance criteria

- **AC-001 — Initial future upload.** After explicit Calendar connection, every eligible future
  local one-off plan and recurring master has exactly one owned remote event. Reopening, retrying,
  process death and two devices holding the same local plan identity do not create a duplicate.
- **AC-002 — Deterministic ownership.** Remote event ID is deterministically derived from the
  cross-device stable local plan ID and event kind; ownership metadata names the app, plan ID,
  account-bound source and local revision. A `409` is accepted only after the fetched event proves
  that exact ownership; otherwise it is a conflict, never an adopted foreign event.
- **AC-003 — Account isolation.** Every mapping, sync token, throttle timestamp, pending proposal
  and operation journal is keyed by the explicitly connected Calendar email. A→B or sign-out never
  reads, writes, resumes or accepts A's Calendar state under B. Legacy ownerless records are
  quarantined (no API call) until an explicit owner-safe resolution exists.
- **AC-004 — Offline local source.** Creating/editing/cancelling a local plan succeeds locally
  without Google. Network/auth failure preserves the local plan and its last confirmed remote
  metadata; no failure, cancellation or revoked consent is represented as an empty remote snapshot.
- **AC-005 — Bounded pull.** Auto-open refresh observes the six-hour per-account throttle;
  manual refresh is explicit and bypasses it. A completed sync atomically advances the account's
  sync token only after every response page has been processed. `410 Gone` discards only the
  token/derived remote observation and performs a full re-read; it does not erase local plans,
  mappings or proposals.
- **AC-006 — Foreign-event boundary.** The API read may encounter other Calendar events, but such
  events are neither imported nor written to Room/DataStore. Only an event that independently
  matches app metadata and the local account-bound mapping is considered owned.
- **AC-007 — One-off reverse changes.** An owned remote time change creates a proposal containing
  old/new time, remote ETag and the local plan revision. Confirm applies only if both versions
  still match; otherwise refreshes/re-proposes. An owned remote deletion similarly proposes local
  cancellation. No proposal interrupts an active workout/rest timer.
- **AC-008 — Safe proposal choices.** Proposed default: **Defer** retains a pending proposal and
  makes no write; **Keep local** records rejection for that exact remote ETag/revision pair and
  makes no remote repair; a later local edit or newer remote version needs an explicit new
  reconciliation action. This avoids silently resurrecting a Calendar event or overwriting a
  newer edit. Product owner must confirm this rejection policy.
- **AC-009 — Recurring master and exception identity.** Pull uses `singleEvents=false`. A recurring
  master matches its owned master ID; an exception matches `(recurringEventId, originalStartTime)`.
  A moved instance preserves `originalStartTime` as the local instance key; cancelling one
  instance is an exception and never changes the master/RRULE or other instances.
- **AC-010 — External series edits.** A changed/deleted owned recurring master yields a series-level
  proposal only; it never rewrites a local rule or exceptional instance automatically. The proposal
  must expose that applying it may conflict with retained instance exceptions. The exact allowed
  UI choices remain product input.
- **AC-011 — Optimistic concurrency.** Any app-originated update/delete first uses the last observed
  ETag as `If-Match`; `412` refreshes state and produces/revises a proposal rather than overwriting
  a Calendar edit. A local revision mismatch follows the same no-automatic-apply rule.
- **AC-012 — Crash-safe replay.** An outbound upload/reconciliation journal is persisted before a
  remote call and retains deterministic IDs, owner, target revision and confirmed steps. Replay is
  single-flight and idempotent; an unknown/corrupt journal fails closed and cannot start a new
  Calendar operation.

## Current execution and data flow

| Evidence | Current behavior | CAL-02 implication |
|---|---|---|
| `data/google/CalendarApi.kt:22`, `CalendarEventDto` | Retrofit exposes only insert/delete. DTO already supports caller-provided `id` and RRULE. | Add read/list, get and conditional update/delete response models without changing ad-hoc semantics. |
| `data/google/CalendarRepository.kt:53` | Ad-hoc schedule inserts Google first, then writes `ScheduledWorkoutEntity`; local entry does not exist when offline. | This is pre-CAL-01 behavior, not a valid CAL-02 local-plan writer. Preserve it until CAL-01 replaces/reroutes it. |
| `data/schedule/WeeklyScheduleRepository.kt:55` | Weekly rules are a DataStore aggregate coupled to immediate Google RRULE replacement. It has a singleton `Mutex`, account gate, preassigned 32-hex IDs and durable create/cleanup/delete journal. | Reuse its safety properties, but do not treat it as CAL-01's local recurring-plan model. CAL-02 needs one unified plan ownership/mapping model. |
| `data/schedule/WeeklyScheduleOperation.kt:52` | Journal validates owner, deterministic prepared IDs and pending sets; unreadable data fails closed. | CAL-02 journal must additionally persist plan identity/revision, remote ETag, event kind and exception key; exclude it from backup as this journal already is. |
| `data/google/GoogleAuthManager.kt:73` | `getAccessTokenForAccount(expectedEmail)` binds OAuth request to a Google account; current scope is `calendar.events`. | All pull and recovery calls must use this account-bound seam. Scope must be rechecked at implementation, but `events.list` currently accepts `calendar.events`. |
| `ui/calendar/CalendarViewModel.kt:131` | UI combines Room history, `ScheduledWorkoutDao`, and weekly DataStore; it invokes Google directly for ad-hoc planning. | CAL-01 must make the calendar UI read a local plan resolver only. CAL-02 adds a separate sync state/proposal flow; it must not make Compose perform transport. |
| `worker/WeeklyScheduleRecoveryWorker.kt` and `GymApplication` (per weekly-safe-replacement plan) | Persistent recovery is unique WorkManager work, not direct app-start network. | A Calendar-sync worker must likewise serialize durable replay; opening the app may enqueue/request a refresh, never bypass its repository state machine. |

## Required data/contract shape (bounded recommendation)

1. CAL-01 must expose a local aggregate with a backend/cross-device stable plan UUID, owner,
   monotonically comparable revision, future-effective time/zone, recurrence master and explicit
   instance exception records. Auto-increment Room IDs alone cannot produce cross-device identity.
2. Store a per-Calendar-account owned-event mapping: local plan UUID + kind (`one_off`, `master`,
   `exception`) + optional original-instance key → remote event ID, last ETag, last observed remote
   revision/time and sync status. Remote ID alone is insufficient: it cannot distinguish a master
   from an exception or safely validate a reused ID.
3. Store the sync cursor and auto-refresh timestamp only as derived account-bound state. A token is
   committed only with the finished page sequence; do not replace a good cursor on cancellation,
   token failure or partial paging.
4. Use private extended properties on every app-created event as a second ownership proof. They are
   private to that calendar copy and writable; do not encode private local identity in title.
5. Deterministic Google ID must meet Calendar's accepted ID alphabet/length and include a stable
   namespace/version in its derivation. The same local plan on two devices must calculate the same
   ID; account mismatch must never cause a different account to claim that event after `409`.

## Important API constraints and risks

- **No filtered incremental query.** `syncToken` cannot be combined with `timeMin`,
  `privateExtendedProperty`, `orderBy` or other listed restrictions. Therefore initial and
  incremental reads use the same unfiltered query parameters, proposed fixed set:
  `singleEvents=false`, `showDeleted=true`, fixed `maxResults`; foreign items are discarded in
  memory. “Upload all future local plans” is an outbound eligibility rule, not a remote list
  `timeMin` filter. [Events.list](https://developers.google.com/workspace/calendar/api/v3/reference/events/list)
- **Paging and 410.** `nextSyncToken` exists only on the final page. Incremental responses contain
  deletions, and `410` requires clearing client sync state and a new full sync. Keep the local
  source and ownership mapping; clear only derived cursor/remote-observation cache, then rebuild it
  from verified owned events. [Google sync guide](https://developers.google.com/workspace/calendar/api/guides/sync)
- **Recurrence.** With `singleEvents=false`, list returns single events, recurring masters and
  exceptions but not ordinary generated instances. `originalStartTime` uniquely identifies a
  recurring instance even after it moves; cancelled exceptions must be retained for their master's
  lifetime. Do not edit every generated instance for a series change. [Recurring events](https://developers.google.com/workspace/calendar/api/guides/recurringevents), [Event resource](https://developers.google.com/workspace/calendar/api/v3/reference/events)
- **Concurrent edits.** Calendar `update` replaces the entire resource; retrieve and update with
  ETag/`If-Match`. `412` means refetch/re-propose, never retry by overwriting. [Events.update](https://developers.google.com/workspace/calendar/api/v3/reference/events/update), [conditional modification](https://developers.google.com/calendar/api/guides/version-resources)
- **Cross-device duplicate ownership.** Concurrent first uploads may both insert the same
  deterministic ID. The loser sees `409`; accepting it without a get-and-metadata verification
  could bind an unrelated event. A non-identical event remains a visible conflict and is not
  mutated.
- **Remote deletion ambiguity.** A deleted event response may contain only its ID. For a recurring
  cancelled exception, the API guarantees `id`, `recurringEventId`, `originalStartTime`; retain the
  corresponding local exception metadata before discarding a full-deleted event observation.
- **Account/revocation/cancellation.** Sign-out, token cancellation, 401/403, process death and
  account switching are paused states. They may surface “needs connection” but must not clear
  cursor, map, proposal or local plan and must not enqueue remote writes under a new account.
- **Room/security/backup.** New Room tables require explicit migration plus exported schema and
  migration tests; no destructive fallback. Store no token. Account email, event IDs, plan IDs,
  ETags and journal contents are private app data. Exclude only in-flight journals from backup;
  durable local plans need their existing cross-device policy preserved.
- **Lifecycle/UI/accessibility.** Refresh is repository/worker controlled and represented as
  immutable UI state. Manual refresh and proposal actions need descriptive labels, disabled/busy
  semantics, 48dp targets, `fontScale=2.0`, TalkBack and compact/medium/expanded verification.
  New motion/haptics must use `GymMotion`/`GymHaptics`; colors only use `MaterialTheme` tokens.

## Research questions and root decisions (autonomous authorization)

These questions do not require another user confirmation. The owner delegated routine product decisions.

- AC-008 is accepted: Keep local suppresses this exact mismatch, never repairs Google implicitly.
  A visible disconnected/mismatch status remains; a subsequent explicit local edit queues normal
  conditional reconciliation. Defer leaves the proposal in Calendar, without recurring popups.
- AC-010: a master deletion offers explicit series cancellation with affected-future-instance count;
  a supported weekly time/date/zone edit offers a separate series replacement preview. For existing
  exceptions, map the original weekly occurrence ordinal to the replacement rule: cancellations
  remain cancelled; moved instances retain their explicit absolute instant. Show the affected exception
  count before approval. Unsupported recurrence/all-day shapes remain a visible conflict with no write.
  No single-instance proposal can invoke this series path. History is immutable.
- CAL-01 already freezes canonical UUIDs and original instance keys in `local-calendar-plan.md` and
  the shared fixture. No new wire revision field is needed: confirmation captures a canonical content
  fingerprint of the full local aggregate (rule plus exceptions for series), owner epoch and remote ETag.
  Compare all three again before applying. Local auto-increment IDs never identify a remote object.
- A recurring master is eligible when any future effective occurrence exists, even if DTSTART is past;
  export the actual rule and exceptions, never synthesize past one-offs. One-offs use startsAt >= now.

The following research wording is retained as provenance and superseded by the decisions above:

1. Confirm AC-008: after choosing “Keep local” for an externally moved/deleted event, should the
   app only mark the mismatch (recommended), offer an explicit “restore/update Google” action, or
   automatically repair Google? Automatic repair risks surprising writes and edit ping-pong.
2. Confirm AC-010's series UI: when a user externally edits/deletes an owned recurring master with
   existing local exceptions, may they apply it as a series replacement, or must they first resolve
   exceptions individually? Recommended first release: show a conflict and defer any series-level
   local mutation.
3. CAL-01 prerequisite: define the exact cross-device stable plan UUID and revision authority.
   CAL-02 cannot guarantee deterministic multi-device IDs or local-revision confirmation while
   one-offs are only local auto-increment `ScheduledWorkoutEntity` records and recurring rules are
   only a DataStore blob.
4. Define the future boundary for recurring masters whose DTSTART is in the past but whose series
   has future occurrences. Recommended: upload the master when it has at least one future effective
   occurrence; never manufacture a historical one-off.

## Recommended verification

Handwritten fake `CalendarApi` tests should cover full/incremental multi-page token commits;
`410`; foreign events; account A/B; cancellation/401/403; same-ID 409 with matching and conflicting
metadata; update 412; duplicate races; deletion proposals; reject/defer persistence; master,
moved-instance and cancelled-instance matching; local revision races; journal crash points; and
single-flight Worker/ViewModel interaction. Use Room migration tests for mappings/proposals and
Robolectric only for Worker/auth boundaries. Validate against a dedicated test calendar only during
implementation; this research made no Google calls.

## Sources used

- [Google Calendar incremental synchronization](https://developers.google.com/workspace/calendar/api/guides/sync) — accessed 10 September 2026.
- [Events.list reference](https://developers.google.com/workspace/calendar/api/v3/reference/events/list) — accessed 10 September 2026.
- [Recurring events guide](https://developers.google.com/workspace/calendar/api/guides/recurringevents) — accessed 10 September 2026.
- [Event resource reference](https://developers.google.com/workspace/calendar/api/v3/reference/events) — accessed 10 September 2026.
- [Events.update reference](https://developers.google.com/workspace/calendar/api/v3/reference/events/update) and [conditional modification](https://developers.google.com/calendar/api/guides/version-resources) — accessed 10 September 2026.
