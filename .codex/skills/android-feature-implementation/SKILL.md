---
name: android-feature-implementation
description: Implement non-trivial Android features end to end in ValerochkaGym with a latency-aware fast path and specialized research, planning, implementation, testing, and review subagents. Use for feature work spanning multiple files or layers; do not use for explanation-only requests or tiny isolated edits.
---

# Android Feature Implementation

Orchestrate a complete Android feature while keeping requirements, decisions, and final integration in the main thread. Delegate bounded work to the project custom agents:

- `android_feature_researcher`
- `android_feature_planner`
- `android_feature_implementer`
- `android_feature_tester`
- `android_feature_reviewer`

Use subagents for this workflow. Keep the main agent responsible for user communication, acceptance decisions, task routing, conflict prevention, finding triage, and the final handoff.

## Optimize for elapsed time

Use the fast path by default. Minimize sequential agent turns, repeated repository exploration, and
duplicate Gradle runs while preserving acceptance coverage and the final project gates.

- Spawn every subagent with `fork_turns: "none"` and pass a compact task packet containing the
  exact request, feature slug, affected scope, artifact paths, constraints, and required output.
- Start independent agents in the same wave and wait for the wave once. Do not parallelize work
  that depends on an unfinished result.
- Reuse Feature Brief, plan, tracker, and prior findings instead of asking later agents to rediscover
  the same context. Agents still read project files required by their role.
- Keep intermediate responses compact. Return decisions and evidence, not raw exploration logs.
- Run only targeted Gradle checks before the final stable diff. The main agent runs the full unit
  suite and debug assembly exactly once at the end.

The fast path should normally finish in at most four sequential agent waves: research, planning,
implementation, and parallel verification. Add a wave only after a material scope change or a
confirmed P0/P1 finding.

Escalate to the strict path when the feature changes a Room schema or migration, durable
sync/import/export formats, secrets/security/permissions, manifest or system components,
WorkManager/foreground-service semantics, concurrency or process-death guarantees, public data
contracts, or build/release configuration. Also use it when the user explicitly requests exhaustive
verification. A feature is not strict merely because it touches several ordinary UI/domain files.

## Select the workspace mode

Default to the current checkout and current branch. Do not create, suggest, switch, or delete a worktree merely because the workflow uses parallel subagents.

Use worktree mode only when the user explicitly asks to use a worktree or explicitly asks to run parallel sessions in separate worktrees. If worktree mode is explicitly requested, read [references/worktree-mode.md](references/worktree-mode.md) before any Git mutation.

Subagents inside one session share that session's checkout. A worktree isolates separate root sessions, not subagents from each other. Prevent same-checkout conflicts with file ownership even in worktree mode.

## Establish project context

1. Read the applicable `AGENTS.md` and `ARCHITECTURE.md`.
2. For any UI-facing change, read `docs/design-system.md` completely before research or planning.
3. Inspect `git status --short`, the current branch, existing `vibe/*-plan.md` and `*-plan-track.md`, and analogous production/tests. Preserve unrelated user changes.
4. Derive a stable kebab-case feature slug and maintain `AC-###` and `T-###` identifiers throughout research, plan, implementation, tests, and review.
5. Read [references/android-quality-gates.md](references/android-quality-gates.md) and route only relevant conditional gates into the plan.

Ask the user only when an unresolved choice changes product behavior, public contracts, data preservation, permissions, external side effects, or material scope. Otherwise state reasonable assumptions and continue.

## Run the workflow

### 1. Research

If an approved `vibe/<slug>-product.md` already defines behavior and acceptance criteria, the main
agent has verified the analogous code path, and there is no platform/API uncertainty, it may build
the Gate R Feature Brief directly and skip a dedicated research turn.

Otherwise use one `android_feature_researcher` for a focused feature. Use two in parallel when the work has
genuinely independent research lanes that can alter the plan, for example:

- UI/domain flow versus persistence/integration/test surface; or
- repository archaeology versus a new or version-sensitive Android API.

