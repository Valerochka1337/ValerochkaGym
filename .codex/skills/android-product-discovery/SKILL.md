---
name: android-product-discovery
description: Turn a rough ValerochkaGym Android feature idea into a researched, debated, and explicitly agreed product brief. Use when the user asks whether or how a feature should work, wants alternatives or competitor/current-practice research, or needs product behavior defined before Android implementation. Do not use for implementation-only tasks or for analyzing shipped telemetry.
---

# Android Product Discovery

Act as the product partner for ValerochkaGym before delivery begins. Challenge the proposed
feature at the level of the underlying user job, research the current landscape, broaden the
solution space, and converge with the user on an observable product contract. The user owns the
product decision; do not turn a provisional recommendation into an approved requirement.

This skill stops at product definition. It may inspect the repository and write the agreed product
brief, but it does not change application code, create an implementation plan, increment the app
version, or invoke an implementation skill unless the user separately requests that work.

## Establish local truth

1. Read the applicable `AGENTS.md` and `ARCHITECTURE.md` completely.
2. If the idea changes anything users see or interact with, read `docs/design-system.md`
   completely before evaluating UX options.
3. Inspect relevant screens, domain models, persistence, sync/export, tests, and existing `vibe/`
   artifacts. Treat repository behavior and explicit owner decisions as facts; do not propose a
   greenfield model that silently breaks them.
4. Check Git state before any file write and obey the repository's branch-isolation rules.

For ValerochkaGym, explicitly consider historical workout meaning, exercise identity and
statistics grouping, Room migration/data preservation, active-workout flows, Google Sheets
round-tripping, stable IDs, offline behavior, and backward compatibility when the feature can
touch them. Mention only the surfaces that are actually affected.

## Run discovery

### 1. Reframe the idea

Restate the initial request as:

- target user and situation;
- job or problem to solve;
- desired outcome;
- supplied evidence;
- assumptions and unknowns.

Treat a requested field, control, or screen as one candidate solution, not as the problem itself.
For example, “add grip” may reveal a broader need to represent exercise variants such as grip,
incline, stance, equipment, or unilateral side without fragmenting history.

Ask at most three high-leverage questions when their answers materially change user-visible
behavior, domain semantics, data preservation, or scope. Otherwise state assumptions and continue.

### 2. Research the current landscape

Internet research is required unless the user explicitly declines it or the network is
unavailable. Read [research-playbook.md](references/research-playbook.md) before searching.

Search both the literal feature and the broader user job. Investigate established workout apps,
exercise-domain terminology, relevant Android/platform guidance, and known failure modes. Prefer
current primary sources and first-party competitor documentation. Cite time-sensitive and factual
claims with direct links, and label repository facts, external facts, inferences, and assumptions
separately.

Do not claim that a marketed competitor feature is effective, common, or a best practice without
supporting evidence. Public reviews and forum posts are discovery signals, not representative user
research.

### 3. Expand and compare the solution space

Generate a small set of genuinely distinct models, normally two to five, plus “keep the current
behavior” when that is a credible option. Include at least one broader or structurally different
alternative to the user's initial implementation idea. Avoid cosmetic variants presented as
separate strategies.

For each option explain:

- how it works from the user's perspective, including a concrete example;
- user value and what problem it does not solve;
- advantages and disadvantages;
- discoverability and workflow cost during a workout;
- consistency with current repository behavior and product conventions;
- product-facing data, history, sync/export, privacy, accessibility, and migration concerns;
- future flexibility and the complexity it buys or avoids;
- evidence level and the cheapest useful validation, when uncertainty matters.

Use a compact comparison table when several options share the same decision dimensions. Do not
hide the recommendation: name the preferred option, why it wins for this personal app, the main
trade-off, and confidence level. Separate product concerns from possible engineering approaches;
do not lock in classes, tables, or APIs during discovery unless an existing contract makes them a
product constraint.

### 4. Debate and converge

Present the first recommendation as provisional. Engage directly with objections, point out
contradictions and downstream costs, and update the model when the user's preference reveals a
different priority. Preserve explicitly rejected alternatives and their reasons so they are not
rediscovered later.

Keep a lightweight decision ledger throughout the conversation:

- agreed decisions;
- rejected alternatives and rationale;
- facts and cited evidence;
- unresolved assumptions and questions.

Do not declare convergence from silence or from partial approval. Continue in conversation until
the user explicitly accepts a direction or asks to finalize it.

## Produce the agreed product brief

When the user accepts the behavior or asks to finalize, read
[product-brief-contract.md](references/product-brief-contract.md) completely. Produce a concise,
standalone Russian-language brief at `vibe/<feature-slug>-product.md` unless the user requests a
different location or chat-only output. Use a stable kebab-case slug.

The brief records the agreed behavior, evidence boundary, rejected alternatives, scope, observable
acceptance criteria, risks, and open questions. It must be understandable without the preceding
chat. Do not represent assumptions as validated facts and do not invent telemetry: this project
intentionally has no logging or analytics SDK. Prefer owner walkthroughs, task success, data
integrity, and regression evidence unless instrumentation is separately approved.

Before handoff, verify that:

- the problem and intended outcome are distinct from the chosen solution;
- the chosen model and rejected alternatives have explicit rationale;
- create, edit, use, history, empty, error, cancellation, and legacy-data behavior are covered when
  relevant;
- each acceptance criterion is observable and testable without prescribing implementation;
- current claims have citations and access/research dates;
- remaining assumptions and product decisions are visible;
- the brief contains no accidental implementation authorization.

After approval, recommend `android-feature-implementation` as the next workflow when the user wants
the feature built. Pass the brief path as the product source of truth, but do not start delivery
without a separate request.
