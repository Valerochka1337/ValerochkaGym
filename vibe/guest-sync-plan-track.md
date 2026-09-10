# Guest sync plan tracker

Status values: `pending | in_progress | done | blocked`.

| Task | Status | Owner | Depends | AC | Automated evidence / observable completion |
|---|---|---|---|---|---|
| T-001 | done | Writer | — | AC-001–007 | Read-only preflight: actual predecessor17, next17→18; accepted contract checked against BackendSync/PortableData/SyncSchema/CatalogSync/BackendTokenStore/AccountVM; implementation waits for #43 acceptance and new branch. |
| T-002 | done | Writer (sole Room owner) | T-001 | AC-002,003,007 | Room 17→18 state/map migration and historical fixture repair passed targeted verification. |
| T-003 | done | Writer | T-002 | AC-001–006 | Claim/recovery, operation handling, automatic conflict policy and Account recovery compile. |
| T-004 | done | Writer | T-003 | AC-001–007 | Real-Room targeted regression set passes: durable definite-409 versus ambiguous retry after recreation, automatic workout/routine policy, final ACK/batching, owner/active/cancellation guards, preservation journals, and claimed-transfer recovery semantics. |
| T-005 | done | Writer | T-004 | AC-001–007 | Version remains the single reserved increment `37 / 1.3.29`; targeted tests, JDK 21 compile, and Spotless pass. |
| T-006 | done | Independent tester + Sol/high reviewer | T-005 | AC-001–007 | Tester targeted-gap report and read-only strict review verdict. |
| T-007 | done | Writer | confirmed T-006 findings | AC-001–007 | Consolidated P1/P2 ownership, recovery, catalog, deletion, 409, migration, footprint and six-kind regression batch passes targeted Gate I. |
| T-008 | done | Root | T-006/T-007 | AC-001–007 | `:app:testDebugUnitTest` then `:app:assembleDebug` pass once on stable diff. |

## AC → task → test traceability

| AC | Tasks | Tests / checks |
|---|---|---|
| AC-001 | T-001–004,T-008 | `BackendSyncTest`: final ACK only after all 1,001-record batches/no outbox/fresh snapshot equality, concurrent edit follow-up operation, copy ACK, exact ambiguous lost-response retry. |
| AC-002 | T-002–004,T-008 | migration and `BackendSyncTest`: legacy A is OWNED, A→B dirty/outbox/catalog-journal/tombstone preflight blocks without data/token changes; clean replacement keeps standard catalog and cannot upload A payload as B. |
| AC-003 | T-001–004,T-008 | `BackendSyncTest` real Room recreation: legacy GUEST/OWNED, CLAIMED/OWNED, B-only resume, durable definite-409 marker and C rejection; Account recovery semantics at `fontScale=2.0`. |
| AC-004 | T-003,T-004,T-008 | `BackendSyncTest`: every-run workout conflict applies one server history snapshot and contains no clone. |
| AC-005 | T-002–004,T-008 | `BackendSyncTest`: routine server baseline only, one mapped full local dirty copy gets baseline after its own ACK, replay remains exactly two. |
| AC-006 | T-003,T-004,T-006,T-008 | `BackendSyncTest`: active apply transaction race, stale owner, cancellation rethrow, definite 409 retained bytes/GET/manual choice/new operation, and ambiguous retry are distinct. |
| AC-007 | T-001–005,T-008 | `Migration<N>To<N+1>Test`, `Migration1To<N+1>Test`, schema JSON, both owner migrations, exact lost-response retry and definite-409 replacement, compile and final gates. |

## Deviations

- Root preservation amendment: owner-switch preflight must include portable/baseline differences, both exact-request journals, catalogue transition and configuration tombstones before any A→B cleanup/token replacement. Independent Sol/high strict re-review PASS (no P0/P1); no destructive bypass added. `<N>` must be replaced by the current Room predecessor immediately before implementation; current planning evidence is version 16.
- Calendar plan aggregate tracking is intentionally deferred to CAL-01. Existing `scheduled_workouts` remains in the current portable union only.

## Findings

