# CAL-01 local calendar tracker

| Task | Status | Owner | Depends | AC | Evidence / done condition |
|---|---|---|---|---|---|
| T-001 | pending | Root contract owner | — | AC-001–007 | Byte-identical Android/backend `cal01-sync-contract.json`, `cmp` and recorded SHA-256 acknowledge captured metadata, IDs and capability matrix. |
| T-002 | pending | Android Room writer | T-001 | AC-001–006 | Targeted Android calendar/backend tests pass; Room migration and gated local source compile. |
| T-003 | pending | Backend writer | T-001 | AC-004,005,007 | Backend integration tests + bootJar prove additive capability-safe sync. |
| T-004 | pending | Android Room writer | T-002,T-003 fixture | AC-001–007 | Real Room/migration/fault/DST/guest/capability/owner/legacy-bridge regressions pass. |
| T-005 | pending | Android Room writer | T-004 | AC-001–007 | One Android version increment and compile evidence. |
| T-006 | pending | Independent tester + Sol/high reviewer | T-003,T-005 | AC-001–007 | Consolidated Gate T/V verdict; no open P0/P1. |
| T-007 | pending | Original owner(s), Root integration | confirmed T-006 findings | affected | Bounded fix and recheck evidence. |
| T-008 | pending | Root | T-006/T-007 | AC-001–007 | Android full units then debug assembly; independent backend test, bootJar and Docker evidence. |

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

## Residual risks

- Legacy rows lacking a portable Google identity remain preserved/quarantined instead of being guessed across devices.
- CAL-02 must supply deterministic Google external identity and same-owner reconciliation before any external event operation is re-enabled.
- Legacy schedule projection remains synchronized for old clients; bridge behavior must not use UI suppression as a data-loss shortcut.
- A server that ignores the capability header leaves calendar changes locally dirty by design.