Give each researcher an exclusive question and evidence boundary; never ask both for a general
feature review. Keep them read-only, start them in one wave, then synthesize their compact results.
Do not spawn a second researcher when no useful independent lane exists—the extra agent adds cost
and synthesis time without reducing the critical path.

Gate R: the Feature Brief has testable acceptance criteria, non-goals, file/symbol evidence, affected layers, project invariants, Android risks, and explicit product-changing questions.

### 2. Plan and plan review

Spawn `android_feature_planner` after the research wave. Provide the user request, synthesized
Feature Brief, chosen assumptions, and required `vibe/<slug>-plan.md` and
`vibe/<slug>-plan-track.md` paths. Require a concise plan and a self-check against Gate P.

On the fast path, accept the planner's self-check and do not spawn a separate plan-review turn. On
the strict path, spawn `android_feature_reviewer` in plan-review mode with `gpt-5.6-sol` and high
reasoning. If it reports a P0/P1 or broken AC/task traceability, send only the findings back to the
planner and review the affected sections once. Escalate only unresolved product or data-safety
decisions to the user.

Gate P: every AC maps to at least one task and verification; architecture, contracts, dependencies, file ownership, execution waves, conditional gates, and high-risk decisions are explicit.

### 3. Implement

Use one `android_feature_implementer` by default; its configured profile is `gpt-5.6-terra` with
high reasoning. Give it exact T/AC IDs and exclusive file ownership. It implements production
behavior and the minimal meaningful regression tests together, runs targeted validation, and
updates its tracker rows.

Use at most two implementers in parallel only when the plan freezes their shared contracts and
assigns non-overlapping vertical slices and files. Never split a Room schema change across owners.
Give navigation, Hilt modules, the version catalog, and other shared choke points to one writer.
Tell every writer that other agents are active and that it must preserve their work.

Do not run Gradle while parallel writers are active. After both return, the main agent runs the
smallest shared compile or targeted-test gate once.

Gate I: the feature compiles through its targeted boundary, targeted tests pass, required Room schema artifacts exist, and deviations are recorded rather than silently changing the plan.

### 4. Verify and review

After a stable implementation diff and targeted compilation, spawn in one parallel wave:

- `android_feature_tester` for AC coverage and targeted test gates; and
- `android_feature_reviewer` for an independent diff review.

The reviewer stays read-only while the tester owns the only Gradle process. On the fast path both
use their configured Terra profiles. On the strict path, override the reviewer with `gpt-5.6-sol`
and high reasoning. Neither agent fixes production code. Do not ask the tester to repeat targeted
commands that already passed unless it identifies a coverage gap.

Gate T: relevant positive, boundary, failure, cancellation, recreation, and concurrency behavior is covered at the lowest reliable layer; each AC has evidence.

Gate V: there are no open P0/P1 findings. Fix P2 findings unless they are explicitly documented as accepted residual risk.

### 5. Fix loop and final gates

Route confirmed findings to the original implementer in one consolidated batch. Allow one normal
fix pass: run the smallest targeted test and ask the reviewer to recheck only affected findings.
Use a second pass only for a remaining P0/P1; otherwise document accepted residual P2 risk instead
of restarting the workflow.

Once the diff is stable, run the final project gates sequentially exactly once:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Apply any relevant conditional gates from `android-quality-gates.md`. Do not rerun research,
planning, the entire review, or the full Gradle gates unless the fix materially changes scope or
invalidates their evidence. If a P0/P1 survives two passes, report the concrete blocker instead of
looping indefinitely.

Update the tracker with final AC status, commands and outcomes, resolved findings, deviations, and residual risks. Do not commit, merge, cherry-pick, push, delete worktrees, or remove branches unless the user explicitly requested that Git action.

## Handoff

Lead with the feature outcome. Report:

- ACs delivered and any exclusions
- changed files grouped by behavior
- exact test/build commands and results
- review verdict and resolved findings
- residual risks or manual checks
- plan and tracker paths
- when explicit worktree mode was used: worktree path, branch/detached state, and integration status
