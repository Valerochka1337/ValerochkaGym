# Health report HTTP exception and Health audit — tracker

Plan: `vibe/health-http-audit-plan.md`. Status vocabulary: `pending | in_progress | done | blocked`.

Final status: complete — 2026-09-06. AC-001–AC-008 delivered; independent review passed.

| Task | Status | Owner | Dependency | Evidence / done check |
|---|---|---|---|---|
| T-001 Report-only policy and reader | done | Implementer A | frozen contracts | Wrapper has `TODO(health-report-http)`; policy and reader tests pass, including unchanged HTTP endpoint. |
| T-002 Editor disclosure and save/read/correction integrity | done | Implementer A | T-001 | Frozen recipient, cancelled disclosure, save/read guards, actual stored URI A/B and correction retention regressions pass. |
| T-003 Renderer and report-detail resilience | done | Implementer B | frozen contracts | Guarded resolver metadata and `originalExpected` detail status tests pass. |
| T-004 Bounded Health-tab audit | done | Root triage owner | T-001–T-003 | Fixed defects plus period-filter discrepancy disposition recorded below; no broad redesign. |
| T-005 Documentation, version, review and final gates | done | Root | T-001–T-004 | Architecture exception/TODO and version `26 / 1.3.18` complete; full unit tests and debug assembly pass sequentially. |

## AC → task → test traceability

| AC | Task(s) | Verification |
|---|---|---|
| AC-001 | T-001, T-002 | `HealthAiEndpointPolicyTest`, `HealthReportAiReaderTest`, `HealthEditorViewModelTest` |
| AC-002 | T-001, T-005 | shared-policy rejection test; reader report-only acceptance; architecture/diff review |
| AC-003 | T-002 | `HealthEditorViewModelTest` frozen recipient, HTTP wording/state, no render/API before confirmation |
| AC-004 | T-002 | `HealthEditorViewModelTest` save-while-reading, success binding, cancel/failure preservation |
| AC-005 | T-002 | `HealthEditorViewModelTest` text-only correction keeps target `originalExpected` |
| AC-006 | T-003 | `HealthDocumentRendererTest` missing/denied URI safe failure; `HealthReportDetailViewModelTest` provenance-independent original status |
| AC-007 | T-004, T-005 | recorded audit evidence/disposition; `git diff --check` |
| AC-008 | T-001, T-002, T-003, T-005 | focused commands; `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` |

## Deviations

- The temporary exception is report-only; shared InBody and restriction-interpreter policy is preserved.
- Root ran the combined targeted gate after both writers finished. Renderer fixture and asynchronous Room test timing needed test-only repairs; repeated only affected classes before the final full gates.

## Findings

- Confirmed, assigned: save during parsing can persist an old draft with a new pending URI; successful parse must bind its own original and cancel/failure preserve the prior association (T-002).
- Confirmed, assigned: loading a correction defaults `retainOriginal` to false instead of the target report’s `originalExpected`, so text-only correction can silently clear requested original retention (T-002).
- Confirmed, assigned: the renderer’s first two URI resolver/metadata accesses are outside its guarded path and can throw for a missing/denied URI (T-003).
- Confirmed, assigned: detail missing-original state compares provenance to `DOCUMENT`, hiding a failed original copy for real LAB provenance (T-003).
- Deferred pending product clarification: Health Analysis period filtering versus active health records. No behaviour change in this feature.

## Command results

- Gate P independent strict review: PASS, no P0/P1. Editorial renderer-test reference corrected.
- Root version increment complete: `25 / 1.3.17` → `26 / 1.3.18`; prior completed increments preserved.
- Targeted tests wait until both implementation writers finish; no parallel Gradle processes.
- Initial combined targeted gate: 44 tests, 3 failures in the new renderer fixture; production and tests compile. Other selected classes pass.
- Renderer fixture repair: use an attached handwritten provider with `ContentResolver.wrap(provider)` because Robolectric's normal resolver does not route asset-file acquisition through its registered-provider map. Call counters prove the intended metadata branches execute.
- `./gradlew :app:testDebugUnitTest --tests '*HealthDocumentRendererTest'`: PASS after fixture repair.
- Strengthened editor/reader gate: 29 tests, one actual-storage assertion ran before Room's asynchronous save completed; repaired by awaiting the buffered completion event before asserting.
- Independent production review: no P0/P1. Independent acceptance review: frozen-recipient and actual-original-storage tests strengthened; repaired coverage passes review.
- `./gradlew :app:testDebugUnitTest --tests '*HealthEditorViewModelTest' --tests '*HealthReportAiReaderTest'`: PASS, 29 tests after repair.
- `./gradlew :app:testDebugUnitTest`: PASS, 19s; XML reports total **1097 tests, 0 failures, 0 errors**.
- `./gradlew :app:assembleDebug`: PASS, 4s, after full unit tests.
- Final independent review: PASS, no P0/P1/P2 in the task delta. Resolved P2: recheck editor busy/disclosure state after suspended configuration lookup; assert actual HTTP request endpoint.
- `git diff --check`: PASS. No commit or push requested/performed.

## Residual risks

- A confirmed public HTTP recipient can receive a medical document while the exception exists. The user-visible recipient/model disclosure, explicit HTTP wording, no scheme downgrade, and named rollback TODO are required safeguards.
- No live server upload or manual device inspection was performed; source and automated checks establish the result. Screenshots were not opened or analyzed.

## Аудит и предложения по функционалу

Проверены пути обзора «Здоровье», редактора исследований, деталей, ограничений и существующие тесты; без ручной проверки на устройстве.

- **Исправления этого этапа:** сохранение во время распознавания и неверная привязка оригинала; сброс `originalExpected` при исправлении; исключения при чтении метаданных недоступного URI; неверное определение отсутствующего оригинала. Доказательства — регрессионные тесты T-001–T-003.
- **Оставшаяся проблема (P2):** `AnalysisViewModel.healthState` не зависит от выбранного периода, а `HealthOverviewCard` выводит весь список `reports`. При выборе короткого периода старые исследования остаются в истории вопреки описанию в `ARCHITECTURE.md`. Предлагается фильтровать историю по `reportedAt`, а последний замер и актуальные ограничения оставлять видимыми независимо от периода. В этом этапе поведение обзора не меняется.
- **Улучшение:** добавить в редактор ограничений дату начала и дату пересмотра — `startsAt`/`reviewAt` уже есть в модели, но UI не позволяет ими управлять. Дата пересмотра должна предлагать проверить ограничение, а не автоматически отменять его.
- **Улучшение:** показывать исследования в истории с датой и понятными русскими статусами вместо сырых значений `FINAL`/`CORRECTED`; это облегчит выбор нужного исследования с одинаковым названием.

Эти предложения основаны на текущем коде; продуктовые расширения не реализовывались.
