# Базовый профиль — трекер

Статусы: `pending | in_progress | done | blocked`.

| Task | Status | Owner | Depends | AC | Evidence / observable done condition |
|---|---|---|---|---|---|
| T-001 | pending | Root contract owner | #51, CAL-01, AI-01 | AC-002–006 | Three canonical fixture copies have identical SHA-256 and owner-7 UUID/capability/null/tombstone/downgrade matrix. |
| T-002 | pending | Backend writer | T-001 | AC-003,005–007 | Backend integration matrix proves tombstone reject before ledger/revision, capability preservation and provider redaction; `test`, `bootJar`. |
| T-003 | pending | Android writer, sole Room owner | T-001,T-002, #08/#12/CAL-01/#9 | AC-001–007,010 | Actual `N→N+1` migration/schema and Profile/BackendSync tests prove incoming tombstone and accepted→missing→accepted safety. |
| T-004 | pending | Same Android writer | T-003, AI-01 Android boundary | AC-001–004,008–010 | Profile/DataStore/AI-gate/ViewModel/Compose tests cover reservation/shown ack and three process-death windows; compile passes. |
| T-005 | pending | Same Android writer | T-003,T-004 | AC-001–010 | One version increment, actual N and Gate I evidence recorded. |
| T-006 | pending | Independent testers + Sol/high reviewer | stable T-002,T-005 | AC-001–010 | Consolidated strict T/V report, no P0/P1. |
| T-007 | pending | Original writer | confirmed T-006 findings | affected | Smallest invalidated test and scoped rereview pass. |
| T-008 | pending | Root | T-006/T-007 | AC-001–010 | Android final unit/debug and backend test/bootJar pass on stable diffs. |

## AC → task → test traceability

| AC | Tasks | Tests/checks |
|---|---|---|
| AC-001 | T-003,T-004,T-008 | empty profile repository/UI and final Android unit suite |
| AC-002 | T-001,T-003,T-004,T-008 | canonical enum/null fixture; editor validation and clear-field VM/Compose tests |
| AC-003 | T-001,T-002,T-004,T-008 | UTC clock boundary/date validation; backend provider-redaction fake |
| AC-004 | T-001,T-002,T-003,T-004 | client/server bounds, code points, catalog IDs, sorted equipment tests |
| AC-005 | T-001,T-002,T-003,T-006 | deterministic UUID fixture; guest→B rekey, B collision/server-wins, no duplicate singleton tests |
| AC-006 | T-001,T-002,T-003,T-006 | profile tombstone pre-ledger reject/client preserve; capability absent/accepted/downgrade, ACKed-clean preservation/full refresh, pre-cursor/pre-ledger and old-client preservation tests |
| AC-007 | T-002,T-003,T-006 | owner switch, cancellation/stale response, full child replace/outbox/baseline tests |
| AC-008 | T-004,T-006 | missing-minimum, 71:59:59/exact 72h, reservation versus shown acknowledgment, Fill/Continue/Disable and no-active-workout tests |
| AC-009 | T-004,T-006 | configuration/process recreation, owner scope, stale/duplicate token and death before dialog/during dialog/after consume-before-effect prove at-most-once manual-retry behavior |
| AC-010 | T-003,T-004,T-008 | incremental/full migration/schema, accessibility/font/adaptive tests, final Android gates |

## Deviations

None. `N` is intentionally not set to the research baseline v16: the Android writer records the integrated predecessor after #08/#12/CAL-01/#9.

## Findings and command results

Pending implementation. T-001 records fixture hash; writers append only final targeted commands and results; root records final commands once.

## Residual risks

DataStore cannot atomically commit with Room; the reservation/shown-ack/token protocol prevents double submit but a process death after consumption requires explicit re-entry. Backend publication/live provider verification remains separate from local contract validation.

Root: strict Sol/high narrow Gate P recheck PASS; all three P1 findings closed.

Root canonical fixture prepared at vibe/basic-profile-sync-contract.json, SHA256 1bec288ad8d841efaf645af13ac5ea1cbe2b53c841846589c8101cfe3f524ed6. Per-checkout copies wait their feature branches; no app changes mixed into preparation.
