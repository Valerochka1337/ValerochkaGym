# Google account and Calendar connection — implementation tracker

## Task status

| Task | Status | Owner | Dependencies | AC | Automated check |
|---|---|---|---|---|---|
| T-001 | pending | implementation writer | — | AC-001–AC-005, AC-007 | `*GoogleAuthManagerTest`, `*AccountViewModelTest`, `*CalendarAccountIdentityTest` — not run |
| T-002 | pending | implementation writer | T-001 | AC-003–AC-007 | Calendar/link/migration/PortableData filters — not run |
| T-003 | pending | implementation writer | T-001–T-002 | AC-004, AC-005, AC-007 | connected-identity weekly/recovery filters — not run |
| T-004 | pending | implementation writer | T-003 | AC-003–AC-008 | exact-target consent Settings filters — not run |
| T-005 | pending | tester + readonly Sol/high reviewer | T-004 | AC-001–AC-008 | targeted audit + strict review — not run |
| T-006 | pending | root session | T-005 | AC-001–AC-008 | full unit tests then debug assembly — not run |

## AC traceability

| AC | Tasks | Evidence |
|---|---|---|
| AC-001 | T-001 | backend-accept ordering and no Calendar side-effect test |
| AC-002 | T-001 | password-flow identity invariance tests |
| AC-003 | T-001, T-002, T-004 | exact target/no-resolution/non-null-token grant, failure retention, transaction, recreation/cancel/stale/concurrent Settings tests |
| AC-004 | T-001, T-003, T-004 | picker-before-authorize, exact pending target, and A→B owner mismatch tests |
| AC-005 | T-001, T-003 | account+scope revoke, backend/local/remote retention, disconnect zero-API weekly/recovery tests |
| AC-006 | T-002 | real Room owned/unknown migration, quarantine, and off-wire metadata tests |
| AC-007 | T-001–T-004 | recreation plus missing/different/revoked connected-account and weekly-regression tests |
| AC-008 | T-004 | Compose semantics/state/target/font/adaptive and busy-gate tests |

## Deviations

None. Record any change to the frozen CAL-01 payload, identity boundary, migration predecessor, or
revoke semantics before implementation.

## Findings

- Current `google_email` is legacy weekly-schedule identity, not the preferred Calendar identity.
- Current one-off event IDs are unbound; migration must quarantine, not infer/adopt, their owner.
- Calendar authorization result has no selected-email identity; explicit picker precedes Other-account
  authorization. Existing weekly ownerEmail/journal semantics are already separate and frozen.
- Consolidated Gate P corrections: `google_email` is candidate-only; all weekly interactive/recovery
  gates use connected identity; a connection commits only after exact account-bound noninteractive
  token success (no resolution plus token). SavedState keeps only kind/normalized target/token/busy,
  reissues that target after recreation and rejects stale/concurrent replies. Migration tests live
  under `data/db`; the eight-column task table makes T-003 strictly precede T-004.

## Command results

No commands run: planning files only.

## Residual risks

- External OAuth revocation/expiry is handled as unavailable linked work; it never authorizes another
  account or deletes a record.
- Calendar event creation before local transaction can orphan a remote event on local fault; do not
  infer its owner or issue cleanup under a different identity.

Gate P strict Sol/high: PASS after narrowed recheck. Prior connection retained until verified replacement; SavedState stores only opaque operation nonce, never credentials. Root restored explicit one version bump/compile/spotless ownership in T-004 after table consolidation.
