# AI-01 — серверный путь существующих AI exercise/InBody

## Goal, scope, assumptions

Перенести **только** существующие генерацию черновика упражнения и распознавание выбранного фото
InBody с Android BYOK на авторизованный backend. Backend собирает owner-scoped context, вызывает
операторски настроенный provider и возвращает проверенный редактируемый draft. Android сохраняет
только после уже существующего явного подтверждения.

Scope excludes planner/proposals, PDF/medical summary, provider quota policy, retries/idempotency,
new Room data, raw prompt/photo/result persistence and deployment claim. Stage 12 supplies the underlying safe sync implementation; AI T-004 owns the new typed readiness handshake after Stage 12 is integrated. Backend is additive
after this Gate P and may start independently. Current upstream/deploy credential access is 403; this
is recorded as a deployment blocker, never evidence that production AI works.

Frozen provider choice: an OpenAI Chat Completions protocol adapter at operator-fixed HTTPS
`AI_BASE_URL`; `AI_API_KEY`, `AI_TEXT_MODEL`, and `AI_VISION_MODEL` are required environment values,
with no guessed defaults. The API supports `POST /v1/chat/completions` and image `image_url` content
parts; implementation uses its documented request/response shape, but no real key, `.env`, URL, or
model ID is read or committed. [Official Create Chat Completion reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create).

## Acceptance criteria

| ID | Acceptance criterion |
|---|---|
| AC-001 | Authenticated status and two action endpoints expose only the frozen v1 DTOs; Android sends no provider URL/key/model, prompt, catalog, owner or local snapshot. |
| AC-002 | AI starts only after `syncReady(owner, revision, catalogRevision)` following sync ACK; backend verifies owner plus both revisions before context and after provider validation, so no foreign/stale result is shown. |
| AC-003 | Exercise response is Existing UUID from owner/public live catalog or validated New; InBody response is the exact nullable current `InBodyReportDraft` including all five nullable segment objects. Neither action writes records/head/operations before user confirmation. |
| AC-004 | Guest/unauthorized, missing consent, unconfigured provider, offline/timeout/busy/stale context, cancellation, owner/editor change and late response leave manual exercise/InBody flows usable and reveal no secret, prompt, health data or upstream body. |
| AC-005 | Provider is env-only, bounded and discardable: HTTPS fixed endpoint, no redirects, 5s connect/45s end-to-end/256KiB response, at most two in-flight calls, no raw request/result/photo storage; provider failure is a safe action error. |
| AC-006 | Android has no direct provider transport, models catalog, BYOK key/base/model UI/DI/storage or debug AI response logger; a local BYOK key is never transported to backend. |
| AC-007 | Secure environment delivery is default-off and secret-safe; existing sync protocol/capabilities remain compatible. Android applies one #AI-01 version code and patch bump. |

## Data and execution flow

`UI event → consent/auth gate → AI T-004 syncReady after Stage-12 sync → BackendApi.authorized DTO → identity →
catalog lock then owner-head lock → revision comparison → immutable allowed context → provider →
validator → repeat freshness/auth check → response → ViewModel owner/requestId/editor-generation
check → editable draft → explicit existing save`.

`syncReady` is a typed Android adapter result only: `Ready(owner, revision, catalogRevision)`, `Blocked`, or
`Failure`, produced by T-004 after Stage-12 sync ACK confirms the personal revision, catalog state is separately refreshed/verified, and owner/outbox/conflict invariants are checked again.
It performs no claim; Stage 12 retains claim ownership. `BackendSync.run(): Unit`, guest, unowned cache,
active workout deferred apply, pending claim/outbox,
unresolved conflict or owner change cannot be treated as Ready. No client snapshot substitutes for
server context. Android cancellation stops awaiting/application; server attempts cancellation but
does not promise provider/cost cancellation.

Shared fixture is frozen before either writer changes code: v1 request/response JSON fixtures cover
canonical lowercase UUID, `requestId`, nonnegative revisions, exercise Existing/New and the complete
nullable InBody draft/segments. Unknown fields reject. Bounds: description 1..2000; New name 1..200,
type `STRENGTH|TIMED|CARDIO`, unique known muscles and contribution `0|50|100` with one nonzero;
JPEG base64 ≤8MiB chars/decoded ≤6MiB, dimensions 1..3072; context ≤1MiB; provider response ≤256KiB.

## Frozen architecture and safety contracts

