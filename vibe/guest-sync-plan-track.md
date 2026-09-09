# Guest sync plan tracker

Status values: `pending | in_progress | done | blocked`.

| Task | Status | Owner | Depends | AC | Automated evidence / observable completion |
|---|---|---|---|---|---|
| T-001 | pending | Writer | — | AC-001–007 | Contract checklist: legacy state, exact retry/409, server-only baseline, final ACK and explicit-conflict no-mutation are frozen before source edits. |
| T-002 | pending | Writer (sole Room owner) | T-001 | AC-002,003,007 | Targeted incremental/full migration; both legacy owner branches preserve data, baseline/outbox bytes and state/mapping. |
| T-003 | pending | Writer | T-002 | AC-001–006 | `:app:compileDebugKotlin`; claim/recovery, operation handling, every-run policy and UI compile. |
| T-004 | pending | Writer | T-003 | AC-001–007 | Targeted `BackendSyncTest`, `AccountViewModelTest`, `AccountFormComposeTest`; union, ACK/retry/409/conflicts/active race regressions. |
| T-005 | pending | Writer | T-004 | AC-001–007 | `:app:compileDebugKotlin`; one version increment and tracker evidence recorded. |
| T-006 | pending | Independent tester + Sol/high reviewer | T-005 | AC-001–007 | Tester targeted-gap report and read-only strict review verdict. |
| T-007 | pending | Writer | confirmed T-006 findings | affected | Smallest invalidated test and bounded rereview. |
| T-008 | pending | Root | T-006/T-007 | AC-001–007 | `:app:testDebugUnitTest` then `:app:assembleDebug` pass once on stable diff. |

## AC → task → test traceability

| AC | Tasks | Tests / checks |
|---|---|---|
| AC-001 | T-001–004,T-008 | `BackendSyncTest`: non-empty guest/server union, final ACK only after last batch/no outbox/fresh snapshot equality, copy ACK, lost response exact retry; final unit suite. |
| AC-002 | T-002–004,T-008 | migration and `BackendSyncTest`: legacy A is OWNED, A→B purge keeps standard catalog and cannot upload A payload as B. |
| AC-003 | T-001–004,T-008 | `BackendSyncTest` real Room recreation: legacy GUEST/OWNED, CLAIMED/OWNED, B-only resume, token-loss/lost response and C rejection; Account recovery tests. |
| AC-004 | T-003,T-004,T-008 | `BackendSyncTest`: every-run workout conflict applies one server snapshot and contains no clone. |
| AC-005 | T-002–004,T-008 | `BackendSyncTest`: routine server baseline only, one mapped local complete dirty copy gets baseline after its ACK, stable replay exactly two. |
| AC-006 | T-003,T-004,T-006,T-008 | `BackendSyncTest`: active preflight/transaction race and stale owner; definite 409 versus ambiguous operation; explicit conflicts retain aggregate/baseline/outbox before manual choice; reviewer checks cancellation/WorkManager. |
| AC-007 | T-001–005,T-008 | `Migration<N>To<N+1>Test`, `Migration1To<N+1>Test`, schema JSON, both owner migrations, exact lost-response retry and definite-409 replacement, compile and final gates. |

## Deviations

- None. `<N>` must be replaced by the current Room predecessor immediately before implementation; current planning evidence is version 16.
- Calendar plan aggregate tracking is intentionally deferred to CAL-01. Existing `scheduled_workouts` remains in the current portable union only.

## Findings

- 2026-09-10 planning: current `BackendSync.claim` clears data when `previous != user`, including first `null→user`; it is incompatible with AC-001.
- 2026-09-10 planning: `BackendTokenStore` uses Keystore + `AtomicFile`, so a cross-store transaction is impossible. The durable claim-before-token B-only recovery contract is required.
- 2026-09-10 planning: generic `CloudMerge` only exposes conflicts to UI; it cannot implement automatic workout/routine policy without a persistent mapping. A local routine copy cannot enter baseline until its separate push ACK.
- 2026-09-10 Gate P repair: existing outbox-before-GET ordering requires two distinct paths: exact retry after ambiguous dispatch, and retained-rejected-batch → GET → policy → new batch after definite HTTP 409.

## Command results

- Planning-only: no Gradle commands run, per `AGENTS.md` documentation-only rule.
- Repository observed dirty from unrelated workout-program work; this plan does not touch it.

## Residual risks

- Backend idempotence/record-kind support is an external release blocker.
- No automatic resolution is defined for routine deletion conflicts, exercise/gym/measurement/schedule conflicts, or future plan-to-routine reassignment; each must be mutation-free until the existing manual choice.
- The AtomicFile/Room failure window is recoverable but cannot be atomic; `CLAIMED(B)` must never be silently reassigned.
