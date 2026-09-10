# PLAN-01 — training proposals tracker

Status values: `pending | in_progress | done | blocked`.

| Task | Status | Owner | Depends | AC | Automated evidence / observable completion |
|---|---|---|---|---|---|
| T-001 | done | Contract owner | AI-01, CAL-01, guest-sync | AC-001–006 | Strict fixture accepted in ca41f1f; pre-implementation correction caps gymIds at 1000 to match routine validation. Both fixture copies SHA-256 `65254ebf9aaa4062ebf8ef71df99c76876685ce10ec0bd2fa8645fced56ea998`. Backend implementation and independent verification remain separate gates. |
| T-002 | pending | Sole backend writer | T-001, accepted next basic-profile backend baseline | AC-001–008 | Proposal/auth/transaction races, migration and focused backend tests pass. |
| T-002V | pending | Independent backend tester/reviewer | stable T-002 | AC-001–008 | Strict backend Gate T/V packet before Android starts. |
| T-002F | pending | Backend writer + root acceptance | T-002V | AC-001–008 | Bounded fix, backend full test/boot artifact and accepted baseline (68 tests reported passing). |
| T-003 | pending | Sole Android writer | T-001,T-002F,CAL-01 | AC-003–008 | Targeted Room/full-authoritative sync/projection tests and `:app:compileDebugKotlin` pass. |
| T-004 | pending | Same Android writer | T-003 | AC-004,008,009 | Focused ViewModel/Compose semantics/adaptive tests and compile pass. |
| T-005 | pending | Same Android writer | T-003,T-004 | AC-001–009 | One version increment, actual N and Gate I commands recorded. |
| T-006 | pending | Independent Android tester + reviewer | T-005 | AC-001–009 | Android-only strict Gate T/V packet; no P0/P1. |
| T-007 | pending | Original writer(s) | confirmed T-006 findings | affected | Smallest invalidated command and rereview evidence. |
| T-008 | pending | Root integration owner | T-006/T-007 | AC-001–009 | Final Android unit suite then debug assembly; accepted backend T-002F evidence carried forward. |

## AC → task → test traceability

| AC | Tasks | Tests / checks |
|---|---|---|
| AC-001 | T-001,T-002,T-002V | version snapshots, typed AI actor, expiry and terminal-state backend tests. |
| AC-002 | T-001,T-002,T-002V | recipient/coach/unrelated/guest matrix; spoof/missing/throw authority and revoked relation deny; stage-23-only authority replacement. |
| AC-003 | T-001–003,T-002V,T-006 | malformed/canonical UUID, equipment, 30×20, zone/start, forbidden payload/stale-context and AI/coach/user weight-source tests. |
| AC-004 | T-001,T-003,T-004,T-006 | owner/proposal/version draft journal, edited-death-before-HTTP, A-B-A exact restore, author-version invalidation, expiry/needs-refresh and no-preapproval-PortableData tests. |
| AC-005 | T-001–003,T-002V,T-006 | immutable canonical bytes before dispatch, server recomputed fingerprint, same-op changed 409, exact retry, explicit terminal and response-loss tests. |
| AC-006 | T-001,T-002,T-002V | all-mutation lock-order deadlock/barrier, session-revalidation, approval/reject/revoke/relation-revoke and DB rollback/restart tests. |
| AC-007 | T-003,T-006,T-008 | full authoritative snapshot application, pre-existing outbox preservation, no second POST/manual insert/dirty outbox, process-death, multi-device/head and active-workout races. |
| AC-008 | T-002–004,T-006 | reject/revoke no-object/history-preservation and visible terminal/error state tests. |
| AC-009 | T-004,T-006 | loading/empty/content/error/stale/applied, TalkBack semantics, 48dp, fontScale 2.0 and compact/expanded checks. |

## Deviations

None. Current Room version observed during planning is 16, but the actual predecessor N belongs to the Android Room writer after CAL-01/guest-sync integration. Backend implementation ownership is maintained in the known backend plan/tracker at `/private/tmp/yarumo-backend-delivery`; no invented manifest path remains.

## Findings

- Current `SaveCompletedWorkoutAsRoutineUseCase` has history-derived semantics and is excluded from projection implementation.
- Current legacy `ScheduledWorkoutDao` is not the target boundary; CAL-01 `CalendarPlanRepository` is required.
- Proposal documents remain server-owned and absent from `PortableData` before approval. Android approval recovery uses normal full `BackendSync` application, never a manual calendar insert or approval-created dirty outbox. CAL-02 alone may enqueue Google transport after a committed calendar plan.

## Command results

Planning only: no Gradle, backend, Git, production-source or schema command ran. Per `AGENTS.md`, documentation-only planning does not run build/tests.

## Residual risks

- Stage 23 relationship persistence is a dependency for coach enablement; PLAN-01 safely denies COACH until then.
- AI-01/21 must supply a validated, frozen context draft; PLAN-01 cannot make a provider response safe by itself.
- A server result can outlive a failed local projection; immutable operation bytes and operation-ID recovery preserve it until normal authoritative sync can reapply.

Root final strict Sol/high Gate P PASS: seven P1 correction groups and the remaining flow-diagram contradiction closed. Backend authoritative full-sync projection is the only approval apply path.