- Takeover read-only preflight: `BackendSync.run` refreshes catalog before ownership checks; guard phase/target/token before that and inside every apply/ACK transaction. Definite409 must not keep reattempting the rejected outbox as ambiguous, and manual resolve must not delete it before a safe choice. `CatalogSync.personalCopy` alone lacks durable conflict mapping/canonical fingerprint. Final no-batch return is not initial-merge ACK proof. Actual Room predecessor17 confirmed against exported schema; migration17→18 must preserve exact baseline/outbox bytes and update historical recovery fixtures for any new objects.

- 2026-09-10 planning: current `BackendSync.claim` clears data when `previous != user`, including first `null→user`; it is incompatible with AC-001.
- 2026-09-10 planning: `BackendTokenStore` uses Keystore + `AtomicFile`, so a cross-store transaction is impossible. The durable claim-before-token B-only recovery contract is required.
- 2026-09-10 planning: generic `CloudMerge` only exposes conflicts to UI; it cannot implement automatic workout/routine policy without a persistent mapping. A local routine copy cannot enter baseline until its separate push ACK.
- 2026-09-10 Gate P repair: existing outbox-before-GET ordering requires two distinct paths: exact retry after ambiguous dispatch, and retained-rejected-batch → GET → policy → new batch after definite HTTP 409.

## Command results

- JDK 21 targeted migration gate passed: `Migration17To18Test`, `Migration1To18Test`,
  `Migration9To12Test`, `Migration10To12Test`, and `Migration11To12Test`.
- JDK 21 targeted `BackendSyncTest`, `AccountViewModelTest`, and `AccountFormComposeTest` passed.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew --no-daemon
  :app:compileDebugKotlin spotlessCheck`: passed.

- Planning-only: no Gradle commands run, per `AGENTS.md` documentation-only rule.
- Repository observed dirty from unrelated workout-program work; this plan does not touch it.

## Residual risks

- Backend idempotence/record-kind support is an external release blocker.
- No automatic resolution is defined for routine deletion conflicts, exercise/gym/measurement/schedule conflicts, or future plan-to-routine reassignment; each must be mutation-free until the existing manual choice.
- The AtomicFile/Room failure window is recoverable but cannot be atomic; `CLAIMED(B)` must never be silently reassigned.

## T-004/T-005 command results

- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew --no-daemon :app:testDebugUnitTest --tests "*BackendSyncTest" --tests "*Migration17To18Test" --tests "*Migration1To18Test" --tests "*AccountViewModelTest" --tests "*AccountFormComposeTest"`: passed. 41 tests total, 0 failures/errors.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew --no-daemon :app:compileDebugKotlin spotlessCheck`: passed.

## T-004 deviation

- A definite revision conflict now writes a separate `backend_rejected_operations` marker in the same 17→18 migration. The original `backend_outbox.requestJson` remains byte-identical and inspectable; recreation skips that known-rejected operation, then removes the marker only after a safe automatic/manual apply has discarded it and created any required fresh operation.

## T-007 command results

- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew --no-daemon :app:testDebugUnitTest --tests "*BackendSyncTest" --tests "*Migration17To18Test" --tests "*Migration1To18Test" --tests "*AccountViewModelTest" --tests "*AccountFormComposeTest"`: passed. 49 tests total, 0 failures/errors.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew --no-daemon :app:compileDebugKotlin spotlessCheck`: passed.

## T-007 resolutions

- Routine mapping has a stable `owned:<owner>` identity for upgraded `OWNED` rows without a `mergeId`; stale or failed B-token installation cannot expose or use retained A credentials against B's Room claim.
- Every private catalog apply and personal API result revalidates owner/phase/token; guest public catalog refresh remains allowed.
- Confirmed deletion resets ownership to `GUEST` and clears rejected-operation recovery state atomically. Definite 409 from both retained and freshly captured batches persists its marker before process recreation.
- The preservation fingerprint now includes generation and exact outbox/catalog/tombstone content. Recovery copy avoids showing technical owner identifiers.

## T-007 bounded follow-up

- Account session listing, revocation and deletion-code requests now use the owner-checked `BackendSync.accountRequest` facade. A dispatched A request may reach the server, but an ownership change before its result returns fails closed and cannot update account UI. Recovery text now instructs resuming the same account that started transfer.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew --no-daemon :app:testDebugUnitTest --tests "*BackendSyncTest" --tests "*AccountViewModelTest" --tests "*AccountFormComposeTest"`: passed.
- `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew --no-daemon :app:compileDebugKotlin spotlessCheck`: passed.

## Independent T-006 audit (10.09.2026)