- `AiActionService` owns prompt construction, context and validators; `AiProvider` receives only
  typed server-built action/context. Context is exercise catalog (owner live + shared nonarchived)
  for exercise, and selected image plus extraction schema for InBody—never history/notes/foreign
  owner rows. Lock order is catalog then owner head; no lock or DB connection crosses provider HTTP.
- Controller derives owner from authenticated identity; no request ownerId. Revision mismatch before
  provider is 409 `ai_context_stale`; context >1MiB is `ai_context_too_large`; post-response owner,
  revision or session change discards with 401/409. No automatic POST retry or `sync_operations` use.
- Provider configuration validates `AI_ENABLED`, provider name, fixed HTTPS base URL without
  userinfo/query/fragment, key and both model IDs. `UNCONFIGURED` status is 200/actions empty;
  action is 503 and must make zero provider calls. Configuration, URL, key, model, prompt and
  upstream response never serialize, log, or reach Android. Env delivery follows the existing
  stdin/temporary-file cleanup mechanism, with no echo and no checked-in `.env`.
- Android uses immutable UI state and one-shot events. Busy/progress is bounded to the request;
  manual creation/editing remains enabled after a terminal error. Consent is explicit before image
  upload. Owner/requestId/editor generation invalidate a late draft; selected photo remains local
  until consent and no result is persisted automatically. Semantics describe availability/error,
  48dp actions, `GymHaptics`, Material tokens and adaptive/font-scale behavior.

### Strict provider wire fixture

The backend fixture asserts one non-streaming `POST /v1/chat/completions`: `model` is exactly the
required text or vision env ID; `store:false`; a single expected choice; strict JSON-schema response
format; text uses text parts and vision uses the selected JPEG as an `image_url` `data:image/jpeg;base64,`
URI. Extract only the exact single choice’s assistant content, then validate it. No tools, model
fallback, provider `/models` request, arbitrary endpoint, or streamed response is accepted. Limits:
`max_completion_tokens=2048`; base64 chars, decoded JPEG, and total body each have exact/max+1 tests,
including the inherited 10 MiB streamed whole-request-body cap with absent or understated Content-Length; slow headers/body, 256KiB/max+1 response, 2 accepted/3rd busy
in-flight, deadline and future-cancellation tests are mandatory.

## Tasks

