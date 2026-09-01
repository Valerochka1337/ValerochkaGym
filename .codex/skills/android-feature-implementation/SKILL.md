---
name: android-feature-implementation
description: Implement non-trivial Android features end to end in ValerochkaGym using specialized research, planning, implementation, testing, and review subagents. Use for feature work spanning multiple files or layers; do not use for explanation-only requests or tiny isolated edits.
---

# Android Feature Implementation

Orchestrate a complete Android feature while keeping requirements, decisions, and final integration in the main thread. Delegate bounded work to the project custom agents:

- `android_feature_researcher`
- `android_feature_planner`
- `android_feature_implementer`
- `android_feature_tester`
- `android_feature_reviewer`

Use subagents for this workflow. Keep the main agent responsible for user communication, acceptance decisions, task routing, conflict prevention, finding triage, and the final handoff.

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

Spawn `android_feature_researcher` with the exact user request, current feature slug, known constraints, and requested output. One researcher is the default. Spawn a second researcher in parallel only when the work splits into genuinely independent lanes such as codebase archaeology and a new/version-sensitive Android API. Keep both read-only, wait for both, and synthesize their evidence without copying raw logs into the main thread.

Gate R: the Feature Brief has testable acceptance criteria, non-goals, file/symbol evidence, affected layers, project invariants, Android risks, and explicit product-changing questions.

### 2. Plan and plan review

Spawn `android_feature_planner` after research completes. Provide the user request, synthesized Feature Brief, chosen assumptions, and required `vibe/<slug>-plan.md` and `vibe/<slug>-plan-track.md` paths.

Then spawn `android_feature_reviewer` in plan-review mode. If it reports a P0/P1 or broken AC/task traceability, send the findings back to the planner and review the revision once more. Escalate only unresolved product or data-safety decisions to the user.

Gate P: every AC maps to at least one task and verification; architecture, contracts, dependencies, file ownership, execution waves, conditional gates, and high-risk decisions are explicit.

### 3. Implement

Use one `android_feature_implementer` by default. Give it exact T/AC IDs and exclusive file ownership. It implements production behavior and the minimal meaningful regression tests together, runs targeted validation, and updates its tracker rows.

Multiple implementers in the same checkout are allowed only when the reviewed plan freezes their shared contracts and assigns non-overlapping files. Never split a Room schema change across owners. Give navigation, Hilt modules, the version catalog, and other shared choke points to one writer. Tell every writer that other agents are active and that it must preserve their work.

Do not run concurrent Gradle builds in one checkout. The main agent serializes build/test commands even when other read-only work continues.

Gate I: the feature compiles through its targeted boundary, targeted tests pass, required Room schema artifacts exist, and deviations are recorded rather than silently changing the plan.

### 4. Verify and review

After a stable implementation diff and targeted compilation, spawn:

- `android_feature_tester` for AC coverage and test/build gates.
- `android_feature_reviewer` for an independent diff review.

They may run in parallel only if the reviewer stays read-only and no other Gradle process runs in that checkout. Otherwise run them sequentially. Neither agent fixes production code.

Gate T: relevant positive, boundary, failure, cancellation, recreation, and concurrency behavior is covered at the lowest reliable layer; each AC has evidence.

Gate V: there are no open P0/P1 findings. Fix P2 findings unless they are explicitly documented as accepted residual risk.

### 5. Fix loop and final gates

Route confirmed findings to the original implementer. Run the smallest targeted test, ask the reviewer to recheck affected findings, then run the final project gates sequentially:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Apply any relevant conditional gates from `android-quality-gates.md`. Limit the cycle to two reviewer/tester fix passes. If a P0/P1 survives two passes, report the concrete blocker instead of looping indefinitely.

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
