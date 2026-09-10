# Coach relations — Android tracker

Current: implemented and targeted-accepted; final integrated unit/debug gate passed
under T-012 in `partial-completion-plan-track.md`. No publication or live backend mutations.

| Task | Status | Owner | Evidence |
|---|---|---|---|
| T-001 | done | root | Exact fixture parity: `f5960d8a8fd269518aa6347b18607a778e4eff7f64aa5ecd9a2dc529aa501460`. |
| T-002 | done_targeted | root, sole Room/sync owner | Owner-bound journal, exact literal retry, no durable invitation token; repository/DAO tests PASS. |
| T-003 | done_targeted | root | Settings entry, pushed directory/detail, two independent grants, projections and revoke; VM and Compose PASS. |
| T-004 | done_targeted | root | Coach create/revise/revoke uses PLAN01 typed editor and durable operations. Pending creation blocks duplicate intent; recreation retries original bytes. Recipient apply stays PLAN01. |
| T-005 | done | root | Relations version44/1.3.36; Room25→26, historical1→26. Calendar AI subsequently increments to45/1.3.37 without another schema change. |
| T-006 | done | independent read-only reviewer | Narrow owner/token/retry/access/late-result review PASS, no P1. |
| T-007 | done_targeted | root | Concrete compile fixes and regression package passed. |
| T-008 | done_local | root | Final shared1262 tests/0failures/1conditional skip + assembleDebug PASS; `/private/tmp/yarumo-partial-final-full.log`. |

## Acceptance evidence

- AC-001/006/007: `CoachRelationsRepositoryTest` checks sanitized invitation storage,
  changed route/body denial, literal lost-response retry and owner A→B→A. No token enters
  SavedState/Room/DataStore or navigation; invitation recovery requires explicit re-entry.
- AC-002/003/005: strict allowlist DTOs, null/zero preservation, 200 exercises/1000 sets,
  `CoachRelationsViewModelTest` terminal404 clear and one revision restart without mixed pages.
- AC-004: authoring uses relation-scoped server mutations; PLAN01 alone projects accepted
  routines/calendar. Read access supplies recipient revision for initial creation; no foreign
  `/sync`, personal-data lookup or inferred revision is used.
- AC-008: `CoachRelationsComposeTest` checks independently selectable, labelled grants and
  reachable confirmation at fontScale2.0. Layout caps width840 and actions use48dp targets.
- Combined Relations/Calendar AI/profile targeted gate: **34 tests, 0 failures/errors/skips**,
  `/private/tmp/yarumo-relations-calendar-targeted.log`.

Directory and projection pages remain in memory; owner/relation changes clear them. Account
deletion removes only that owner's relation journal. Existing accepted training history is
untouched by relation revoke. No device or screenshot review was performed.