| ID | Exact files | Owner | Depends on | Actions | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|---|
| T-001 | New shared `vibe/server-ai-contract-v1.json` fixture; backend `src/test/resources/ai-contract-v1.json`; Android `app/src/test/resources/ai-contract-v1.json` | root contract freeze | — | Freeze byte-equivalent DTO fixture, nullable InBody segments and error/status examples; reject additions without joint change. | fixture parity test in each repository | Both writers consume identical contract. | AC-001–AC-003 |
| T-002 | Backend `/Users/raul/ItmoProjects/ValerochkaGymBackend`: `controller/ai/AiController.kt`, `controller/model/AiModels.kt`, `service/ai/{AiActionService,AiContextReader,AiProvider,HttpOpenAiChatCompletionsProvider,AiDraftValidator,AiImageInput}.kt`, `config/AiConfiguration.kt`, `config/Security.kt`, `Errors.kt`, `application.yml`; tests `AiIntegrationTest.kt`, `AiActionServiceTest.kt`, `HttpOpenAiChatCompletionsProviderTest.kt` | backend writer | T-001 | Implement auth/status/actions, owner/revision barriers, typed context/prompt/validation and bounded OpenAI-protocol adapter. Add no-write, A/B, stale/revoke/delete, malformed/NaN/unknown, image and cancellation tests. | backend targeted Gradle test suite for named AI tests | All provider calls are server-owned and bounded; fixture routes pass. | AC-001–AC-005 |
| T-003 | Backend `.env.example`, `docs/api.md`, generated `docs/openapi.json`, `README.md`, `infra/compose.production.yaml`, existing local deploy workflow and secrets-delivery scripts, and their tests | backend writer | T-002 | Mandatory: add default-off env contract and the existing local workflow’s stdin/temporary-file delivery, cleanup and no-echo behavior; test dummy secret delivery/cleanup and fake provider without network. Preserve sync v2/v3/capabilities. | backend config/integration and delivery-script tests | No literal secret/default model; 403 blocks live delivery only, not local verification. | AC-005, AC-007 |
| T-004 | Android after Stage 12: `data/backend/{BackendApi,BackendSync,SyncReadyAdapter}.kt`, new `data/ai/BackendAiRepository.kt` and DTOs; `ui/library/ExerciseLibraryViewModel.kt`, `ui/measurements/MeasurementEditorViewModel.kt` plus tests; fixture | Android writer | T-001, Stage 12 ACK receipt, accepted backend fixture | Own typed receipt→`syncReady`: serialize after Stage-12 sync completion, require final matching owner/no outbox/no unresolved conflict, refresh or verify catalog state, capture acknowledged personal revision and current local catalog revision, then recheck owner before returning Ready; otherwise Blocked/Failure; unowned/guest returns Blocked with zero AI API calls and never claims. Add consent/progress/manual fallback and owner/requestId/editor-generation guards. | targeted BackendAi/BackendSync/library/measurement tests | No late/foreign/stale draft applies. | AC-001–AC-004 |
| T-005 | Android retained domain/binding/removal: `data/ai/{ExerciseAiGenerator,InBodyReportAiReader,InBodyReportDraft}.kt`, new backend bindings/repository; `di/{DomainModule,AiApiSecrets}.kt`; `data/settings/{AiApiKeyStore,SettingsRepository}.kt`; `ui/settings/{SettingsViewModel,SettingsScreen}.kt`; remove direct `AiApi`, `AiApiConfiguration`, `AiModelCatalog`, `AiModelAvailability`, `AiResponseLogger`, AI Retrofit bindings; affected tests and `app/build.gradle.kts` | Android writer | T-004 | Retain/relocate exercise and InBody interfaces/results/draft, binding them to backend. Delete encrypted BYOK key and preview once, remove base/model settings and rewrite/remove model availability; remove debug logger. Existing UUID resolves only to same-owner synced local Long or safely fails. | targeted removal/binding tests + absence check + compile | No provider/BYOK transport remains; same-owner mapping failure is covered; one version bump. | AC-003, AC-006, AC-007 |
| T-006 | *(no edits)* | independent backend tester + readonly Sol/high reviewer | T-003 | Tester and reviewer audit independently without edits; route findings to T-006F before accepted fixture is handed cross-repo. | backend AI/config targeted audit + read-only review | Findings supplied to T-006F; no premature fixture acceptance. | AC-001–AC-005, AC-007 |
| T-006F | Backend files owned in T-002/T-003 | backend writer, then readonly reviewer and root | T-006 | Conditional: backend writer fixes consolidated findings and runs affected targets; reviewer rechecks affected findings; root runs final check/bootJar on stable diff. Tester/reviewer never edit. | affected tests then full check/bootJar | Backend fixture accepted only after this gate. | AC-001–AC-005, AC-007 |
| T-007 | *(no edits)* | independent Android tester + readonly Sol/high reviewer | T-005, T-006F | Android targeted audit/review, one Android writer fix batch/recheck, then root final gates. | targeted Android tests; `:app:testDebugUnitTest`; `:app:assembleDebug`; `:app:assembleRelease` unsigned attempt | No P0/P1; release result recorded even without signing. | AC-001–AC-007 |

## Ownership, waves, gates, risks

T-001 freezes first. One backend writer owns T-002–T-003 and conditional T-006F fixes; T-006 is readonly verification and root owns the final suite;
one Android writer owns T-004–T-005,
including shared Android DI/navigation/version choke points. They do not overlap. Backend may start
T-002 once T-001 passes; Android waits for Stage 12 and the backend-accepted endpoint fixture.
T-007 runs Android tester and Sol/high reviewer in parallel, then root final Android gates.

Relevant strict gates: authenticated HTTP/integration and fake-provider tests; owner/revision race,
image size/type/dimension, timeout/body-limit/concurrency/cancellation, env-delivery, Android
ViewModel cancellation/recreation/accessibility tests; Android full unit then debug assemble once.
Always attempt unsigned `:app:assembleRelease` after Android full unit/debug gates and record any
signing/tooling blocker. No Room migration, WorkManager, permission or screenshot gate is introduced.

Risks: Stage 12 readiness is a hard Android blocker; backend upstream/deploy access is currently
unavailable; Chat Completions image/structured behavior is enforced by adapter/validator tests, not
assumed across arbitrary endpoints. Rollback disables `AI_ENABLED`; no data needs migration or
deletion, and manual flows remain available.

Gate P self-check: every AC maps to a task and automated evidence; fixture, DTO, freshness, provider
and consent contracts are frozen; backend/Android ownership is exclusive; production AI/deploy is
explicitly unverified.

## Authoritative AI-01/health-disclosure integration delta (2026-09-10)