Gate T пока FAIL: формулировки AC traceability выше — целевые требования и частичное
покрытие, не итоговая приёмка. Независимый tester подтвердил отсутствие тестов:
17→18 с NULL owner, recreation между durable CLAIMED и записью token, отдельных
baseline/originalOutbox/applying footprint cases, полного initial six-kind union и
смены owner на POST/ACK/catalog boundary. Отмечен P1: CatalogSync.apply transaction
не получает owner guard; внешний check после refresh слишком поздний. P2:
preservation fingerprint не включает generation. Gate V выполняет независимый
`guest_review_final`; runtime сначала отвергал запуск из-за занятого agent slot,
после освобождения reviewer успешно запущен. Full/debug ещё не выполнялись.

Последняя двухфайловая правка deletion/manual-conflicts отформатирована root и
проверена: BackendSync28tests PASS, compile/spotless PASS,
`/private/tmp/yarumo-guest-final-targeted.log`. Этот PASS не закрывает пробелы T-006.

Strict reviewer подтвердил ещё P1 для T-007: upgraded OWNED с mergeId=NULL падает
на routine conflict; crash/token-write-failure после Room CLAIMED(B) оставляет
A-token и AccountGate допускает несогласованную сессию; confirmed delete из CLAIMED
не сбрасывает backend_state/rejected markers и блокирует новый B навсегда. Эти
сценарии обязательны в consolidated fix + regression batch, приёмки пока нет.

## Narrow T/V recheck after first fix batch

Gate I49tests/compile/spotless PASS подтверждён writer. Independent Sol/high V
закрыл6из7production findings; остаётся исходный P1: AccountViewModel session/revoke/
delete-code обращаются прямо к API, минуя owner façade. P2 wording «прежний аккаунт»
неверно указывает A вместо целевого B. Второй bounded pass назначен.

Повторный named tester runtime не запустил (`agent thread limit reached`), поэтому
root независимо от writer проверил actual tests. Gate T пока не закрыт: six-kind
тест проверяет snapshot без merge; token failure не проверяет B resume/directAPI;
нет independent baseline-only/applying-only и stale POST ACK. Эти точные пробелы
включены во второй pass; прежние формулировки allAC не являются приёмкой.

## Root bounded intervention after surviving P1

Второй writer pass не закрыл атомарность полной account action: revoke мог
освободить mutex между DELETE и GET, UI commit выполнялся позже. Root сообщил
конкретный blocker и заменил дальнейший общий fix loop точечным исправлением:
expectedOwner захватывается до запуска VM task, один accountRequests mutex охватывает
все запросы, повторную проверку и UI commit. Список сессий хранится с owner и
фильтруется текущей session. Regression VM реально ставит B signIn в очередь во время
DELETE и проверяет DELETE+GET для A и отсутствие A sessions под B; queued A action
после смены владельца не выполняет ни одного запроса.

Root также завершил недостающие Gate T тесты: actual six-kind initial union с непустым
server и finalACK; failing-once token store + C denial/B resume с тем же mergeId;
отдельные baseline divergence/deletion и applying-only при неизменной generation;
POST commit→late ACK не меняет baseline и сохраняет exact outbox под B.
Targeted53tests PASS (BackendSync39/AccountVM9/Compose5), compile/spotless PASS,
log `/private/tmp/yarumo-guest-root-boundary.log`. Room migration targeted evidence
не инвалидировано (production migration не менялась). Narrow V этого вмешательства
ещё pending; dispatch временно недоступен из-за runtime agent thread limit.

## Final local acceptance

Guest sync AC-001–007 accepted locally. Independent strict review closed the original
five P1 and two P2; a separate Sol/high reviewer accepted the root composite-account
boundary. Root completed missing Gate T regressions after the runtime could not
restore the prior tester. Full run initially found one historical migration defect:
SyncSchema.create accidentally introduced Room18 tables in14→15. Restored exactly
historical DDL; independent narrow migration check and targeted4tests PASS.

Final full rerun:1110tests,0failures/errors,1skip PASS (6m14s), debug PASS (12s).
Logs: `/private/tmp/yarumo-guest-final-unit-rerun.log`,
`/private/tmp/yarumo-guest-final-debug.log`. Version37/1.3.29, Room18, one increment
above integration36/1.3.28. No UI screenshots inspected and no publication performed.
