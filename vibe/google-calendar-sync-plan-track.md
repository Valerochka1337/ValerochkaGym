# CAL-02 — трекер Google Calendar sync

Статусы: `pending | in_progress | done | blocked`.

| Task | Status | Owner | Depends | AC | Evidence / done condition |
|---|---|---|---|---|---|
| T-001 | pending | Root contract owner | CAL-01, 08 | AC-001,002,005,006,009–012 | Canonical fixture copies hash identically and reference existing CAL-01 UUID contract. |
| T-002 | pending | Android writer, sole Room owner | T-001 | AC-003,004,012 | Actual `N→N+1` migration/full-path/DAO tests, schema, quarantine proof. |
| T-003 | pending | Same Android writer | T-001,T-002,08 | AC-001–006,011,012 | Calendar transport/repository/journal tests and compile pass. |
| T-004 | pending | Same Android writer | T-002,T-003 | AC-004,005,007–012 | Reconciler/worker tests prove throttle, paging, proposals/remap/suppression. |
| T-005 | pending | Same Android writer | T-004 | AC-007–010 | VM/Compose semantics, active-workout and 2x font tests pass. |
| T-006 | pending | Same Android writer | T-002–005 | AC-001–012 | One version bump, actual N, fixture hash and Gate I evidence. |
| T-007 | pending | Independent tester + Sol/high reviewer | stable T-006 | AC-001–012 | Consolidated strict T/V verdict; no P0/P1. |
| T-008 | pending | Original writer | confirmed T-007 findings | affected | Smallest invalidated tests and scoped rereview pass. |
| T-009 | pending | Root | T-007/T-008 | AC-001–012 | Final unit suite then debug assembly pass once. |

## AC → task → verification

| AC | Tasks | Primary evidence |
|---|---|---|
| AC-001 | T-001,T-003,T-004,T-009 | deterministic initial upload, timeout/replay/two-device 409 verification tests |
| AC-002 | T-001,T-003,T-007 | fixture and 409 matching/mismatching ownership tests |
| AC-003 | T-002–004,T-007 | A/B, sign-out/revoke, explicit reconnect and legacy quarantine tests |
| AC-004 | T-002–004,T-009 | offline/cancel/auth-failure preservation tests |
| AC-005 | T-001,T-003,T-004 | 6h/manual, multipage token, 410 full reread tests |
| AC-006 | T-001,T-003,T-007 | foreign stream discard and minimal-cancelled verified-link tests |
| AC-007 | T-004,T-005 | one-off move/delete proposal + fingerprint/epoch/ETag/revision race tests |
| AC-008 | T-004,T-005 | Defer card/no popup and exact suppression/no-repair tests |
| AC-009 | T-001,T-004 | master/exception/moved/cancelled key identity tests |
| AC-010 | T-001,T-004,T-005 | weekly ordinal remap, retained cancellation/absolute move, unsupported conflict tests |
| AC-011 | T-003,T-004 | If-Match 412 and all-four-values confirmation tests |
| AC-012 | T-002,T-003,T-004 | pre-call journal, corrupt/dead-letter, ambiguous replay and single-flight worker tests |

## Deviations, findings, command results

Pending. T-001 records fixture SHA-256. T-002 records actual Room predecessor N rather than assuming a planning version. No Google API call, credentials or Gradle command is run during planning.

## Residual risks

Calendar cannot atomically commit with Room/journal; safe pause/manual retry is preferred to duplicate remote writes. A large unfiltered Google stream is bounded by page streaming, not imported storage. Unsupported recurrence remains a visible user conflict by design.

## Root strict-P correction batch

Five P1 contracts corrected: stable exported ownership versus local epoch; natural instance IDs
and paged instances API; newly cancelled instance verified through master; backend capability/ACK
readiness and exact-state retention; transactional active-workout guard including in-flight HTTP
ambiguity. Required regression matrix assigned T-001/T-003/T-004 and independent T-007.
Narrow Sol/high recheck pending.

Root final strict Sol/high Gate P PASS: all five original P1 and two residual wording ambiguities closed; kind-specific verifier and account-wide readiness gates have direct test obligations.
