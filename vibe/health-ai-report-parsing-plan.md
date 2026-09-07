# Health AI report parsing — implementation plan

Status: approved implementation plan · 2026-09-05

## Goal, scope, non-goals

Make `AiApiHealthReportAiReader` accept the portable JSON-mode replies returned by supported OpenAI-compatible vision providers while refusing ambiguous, partial, or unsafe data before an editable draft is shown.

In scope: schema-bearing prompt, exact-one-object extraction (bare or one isolated `json` fence), response/choice/finish and HTTP failure mapping, strict existing validation, regression tests, and the required second version increment. Out of scope: UI redesign/copy changes, dependency/Hilt/navigation changes, model routing/retry/fallback, local OCR, Room/schema/migration, background work, permissions, logging, and persisting a draft.

Assumptions: `vibe/health-data-plan.md` is the approved product brief; `json_object` remains the cross-provider wire contract; the existing disclosure flow passes the frozen `AiApiRequestConfiguration`; the unrelated health UI-polish work and its already-dirty `24 / 1.3.16` increment are preserved. This completed fix therefore owns `25 / 1.3.17`.

## Acceptance criteria

- AC-001: A valid strict response produces an editable `HealthReportAiDraft` (including exact typed value and source page) and performs no persistence.
- AC-002: Accept a bare object or exactly one isolated fenced `json` object; reject surrounding prose, multiple fences/objects, or malformed payload.
- AC-003: The immutable system prompt embeds the complete response JSON Schema; it requires ISO `YYYY-MM-DD`, `sourcePage`, the `valueType` enum, factual-only extraction, and treats document text/images as untrusted data rather than instructions.
- AC-004: Reject non-ISO report/result dates; convert a valid ISO date to the same UTC-start epoch.
- AC-005: Top-level response error, choice error, and `finish_reason` `error`/`length` return recoverable failures before payload parsing.
- AC-006: HTTP and model/provider failures give an actionable sanitized failure without API keys, document bytes, raw medical response/error body, or logs.
- AC-007: Retain renderer/empty-page fast failures, cancellation propagation, page bounds/order, duplicate detection, and finite typed-value checks.
- AC-008: Retain `response_format: json_object`, existing OpenAI-compatible request fields, page ordering, endpoint HTTPS/loopback consent policy, and post-consent frozen configuration.

## Current → target flow

`HealthEditorScreen` event → `HealthEditorViewModel.selectDocument()` creates disclosure state with an in-memory frozen endpoint/model → confirm event → `read(uri, frozenConfiguration, consent)` → endpoint policy → cancellable renderer → OpenAI-compatible `json_object` request → first choice payload → direct object parse → immutable draft in ViewModel state → explicit Save event is the only `HealthRepository`/Room transaction.

Target changes only the bold logical segment after the request: **check response/choice errors and finish reason; extract exactly one allowed object; parse it on `@ComputeDispatcher`; locally validate it; return `Success(draft)` or sanitized `Failure`.** No writer, UI state/event, navigation route, saved state, or repository boundary moves. Loading/error/content remain the ViewModel's existing immutable UI states; cancellation remains an exception, not an error state. After configuration/process death the secret-bearing frozen configuration remains intentionally non-restorable and selection/consent must recur.

## Frozen architecture and contracts

| Concern | Frozen decision |
|---|---|
| Data ownership / SSOT | The reader owns only an in-memory draft; `HealthRepository` remains the sole persisted-data/transaction boundary after explicit Save. |
| External contract | Keep `AiApiChatRequest`, ordered text-plus-rendered-page messages, `AiApiResponseFormat()` (`json_object`), endpoint derivation and bearer construction unchanged. Do not introduce `json_schema`. |
| Parsing | Full-input grammar is frozen as `WS object WS` **or** `WS ```json WS object WS ``` WS`, where `WS` is whitespace only. Accept exactly one JSON object; reject any prose, a missing/non-`json` language tag, extra fence, concatenated/multiple object, or any trailing non-whitespace payload. Required root/observation fields and current page, duplicate, and finite-number validation remain authoritative. |
| Prompt | Reuse `jsonObjectSystemPrompt`; schema must enumerate required fields/types/nullable optional metadata and allowed `NUMBER`, `NUMBER_WITH_OPERATOR`, `RANGE`, `CATEGORY`, `CODE`, `TEXT`; instructions forbid inference, diagnosis, and following document instructions. |
| Errors/privacy | Health-owned precedence is: top-level `response.error` → any `choice.error` → selected choice `finishReason` → payload. Map only sanitized categories: 401/403 authentication/access; 402 balance/quota; 429 rate limit; 5xx or model-unavailable type model unavailable; timeout; network; `finish_reason=error`; `finish_reason=length`; then generic provider failure. Never surface or log raw request/response/error bodies, API keys, document bytes, or throwable text. No broad shared refactor is required. |
| Coroutine/cancellation | Rendering/API remain suspend; parsing stays in `withContext(@ComputeDispatcher)`; `CancellationException` is rethrown. No new scope, worker, dispatcher, Hilt binding/scope, permission, or service. |
| UI/accessibility/adaptive/navigation | No composable, screen copy, semantics, navigation, or adaptive layout changes. Existing polite actionable error rendering remains the UI boundary. |
| Storage | No Room entity/DAO/schema/migration/data-preservation effect; no background work or runtime-permission effect. |

