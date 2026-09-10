# Plan: Yarumo coach rebrand (#48, stage 07)

## Goal, scope, non-goals

Rename the user-facing Android product to **Yarumo coach** and replace launcher branding with the
approved existing Yarumo mark. Cover launcher/adaptive resources, user-visible name consumers,
README/design system, aliases, and compatible update behavior.

Do not change application ID, signing, authorities, Room/DataStore names, canonical UUID namespaces,
OAuth client/configuration, backend protocol, GitHub repository, or update asset name
`ValerochkaGym-v<version>.apk`. Do not generate a new logo, alter supplied originals, use UI
screenshots/video, or claim external Google OAuth-console branding is complete.

## Acceptance criteria

| ID | Criterion |
|---|---|
| AC-001 | `Yarumo coach` appears in launcher, account/settings, update notification, APK-install explanation, and other user-visible product messages; the old product name does not. |
| AC-002 | Default, round, monochrome, and all four accent launcher aliases resolve to the approved mark with adaptive safe inset/proportions; no wordmark appears as tiny launcher text. |
| AC-003 | Package/signature/authorities, storage/schema/UUID namespaces, OAuth, and updater repository/asset naming remain installation- and update-compatible. |
| AC-004 | README and design system state the new brand and explicitly retain legacy technical identifiers/external settings. |
| AC-005 | Resource/alias switching, light-dark/large-text host behavior, APK metadata, and available-device upgrade without clearing data are verified; no screenshot/video is made. |

## Current → target flow

Current manifest aliases map `AccentColor.aliasName` through `AppIconManager` to four adaptive icon
resources; foreground vectors are accent-colored. Target keeps alias names/order, `AccentColor` IDs,
and `AppIconManager` behavior, but every adaptive foreground wraps the approved unchanged raster
mark with the same safe inset. A separate one-color vector mask preserves circle, barbell, heart,
and pulse for themed monochrome. Legacy density PNGs are not edited; minSdk 36 uses the adaptive resources. `app_name` and listed hardcoded display strings become `Yarumo coach`.

## Frozen decisions and contracts

- Source assets are only `docs/branding/yarumo-coach/app-icon-source.png` (launcher mark) and
  `logo-with-wordmark.png` (documentation/presentation). Copy the icon source byte-for-byte into a
  new drawable-nodpi source resource; native XML `inset`/`bitmap` foreground wrappers preserve it,
  use bitmap android:gravity="fill" inside uniform 15% insets on all sides (70% square inner extent): the square source scales uniformly, retains aspect ratio, and is centered by equal insets. Tests assert fill gravity and all four 15% insets. Source SHA-256 is `0eabd16bbfc8ba3f1edaa14ad25702f5beb0131eb71cb63e3085b2ee230b2431`; the 1254×1254 RGB image is opaque, so its original square background remains inside the inset and the existing icon background fills the outside. Do not rasterize the wordmark into an
  icon or replace the source with generated art.
- Keep `ic_launcher*` resource names, four `activity-alias` names/order, manifest component
  enablement defaults, `AccentColor` IDs/aliases, and `AppIconManager` package component mapping.
  All aliases intentionally show the same approved opaque mark and existing shared background; accent choice still controls application colors. Preserve alias IDs/order/defaults. Round references an adaptive icon; monochrome is a readable `VectorDrawable` mask,
  system-tinted and not a color bitmap.
- `@string/app_name` becomes `Yarumo coach`; update only user-facing Kotlin text in account,
  settings, post-update notification, APK-install host, backend sign-in failure, and AI prompt
  brand context. Preserve legacy code identifiers, `Theme.ValerochkaGym`, namespaces, OAuth string
  key/value, URL/repository, release asset parser, authorities, and portable-data UUID values.
- No database/migration/DI/permission/background semantic change. Version bump is one #48 patch/code
  increment above all integrated prior stages; it does not rename release APK convention.