This delta supersedes the earlier exclusions of new Room data, migrations and retries/idempotency
only for the shared disclosure consent aggregate. AI draft POSTs remain non-retried and no raw
image or AI request/result enters that aggregate. The
accepted backend InBody endpoint requires an enabled, current health-AI disclosure receipt and the
exact `X-Health-AI-Disclosure-Revision` header. Without this bounded client foundation, Android can
only fail closed and has no usable pre-Health grant path.

AI-01 T-004 is the sole early owner of the already planned
`HealthAiConsentStateEntity`, `HealthAiConsentOutboxEntity` and their DAO. It creates them in the
actual next Room version after integrated CAL-01 (**19→20 unless the predecessor changes**), with
one handwritten migration, exported schema, incremental `Migration19To20Test` (renamed to the
actual N→N+1) and supported `Migration1To20Test` full-path test. Health T-003 reuses these exact
tables/DAO and does not create, rename, migrate, or copy a second consent store.

The state is scoped only to an authenticated owner and stores the received `revision`,
`noticeVersion`, `enabled`, and `recordedAtEpochMs` receipt defined by
`vibe/contracts/manual-health-contract.json`; absent state is exactly its zero receipt. The outbox
is owner-scoped and atomically persists `operationId`, complete first-send UTF-8 request bytes,
SHA-256 and dispatch state before POST. After receiving a response it persists the exact raw
receipt bytes before or with monotonic receipt application; an ambiguous response retains the
original request for literal retry. A network receipt never changes the immediate DataStore flag.
Its serializer, field order, `POST/GET
/v1/health-ai-disclosure`, CAS retry and `consent_operation_reused`/`consent_revision_conflict`
semantics are exactly the contract's `canonicalAiDisclosureRequest` and `AiDisclosureReceipt`; this
delta adds no wire field or endpoint.

The immediate local privacy choice remains the existing planned DataStore choice
`healthAiDisclosureEnabled`: explicit grant/revoke changes it; false blocks image encoding and
upload immediately. Room receipt/outbox work is permitted only for the same authenticated owner;
guest state never auto-grants, claims, transfers or reclassifies consent. A stale CAS first refreshes
the receipt and creates a new exact operation; it never retries or persists an image. InBody sends
only when the local flag is true and the same-owner receipt is enabled at the current notice; it
supplies that receipt revision in the header, and owner/revoke state is rechecked before applying a
late response. The normal user-visible grant/revoke control is added at the existing InBody action
boundary, not deferred to Health UI.

T-004 explicitly adds `health-ledger-v1` to the request capability union while retaining
`calendar-plans`. Both GET and POST `/v1/health-ai-disclosure` carry
`X-Gym-Capabilities: calendar-plans,health-ledger-v1` (or its equivalent parsed union).
Accepted capability is scoped to the current owner/response; missing acceptance, downgrade or
426 keeps the exact consent outbox and fails closed before image encoding/upload. InBody also
carries the exact enabled receipt revision header. Transport tests cover the capability on both
disclosure routes, accepted response, missing/downgraded acceptance and 426, including zero image
encode/upload/API calls until the admission conditions hold. No capability failure erases state
or enables the local privacy flag.

T-004 exact ownership expands to `data/db/entity/{HealthAiConsentStateEntity,
HealthAiConsentOutboxEntity}.kt`, `data/db/dao/HealthAiConsentDao.kt`, `data/db/{GymDatabase.kt,
Migrations.kt}`, `app/schemas/.../20.json` (actual N+1), `data/health/HealthAiDisclosureRepository.kt`,
the minimal existing DataStore setting accessor, `data/backend/{BackendApi.kt,BackendModels.kt}`,
`data/ai/BackendAiRepository.kt`, `ui/measurements/{MeasurementEditorViewModel,MeasurementEditorScreen}.kt`,
DI bindings, and matching DAO/migration/repository/ViewModel tests. The writer owns this whole Room
aggregate and must not split it with Health.

Required T-004 evidence adds exact first-send/retry and changed-body conflict, receipt monotonicity,
explicit grant/revoke, guest/no-auto-consent, claim and A→B isolation, process recreation,
stale-CAS refresh, header equality, no-encode/no-upload while absent/disabled/stale, and late
owner/revoke/provider-result discard tests. T-007 is strict for this delta: migration/schema/full
path, disclosure owner/claim and privacy gates join the existing AI checks; it still runs the
unsigned release attempt. The one AI-01 version increment remains unchanged.

DataStore alone is rejected: it can persist the immediate local block but cannot provide the
owner-scoped exact operation bytes, server receipt, idempotent retry, migration/claim proof or
transactional preservation needed by the accepted health contract.
