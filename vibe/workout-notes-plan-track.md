# Stage 10 / #9 tracker

| Task | Status | Owner | Dependencies | AC | Automated check |
|---|---|---|---|---|---|
| T-001 | contract frozen; Android copy deferred | root contract owner | CAL-01 strategy | AC-006–AC-008 | core/backend SHA256 5a98f491bbb658c76e47933b49b50041fe1f1a16618b9cadfcf1befdbd22818b |
| T-002 | in progress | backend writer | T-001 | AC-006–AC-008 | backend integration/bootJar — not run |
| T-003 | pending | backend tester + Sol/high reviewer | T-002 | AC-006–AC-009 | backend audit/recheck — not run |
| T-003F | pending | backend writer / reviewer / root | T-003 | AC-006–AC-009 | conditional fixes, recheck, final backend suite |
| T-004 | pending | Android writer | T-001,T-003F,03/12/CAL | AC-001–AC-004, AC-006–AC-009 | migration/DAO/sync — not run |
| T-005 | pending | Android writer | T-004 | AC-001–AC-005, AC-009 | UI/repository/compile — not run |
| T-006 | pending | Android tester + Sol/high reviewer | T-005 | AC-001–AC-009 | audit/recheck — not run |
| T-006F | pending | Android writer / reviewer | T-006 | AC-001–AC-009 | conditional fixes and narrow recheck |
| T-007 | pending | root | T-006F | AC-001–AC-009 | unit → debug → unsigned release — not run |

## AC traceability

| AC | Tasks | Evidence |
|---|---|---|
| AC-001 | T-004,T-005 | Unicode/trim/recreation/double-save tests |
| AC-002 | T-004 | persistent-set-ID/reorder/duplicate/cascade tests |
| AC-003 | T-004,T-005 | history and notes-only prune/program-copy tests |
| AC-004 | T-004,T-005 | hint/standard/unpin invariance tests |
| AC-005 | T-005 | active/detail semantics and adaptive tests |
| AC-006 | T-001–T-004 | GET sync/changes/list/single, POST atomic old-shape/rejection and capability-transition tests |
| AC-007 | T-001–T-004 | both owner capabilities, before-paging, mixed batch/dirty/exact-retry merge/owner tests |
| AC-008 | T-002–T-004 | server-wins/tombstone tests |
| AC-009 | T-003–T-005 | active block/post-finish/dirty-baseline tests |

## Findings and residual risks

- Legacy strategy is resource-specific annotated-workout rejection, never blanket v4.
- `STANDARD` guard protects catalog mutations only; hint operations mutate an independent personal row.
- Actual migration predecessor follows integrated CAL-01/12 work.
- Notes are private typed future-AI data, never instructions or permissions.
- No command ran: planning files only.

Root strict corrections: T-003F/T-006F explicitly own conditional writer fixes and narrow review. Incapable workout projection is sendable only if both current AND acknowledged-baseline set notes are empty; pending annotated outbox bytes remain immutable. Finish/program tests explicitly named in T-005.

Gate P strict Sol/high recheck PASS. All remaining P0/P1/P2 closed; exact baseline/current projection and fix-loop ownership frozen.

Root fixture/new clarification bounded Sol-high review PASS; STANDARD cross-kind UUID guard and canonical hint UUID validation assigned to backend writer. Backend branch feat/workout-notes-contract from a5b567a. Android fixture waits its own feature branch.