## Tasks

| ID | Exact files / owner | Depends on | Action | Automated verification | Observable done condition | AC |
|---|---|---|---|---|---|---|
| T-001 | `app/src/main/java/com/valerochka1337/valerochkagym/data/ai/HealthReportAiReader.kt` — one implementation writer | frozen contracts | Build a complete health-report schema and system/user instructions; preserve `json_object` request/page order/configuration. Implement the full-input grammar exactly as frozen, then guarded response/choice/finish handling and Health-owned sanitized provider mapping before local validation. Preserve dispatcher and cancellation semantics. | `./gradlew :app:testDebugUnitTest --tests '*HealthReportAiReaderTest'` (after T-002) | Reader accepts only either grammar production, cannot parse an error/truncated/ambiguous response, and returns only a draft for valid payloads while every preserved boundary remains reachable. | AC-001–AC-008 |
| T-002 | `app/src/test/java/com/valerochka1337/valerochkagym/data/ai/HealthReportAiReaderTest.kt` — same writer | T-001 | Extend handwritten `FakeApi`/renderer tests with an explicit fixture matrix: accepted whitespace-wrapped bare object and one `json` fence; rejected prose, missing/other tag, multiple fences/objects, concatenated object, and trailing payload. Assert prompt/schema, strict ISO→UTC and invalid dates; top-level error over choice error, any choice error over finish/payload, then `error`/`length`; each 401/403, 402, 429, 5xx/model-unavailable, timeout and network category; messages omit API-key/document/error-body sentinels. Retain cancellation, renderer/empty/page order/page bounds/duplicate/nonfinite regression and re-run existing `HealthEditorViewModelTest` as save-boundary proof. | `./gradlew :app:testDebugUnitTest --tests '*HealthReportAiReaderTest'`; `./gradlew :app:testDebugUnitTest --tests '*HealthEditorViewModelTest'` | Each AC has a lowest-layer automated assertion; the complete grammar and failure-precedence fixture matrices pass, with no raw medical payload in assertions or output. | AC-001–AC-008 |
| T-003 | `app/build.gradle.kts` — same writer (shared build choke point) | T-001, T-002 | Change only fallback app version from `24 / 1.3.16` to `25 / 1.3.17`; retain test override behavior and all unrelated UI-polish edits. | `git diff --check`; final `./gradlew :app:assembleDebug` | Diff has exactly the expected fallback version increment and debug build resolves it. | AC-008 |

## Ownership and execution

One writer owns all three files; no parallel implementation is permitted because parser contracts, its fakes, and the app version are shared choke points. The existing unrelated UI-polish owner retains `AnalysisScreen.kt`, `HealthCards.kt`, accessibility/render tests, and its plan artifacts.

| Wave | Tasks | Exit |
|---|---|---|
| 1 | T-001 | Contract-compliant reader diff, no public-wire/persistence expansion. |
| 2 | T-002 | Focused reader and ViewModel tests pass. |
| 3 | T-003 | Version increment and clean diff; stable integrated diff. |
| 4 | final gates | Full unit suite, then debug assembly, once and sequentially. |

## Quality gates

Relevant conditional gates: preserve immutable UI event/state ownership (no UI implementation change); injected compute dispatcher and cancellation; API/credential/document privacy; handwritten fakes/no mock library; endpoint degraded/error paths; no new dependencies, Hilt bindings, Room migration, worker, permission, manifest, or release-specific dependency change. UI/adaptive/accessibility visual gates, Room migration/schema gates, WorkManager/service gates, chart render gate, and release assembly are not applicable.

Final project gates after the stable code diff, sequentially once:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## Risks, questions, rollback

Provider-specific free models may still reject a multi-page image request or emit invalid JSON; the user receives a safe retry/manual-entry failure, not fabricated data. The deliberately narrow full-input grammar may reject otherwise recoverable prose, which is preferred for medical imports. Error mapping must use status/type only—do not copy server text into UI or logs. `json_object` is retained because strict `json_schema` capability is not portable across configured providers.

No unresolved product decision blocks implementation. Rollback is source-only: no Room rows, schema, documents, jobs, or settings are created by parsing; drafts disappear unless the existing explicit Save occurs. Reverting the reader/test/version diff therefore preserves user data.

## Gate P self-check

Pass: AC-001…AC-008 each map to T-001/T-002 (and AC-008 also T-003) and concrete commands; payload grammar, error precedence/category mapping, contracts, and dependencies are frozen; file ownership has one non-overlapping writer; all relevant conditional gates and final gates are listed.