- External Google OAuth consent/app-name branding cannot be changed from this Android repository.
  README checklist tells the owner to update it manually if desired; it is not marked delivered.

## Tasks

| ID | Exact files | Owner | Depends on | Actions and automated verification | Done condition | AC |
|---|---|---|---|---|---|---|
| T-001 | `app/src/main/res/drawable-nodpi/yarumo_app_icon_mark.png`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/drawable/ic_launcher_foreground_lime.xml`, `app/src/main/res/drawable/ic_launcher_foreground_cyan.xml`, `app/src/main/res/drawable/ic_launcher_foreground_coral.xml`, `app/src/main/res/drawable/ic_launcher_monochrome.xml`, `app/src/main/res/drawable/ic_notification_gym.xml`, `app/src/main/res/mipmap-anydpi/ic_launcher.xml`, `app/src/main/res/mipmap-anydpi/ic_launcher_round.xml`, `app/src/main/res/mipmap-anydpi/ic_launcher_lime.xml`, `app/src/main/res/mipmap-anydpi/ic_launcher_cyan.xml`, `app/src/main/res/mipmap-anydpi/ic_launcher_coral.xml`, `app/src/test/java/com/valerochka1337/valerochkagym/data/appicon/AppIconManagerTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/data/appicon/LauncherBrandResourcesTest.kt` | implementation writer | — | Build approved asset pipeline: unchanged source copy, XML inset/bitmap foregrounds, separate monochrome vector mask; no density raster regeneration. Update the native notification vector to the matching monochrome mark. Assert each alias/resource resolves, alias switching still enables one component, adaptive/round reference expected layers, and monochrome is vector. `./gradlew :app:testDebugUnitTest --tests "*AppIconManagerTest" --tests "*LauncherBrandResourcesTest"` | All launcher entry points use approved mark with safe zone; aliases remain switchable. | AC-002, AC-003, AC-005 |
| T-002 | `app/src/main/res/values/strings.xml`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/account/AccountScreen.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/settings/SettingsScreen.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/update/PostUpdateRelaunchCoordinator.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/update/AppUpdateHost.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/backend/BackendUploadAdapter.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/data/ai/ExerciseAiGenerator.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/data/update/PostUpdateRelaunchTest.kt`, `app/src/test/java/com/valerochka1337/valerochkagym/ui/AccountFormComposeTest.kt` | implementation writer | T-001 | Change display name/user-facing copy only; retain updater asset parser and technical identity literals. Add/update focused text/notification semantics tests, including explicit account/settings/updater-host semantics checks under day and night configurations and fontScale=2.0 without screenshots. `./gradlew :app:testDebugUnitTest --tests "*PostUpdateRelaunchTest" --tests "*AccountFormComposeTest"` | User-visible product references are Yarumo coach; compatibility literals are unchanged. | AC-001, AC-003, AC-005 |
| T-003 | `README.md`, `docs/design-system.md`, `docs/branding/yarumo-coach/README.md`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/valerochka1337/valerochkagym/data/appicon/AppIconManager.kt`, `app/src/main/java/com/valerochka1337/valerochkagym/ui/theme/AccentColor.kt` | implementation writer | T-001–T-002 | Update brand/documentation and audit manifest/alias contracts. Modify manifest/manager/accent only if resource references require it; do not rename aliases/identifiers. Document retained application ID, signing, authorities, namespace/UUID, OAuth and updater asset/repository; add manual Google OAuth-console branding checklist. `rg -n "ValerochkaGym|Yarumo coach" README.md docs/design-system.md app/src/main` | Docs describe brand and retained compatibility identities; no legacy user copy remains. | AC-001, AC-003, AC-004 |
| T-004 | `app/build.gradle.kts`, `vibe/yarumo-rebrand-plan-track.md` | implementation writer | T-001–T-003 | Check target/integrated version and make exactly one #48 `versionCode +1`, patch `versionName +1`; run targeted compile/resource packaging. `./gradlew :app:compileDebugKotlin` | One version increment above target; implementation evidence recorded. | AC-001–AC-005 |
| T-005 | *(no file edits; reports to root)* | independent tester + readonly Sol/high reviewer | T-004 | Tester audits resources/display/update compatibility and runs smallest missing targeted check. Reviewer read-only checks asset provenance/insets, aliases, preserved identifiers, docs, version and release risk. Writer applies one consolidated fix/recheck. | No P0/P1; evidence reaches root. | AC-001–AC-005 |
| T-006 | `vibe/yarumo-rebrand-plan-track.md` | root session | T-005 | Stable diff: run full unit tests then debug assembly once; inspect debug APK metadata (`applicationId`, version, label) using installed Android build tools. Always attempt `./gradlew :app:assembleRelease` for R8/resource shrinking; report a blocker only from its actual failure. Compare immutable contracts and packaged manifest using the checklist below. Compare release signer certificate digests with `apksigner verify --print-certs` against a known prior release when signing is available; final signed GitHub CI release must prove continuity. If an emulator/device is available, install successive compatible debug APKs without clearing data and verify package/data/auth remain present via ADB/accessibility tree. | Final commands/results and any unavailable environment blocker recorded. | AC-001–AC-005 |

## Ownership and waves

| Owner | Exclusive responsibility |
|---|---|
| implementation writer | T-001–T-004 resources, brand copy/docs, contract audit, #48 version, and tracker evidence. |
| independent tester / readonly Sol-high reviewer | T-005 only; no edits. |
| root session | T-006 final gates, metadata/conditional release/available-emulator validation, tracker completion. |

One writer owns launcher resources, Manifest/alias choke points, and version. Wave 1 T-001; wave 2
T-002 → T-003 → T-004; wave 3 tester plus Sol/high reviewer; wave 4 root final validation.

## Quality gates, risks, rollback

- Verify XML/resource resolution under all aliases, day/night and font-scale host configuration;
  `AppIconManager` switching; updater parser still accepts `ValerochkaGym-v<version>.apk`; APK
  metadata; unconditional release R8 assembly attempt; and upgrade without clear data when a device is
  available. Use accessibility/ADB only, never UI screenshots.
- Risks: adaptive crop/themed mask legibility (safe inset + XML/vector/resource tests); resource
  rename breaks aliases/update (names/identifiers frozen); broad textual replacement corrupts UUIDs
  or asset parser (explicit invariant audit). Rollback is a forward release: restore prior resources/copy in a new patch with a strictly higher versionCode, same package/signature/authorities/storage. Repeat packaged metadata, signer comparison and no-clear-data upgrade checks; never distribute a lower/equal version as rollback.

## Gate P self-check

Pass: AC-001…AC-005 map to owned tasks/tests; launcher/alias/version has one writer; asset and
compatibility contracts are frozen; relevant system-resource/update, conditional release/device,
independent review, and root final gates are explicit.

## Strict-review compatibility checklist

Before implementation capture exact baseline values and paths for applicationId/namespace, both
manifest authorities, gym.db and all three DataStore names, OAuth resource key/value, all portable
UUID prefixes, GitHub owner/repository/asset parser constants, alias names/order/defaults. Compare
these exact values after the diff and in packaged manifest where applicable; broad rg is discovery
only, not proof. Record the source hash and baseline/current APK label/version/package. Preserve
signing configuration; compare release certificate SHA-256 to the prior published release using
apksigner when locally signed artifacts are available and require final signed CI verification.
Do not print OAuth tokens or signing secrets. No screenshots.

Root resolved P1 raster pipeline, compatibility proof, unconditional R8 attempt and forward rollback;
P2 geometry, intentionally identical accent launcher marks, concrete display configurations, serial
dependencies and notification mark are incorporated. All substantive findings resolved; final fill-gravity correction ready for narrow recheck.
