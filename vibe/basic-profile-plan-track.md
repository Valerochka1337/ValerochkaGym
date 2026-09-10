# Базовый профиль — трекер

Статусы: `pending | in_progress | done | blocked`.

| Task | Status | Owner | Depends | AC | Evidence / observable done condition |
|---|---|---|---|---|---|
| T-001 | done | Root contract owner | #51, CAL-01, AI-01 | AC-002–006 | All three fixture copies SHA256 1bec288ad8d841efaf645af13ac5ea1cbe2b53c841846589c8101cfe3f524ed6; accepted Notes Room22 predecessor. |
| T-002 | done | Backend writer | T-001 | AC-003,005–007 | 03a0c1d; strict T/V PASS; full 72 tests, 0 failures/errors/skips; check/bootJar PASS. |
| T-003 | done_local | shared_owner, sole Room/sync owner | T-001,T-002, #08/#12/CAL-01/#9 | AC-001–007,010 | Actual22→23 schema/migration/repository/wire/capability targeted tests pass. |
| T-004 | done_local | ai_fix + root final regressions | frozen T-003 API, AI-01 Android boundary | AC-001–004,008–010 | Profile/DataStore/AI-gate/ViewModel/Compose targeted tests pass. |
| T-005 | done | shared_owner | T-003,T-004 | AC-001–010 | One increment41/1.3.33; Room23; compile and spotless pass. |
| T-006 | done | Independent tester + reviewer | stable T-002,T-005 | AC-001–010 | Final narrow T/V PASS; no P0/P1. |
| T-007 | done | Original writers + root tests | confirmed T-006 findings | affected | Exact wire/ISO/UTC/active-race fixes and required regressions pass. |
| T-008 | done_local | Root | T-006/T-007 | AC-001–010 | Full1160/0fail/0errors/1skip (6m53s), assembleDebug PASS. Backend acceptance carried forward. |

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

T-003 source freeze: Room22→23, deterministic guest claim rekey/server-wins collision,
strict profile wire and atomic equipment replacement, capability downgrade/tombstone/exact-outbox
coverage are in the shared workspace. `git diff --check` PASS; no Gradle command was run because
the parent serializes the combined writer gate.

## Residual risks

DataStore cannot atomically commit with Room; the reservation/shown-ack/token protocol prevents double submit but a process death after consumption requires explicit re-entry. Backend publication/live provider verification remains separate from local contract validation.

Root: strict Sol/high narrow Gate P recheck PASS; all three P1 findings closed.

Root canonical fixture prepared at vibe/basic-profile-sync-contract.json, SHA256 1bec288ad8d841efaf645af13ac5ea1cbe2b53c841846589c8101cfe3f524ed6. Per-checkout copies wait their feature branches; no app changes mixed into preparation.

Backend copy matches the canonical SHA. Android copy remains T-001 outstanding work on its feature branch. Backend final log: `/private/tmp/yarumo-profile-backend-final-fixed.log`; migration 009, unique owner profile and tombstone rejection, UTC age-only AI projection verified. Existing admin-session expiry test uses a fixed expired timestamp to remove database/JVM clock skew; production expiry behavior is unchanged.

## Prepared prompt integration seam — 2026-09-10 (read-only)

Independent prompt preflight reuses the accepted plan: an application-scoped
AiProfilePromptGate owns DataStore reserve → actual-visible ACK → consume/cancel.
Only token/kind/config/process marker is durable; no URI, request text/body or continuation.
A per-process marker distinguishes recreation from cold process restart; orphan cleanup
preserves disabled/lastShown and never emits an AI effect. Exercise entry gates before
generation; InBody entry gates before opening its source picker. Fill consumes then opens
profile, Continue/Disable consumes before one live continuation. Tests must cover 72-hour
boundary, OR condition for missing goal/experience, owner switch, stale callbacks, and
all three process-death windows. Implementation waits for Notes/current Room predecessor.

## Frozen parallel integration boundary — 2026-09-10

Under the user's explicit parallel-work instruction, `shared_owner` owns all profile
entities/DAO/Room/schema/migration/PortableData/BackendSync/domain repository/DI/navigation
and the single build version increment. `ai_fix` owns profile UI/ViewModel, the shared
prompt gate and SettingsRepository prompt persistence, Settings entry, Exercise/InBody
entry integration and their tests. No other writer touches shared data files. Root alone
runs Gradle, after both writers freeze. Independent test/review agents remain read-only.

The profile boundary uses BasicProfile with nullable frozen fields, ProfileEditTarget
(scope, ownerId, sessionEpoch), and ProfileEditorSnapshot(target, profile). Repository
openEditor/observeCurrent/observe(target)/save(target, profile) must never substitute a
new owner's profile into a stale editor. Save returns Saved/Invalid/StaleTarget. UI does
not own syncId or updatedAt. ProfileScreen accepts onBack; Settings and AI entry screens
expose onOpenProfile for the shared navigation owner. Planned predecessor is the actual
Notes Room22; migration22→23 and version41/1.3.33 begin only after Notes final gates.

Profile implementation checkpoint: data/API/UI sources are present, including Room22→23,
session-aware profile editor and owner-scoped prompt reserve/visible-ACK/consume policy.
Gradle has not run yet. Independent data coverage audit requires child-bearing tests for
guest rekey/B collision, server replacement, tombstone and capability-loss preservation,
plus remaining validation boundaries. The original shared owner is adding that packet;
the UI writer is completing direct InBody and profile Compose/lifecycle coverage. Source
data review proceeds independently. No feature acceptance is claimed yet.

Superseding verification checkpoint: independent T/V PASS. The final test packet covers
child-bearing owner/capability transitions, strict wire/UTC dates, deferred reserve with
inactive→active transition, all process-death windows, stale owner/token callbacks,
Disable once-only continuation and Fill once-only navigation for both AI entries.
First combined168-case run compiled successfully and exported Room23; its three failures
were first-export asset ordering and a missing owner fixture row. Narrow rechecks fixed
those and enabled foreign keys explicitly on MigrationTestHelper's raw connection while
retaining the cascade assertion. No production cascade guard was weakened.

Logs: `/private/tmp/yarumo-partial-profile-targeted.log`,
`/private/tmp/yarumo-partial-profile-recheck.log`,
`/private/tmp/yarumo-partial-profile-migration-recheck.log`.
Root full unit gate now runs with the existing isolation init script and no exclusions:
`/private/tmp/yarumo-partial-profile-full.log`. All source writers are frozen.

Final Android acceptance: full1160/0fail/0errors/1skip,6m53s; assembleDebug PASS12s.
Room23 and version41/1.3.33 retained once. Debug log
`/private/tmp/yarumo-partial-profile-debug.log`; control APK
`/private/tmp/yarumo-profile-v41-debug.apk`. No new release-sensitive configuration was
introduced, so no repeat release gate applies to Profile. All WIP remains local and
uncommitted. Health begins from this accepted Room23 predecessor.
