# Health AI report parsing — tracker

Status: complete · targeted and full validation passed

| Task | Status | Owner | Depends on | AC | Automated evidence / done condition |
|---|---|---|---|---|---|
| T-001 | pass | implementation writer | frozen contracts | AC-001–AC-008 | Complete schema/prompt, full-input grammar, response/choice/finish precedence, sanitized provider failures, cancellation, frozen configuration and `json_object` wire contract are implemented; focused reader test passes. |
| T-002 | pass | implementation writer | T-001 | AC-001–AC-008 | Handwritten fixture matrix covers accepted/rejected grammar, schema prompt, dates/typed values, failure precedence/categories, sentinel-free messages and preserved renderer/page/cancellation boundaries; focused reader test passes. |
| T-003 | pass | implementation writer | T-001, T-002 | AC-008 | Fallback version is `25 / 1.3.17`; `git diff --check`, full unit suite and debug assembly pass. |

## AC → task → test traceability

| AC | Tasks | Planned automated proof |
|---|---|---|
| AC-001 | T-001, T-002 | Valid strict draft/source-page assertion; existing ViewModel explicit-save boundary test. |
| AC-002 | T-001, T-002 | Fixtures accept only whitespace-wrapped bare object or one `json` fence; reject prose, missing/other tag, multiple fences/objects, concatenated object, malformed and trailing payload. |
| AC-003 | T-001, T-002 | Assert prompt contains serialized schema, required date/page/value enum and untrusted-document instruction. |
| AC-004 | T-001, T-002 | Invalid report/result dates fail; valid ISO equals UTC-start epoch. |
| AC-005 | T-001, T-002 | Top-level error precedes any choice error; any choice error precedes finish/payload; `error` and `length` finish reasons fail before parsing. |
| AC-006 | T-001, T-002 | 401/403, 402, 429, 5xx/model-unavailable, timeout and network fixtures map to actionable messages that exclude API-key/document/error-body sentinels. |
| AC-007 | T-001, T-002 | Existing/new renderer failure, empty, cancellation, page order/bounds, duplicate, NaN/infinite cases. |
| AC-008 | T-001, T-002, T-003 | Request `json_object`, model/config/page order and policy tests; version/build gate. |

## Command results

| Command | Result |
|---|---|
| Planning-only change: Gradle commands | Not run (required only after application/build code changes). |
| `git status --short` before planning | Existing unrelated health UI-polish edits and version increment present; preserved. |
| `./gradlew :app:testDebugUnitTest --tests '*HealthReportAiReaderTest'` | PASS — repair-pass final run: BUILD SUCCESSFUL in 4s; 37 actionable tasks, 3 executed, 34 up-to-date. |
| `./gradlew :app:testDebugUnitTest --tests '*HealthReportAiReaderTest' --tests '*HealthEditorViewModelTest'` | PASS — 13 reader tests and 4 ViewModel tests. |
| `./gradlew :app:testDebugUnitTest` | PASS — BUILD SUCCESSFUL in 19s. |
| `./gradlew :app:assembleDebug` | PASS — BUILD SUCCESSFUL in 6s. |
| `git diff --check` | PASS — no whitespace errors. |

## Deviations and findings

- Gate P remediation: frozen full-input payload grammar and a Health-owned sanitized error precedence/category table; no shared AI refactor is required.
- The approved health-data brief already freezes draft-only persistence, consent, privacy, and the portable OpenAI-compatible `json_object` contract.
- The current reader lacks a complete schema prompt, fenced response support, and pre-parse response/choice/finish handling; implementation is intentionally confined to reader + its test.
- Independent verification passed `HealthReportAiReaderTest` together with `HealthEditorViewModelTest`; final integration passed the complete unit suite and debug assembly.
- Repair pass resolved P1: the serialized schema now requires `valueType` and encodes report/result dates with both `format: date` and the exact ISO regex; tests parse the embedded schema rather than matching prompt substrings.
- Repair pass resolved P1: blank `title`/`provenance` now fail local validation; `AiApiError.normalizedType` falls back to a string-valued `code` only after `type` and metadata, while `httpCode` remains unchanged.
- Repair pass resolved P2: optional metadata accepts absence or JSON null, while numeric/object values fail local validation; fixtures cover both cases and top-level/choice string-code precedence.

## Residual risks

- Provider output/request limits can still require retry/manual entry; no automatic retry/fallback is in scope.
- Exact fenced-object acceptance intentionally rejects conversational wrappers to prevent ambiguous medical imports.
- Low-risk review note accepted: nullable/malformed optional metadata has representative coverage (`unit`/`method`) rather than a duplicated fixture for every optional key; all fields share the same parser path.
