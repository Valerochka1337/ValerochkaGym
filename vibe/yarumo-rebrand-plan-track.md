# Yarumo coach rebrand — implementation tracker

## Task status

| Task | Status | Owner | Dependencies | AC | Check |
|---|---|---|---|---|---|
| T-001 | pending | implementation writer | — | AC-002, AC-003, AC-005 | `*AppIconManagerTest`, `*LauncherBrandResourcesTest` — not run |
| T-002 | pending | implementation writer | T-001 | AC-001, AC-003, AC-005 | `*PostUpdateRelaunchTest`, `*AccountFormComposeTest` — not run |
| T-003 | pending | implementation writer | T-001–T-002 | AC-001, AC-003, AC-004 | brand/identity `rg` audit — not run |
| T-004 | pending | implementation writer | T-001–T-003 | AC-001–AC-005 | target-version check; `:app:compileDebugKotlin` — not run |
| T-005 | pending | tester + readonly Sol/high reviewer | T-004 | AC-001–AC-005 | targeted tester check + strict review — not run |
| T-006 | pending | root session | T-005 | AC-001–AC-005 | full tests → debug assembly; APK metadata; conditional release/device upgrade — not run |

## AC traceability

| AC | Tasks | Evidence |
|---|---|---|
| AC-001 | T-002–T-003 | string/Compose/notification assertions and repository/docs brand audit |
| AC-002 | T-001 | alias/adaptive/round/monochrome resource tests and approved-source audit |
| AC-003 | T-001–T-004 | alias/manifest plus immutable identity/updater parser/version audit |
| AC-004 | T-003 | README/design-system/branding README retained-identity and external OAuth checklist review |
| AC-005 | T-001–T-006 | resource tests, accessibility host checks, APK metadata, available-device upgrade evidence |

## Deviations

None. Record any asset derivation, compatibility invariant, unavailable release-signing/device, or
external OAuth-console status here.

## Findings

- Four aliases are coupled through `AccentColor.aliasName` and `AppIconManager`; names/order stay.
- Existing adaptive icon foregrounds are accent vectors, monochrome is a vector, and legacy density
  PNGs remain for non-adaptive launchers.
- Updater/release convention and portable UUID namespaces deliberately retain `ValerochkaGym`.

## Command results

No commands run: this is plan-only work.

## Residual risks

- Launcher crop/themed rendering differs by OEM; adaptive safe inset, round/monochrome resource
  checks, and available-device validation cover supported evidence without screenshots.
- Google OAuth console display name is external; README checklist records it as manual, not shipped.
