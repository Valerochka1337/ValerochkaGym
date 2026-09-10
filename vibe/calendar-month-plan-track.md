# Stage 09 / #52 tracker

| Task | Status | Owner | Dependencies | AC | Automated check |
|---|---|---|---|---|---|
| T-001 | pending | implementation writer | CAL-01 | AC-001–AC-006 | presentation query + CAL-01 repository targets — not run |
| T-002 | pending | implementation writer | T-001, Stage 12 | AC-001–AC-008 | calendar ViewModel/format/screen targets — not run |
| T-003 | pending | implementation writer | T-002 | AC-001–AC-008 | compile target — not run |
| T-004 | pending | tester + Sol/high reviewer | T-003 | AC-001–AC-008 | targeted audit/review — not run |
| T-005 | pending | root session | T-004 | AC-001–AC-008 | full unit → debug assemble — not run |

## AC traceability

| AC | Tasks | Evidence |
|---|---|---|
| AC-001 | T-001, T-002 | 42-cell Monday-first and multi-entry semantic tests |
| AC-002 | T-001, T-002 | injected today/month-count and zero-write tests |
| AC-003 | T-001, T-002 | complete source-separated day agenda tests |
| AC-004 | T-001, T-002 | bounded nearest/from/equal-identity tests |
| AC-005 | T-001, T-002 | five-item finishedAt/UUID ordering and detail route tests |
| AC-006 | T-001, T-002 | local-date/routine/deleted-routine history tests |
| AC-007 | T-002, T-004 | migrating/error/empty/offline/no-Google-call tests |
| AC-008 | T-002, T-004 | semantics, touch target, font-scale, adaptive, motion/haptic tests |

## Findings, commands, residual risks

- CAL-01 is the Room SSOT/resolver; stage 09 cannot reintroduce legacy schedule or Google calls.
- Existing history date is `startedAt` local date; preview is exactly five and selection-independent.
- No command ran: planning files only. CAL-01 and Stage 12 are hard dependencies; no workaround is
  planned. No Room, worker, permission, manifest, or Google-write change belongs to this stage.
