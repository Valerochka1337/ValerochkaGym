# Guest local access (#51, stage 13)

## Goal, dependencies and scope

Open the existing Room-backed workouts, history, configuration and CAL-01 plans without an account, while keeping dataset ownership, claim recovery, AI authorization and Google Calendar isolation intact.

This Android-only stage starts after the accepted guest-sync, AI-01 Android, CAL-01 Android and CAL-02 implementations are integrated. T-001 must record their actual class, DAO and migration names before source edits; expected names below come from the accepted plans and are not claims that those files already exist. No backend endpoint/schema, new Room table, claim/conflict policy, AI prompt, Google event, social feature, full-erase flow, dependency or runtime permission is added.

## Acceptance traceability

| ID | Required result |
|---|---|
| AC-001 | A fresh offline `GUEST` can start and finish a workout and read history; restart preserves it. |
| AC-002 | A guest can create, edit and delete CAL-01 plans locally; registration and local plan CRUD make zero Google calls. |
| AC-003 | Every current AI entry point uses one typed eligibility boundary. Guest/recovery/not-ready states offer an explicit useful action, manual alternatives remain usable, and no picker, attachment read/encoding or AI HTTP call starts before `Ready`. AI-01/server auth remains authoritative. |
| AC-004 | Registration is always reachable in Account settings and through a persistent blocked-AI card. It is semantic and readable at `fontScale=2.0`, without post-workout or repeated dialogs. |
| AC-005 | Room `GUEST`, `CLAIMED(B)` and `OWNED(A)` remain distinct from token state. Token mismatch cannot relabel, upload, replace, or render one owner's data as another's; actual dataset replacement invalidates foreign navigation and drafts. |
| AC-006 | Login starts/resumes guest-sync and remains pending through `CLAIMED` until its final ACK. It makes zero Google calls and never automatically replays an AI intent after login or process recreation. |
| AC-007 | Re-authentication of the same dataset and `GUEST -> CLAIMED -> OWNED` preserve the local route and active workout. Only a committed dataset replacement resets routes/actions, and restored detail/action state is revalidated against the current dataset. |

## Frozen access and identity contracts

### Room dataset identity

Room ownership is the sole authority for which local dataset may be rendered. The first ownership emission gates the app; before it, render a loading state and never infer `GUEST` from a missing token.

The accepted guest-sync ownership row must also expose one persisted opaque `datasetId: UUID` (or an exactly equivalent durable field). T-001 locates the real integrated row. If that row has no such field, the sole Room writer extends that existing row, handwritten migration and schema; this stage creates no separate table. Fresh databases and migrated databases receive the value exactly once. It is preserved across process death, sign-out, token refresh, re-authentication of the same owner and `GUEST -> CLAIMED(B) -> OWNED(B)`. A permitted `OWNED(A) -> B` replacement allocates a new value in the same Room transaction that commits the replacement. A failed/blocked preflight leaves it unchanged.

`datasetId` is deliberately independent of token/session state, `mergeId`, backend revision and ordinary sync generation. It changes only when the local personal dataset is actually replaced. UI code may observe it but may neither synthesize nor mutate it.

### Local-read and network matrix

| Room state | Token/session | Local UI | Account/recovery state | Backend/Google effect |
|---|---|---|---|---|
| first value pending | any | loading only | loading | none |
| `GUEST` | none | current guest dataset | registration available | no AI/backend/Google call |
| `GUEST` | matching newly authenticated B | same dataset | guest-sync claim begins; then `CLAIMED(B)` | only guest-sync protocol |
| `CLAIMED(B)` | B | same dataset | transfer pending/error until final ACK | only exact B claim recovery/sync |
| `CLAIMED(B)` | none/expired | same dataset | sign in as B to resume | none until B is authenticated |
| `CLAIMED(B)` | C | same dataset, never labelled C | mismatch; recover B | zero apply/push/replacement to C |
| `OWNED(A)` | A | A dataset | signed in; sync readiness shown separately | allowed only through existing owner/capability gates |
| `OWNED(A)` | none/expired | A dataset | local A data plus sign-in required | no network mutation |
| `OWNED(A)` | B | A dataset, never labelled B | mismatch/owner-switch preflight | zero B access until accepted replacement protocol commits |
| corrupt/unsupported ownership | any | fail-closed recovery, no personal content | explicit recovery | none |

Opening, continuing and finishing a local workout never depends on the network. Login and owner switching retain the guest-sync active-workout and preservation-preflight guards. Session/token flows only decorate the Room-derived access state and cannot rewrite it outside guest-sync.

### Navigation and one-shot ownership

`MainActivity` replaces the null-session `AccountGate` with the Room-backed loading/access gate and passes the durable `datasetId` to `MainScaffold`. The NavHost/saveable subtree is keyed by `datasetId`, not by user ID, token, `mergeId` or sync generation. Thus same-dataset login/re-auth and claim completion preserve route/back stack and active-workout UI; a committed A-to-B dataset replacement recreates navigation at the safe start destination.

Every requested route, restored detail argument, pending dialog/sheet action and asynchronous VM result captures its originating `datasetId` and local request epoch. Before navigation or state application it must match the current Room identity and the referenced Room row must still exist. A mismatch discards the action and goes to a safe root without exposing a foreign row. On dataset replacement, dispose the old NavHost, clear its `NavBackStackEntry` `ViewModelStore`s and cancel their `viewModelScope` jobs before enabling the new graph.

Cancellation is not the write authority: a suspended DAO operation may already be queued. T-001 inventories every UI mutation path reachable from those entries (expected current owners: routine detail/editor, gym detail/editor/repository, exercise detail/library/repository, measurement list/editor, workout list, configuration clone, and integrated CAL-01 plan repository). Each path that can suspend between capturing UI input and committing a personal write must pass the captured dataset ID to one shared Room-transaction guard which compares it to the current ownership row immediately before the insert/update/delete. A stale A editor therefore cannot insert into B after replacement. SavedState stores primitive editor/navigation input only; it never stores ownership authority, AI command, attachment bytes or an authorization result.

The transaction ordering closes both sides of the race: if the old write commits first, the guest-sync preservation preflight observes it and blocks replacement when it is unacknowledged; if replacement commits first, the write guard sees the new dataset ID and rolls the old write back.

### One AI eligibility boundary

Add one typed `AiEligibility` boundary consumed by exercise generation, InBody analysis and later AI cards. It reads Room ownership/dataset identity, verifies exact token owner, then delegates readiness to AI-01 `SyncReadyAdapter`. Its closed result set distinguishes `NeedsAccount`, `ClaimRecovery(target)`, `NeedsReauth(owner)`, `OwnerMismatch(expected)`, `SyncNotReady(reason)` and `Ready(requestContext)`. Only `Ready` may reach `BackendAiRepository`.

The eligibility check precedes opening an image picker, taking URI permission, reading/encoding an attachment, or building/sending an AI request. After every suspension, the caller rechecks dataset ID, editor generation/request epoch and current eligibility before emitting a picker effect or applying a response. Server 401/403 still becomes an unauthorized/re-auth state; UI eligibility is not a security boundary.

Blocked AI UI keeps manual exercise/measurement entry available and shows a persistent registration/recovery card with an explicit Account action. Account success returns to the unchanged local route when the dataset ID is unchanged, but clears the attempted AI command and attachment state. The user must explicitly invoke AI again; neither login callback nor SavedState restoration retries it.

### Calendar and registration boundary

Guest calendar CRUD uses only CAL-01 `CalendarPlanRepository`. Registration/sign-in does not connect Google, enqueue CAL-02 work or call `CalendarGoogleSyncRepository`. CAL-02 remains independently gated by explicit Calendar connection, settled `OWNED`, capability, ACK/outbox/conflict and active-workout contracts. Existing guest-sync UI remains truthful: authenticated is not merged, and `CLAIMED` stays visible until the final ACK.

## Executable tasks

| Task | Exact files / owner | Depends | Action | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|
| T-001 | **One Android writer**, read-only integration inventory in `vibe/guest-access-plan-track.md` | accepted guest-sync, AI-01 Android, CAL-01 Android, CAL-02 integrated | Record actual ownership row/DAO/migration, `BackendSync` ownership transaction/state API, AI-01 `SyncReadyAdapter`/`BackendAiRepository`, CAL-01 repository and CAL-02 transport names. Inventory every `BackendAiRepository` caller/picker effect and every personal-data mutation file reachable from old navigation entries; expected mutation owners are `ui/{routine/RoutineEditorViewModel.kt,routine/RoutineDetailViewModel.kt,gyms/GymEditorViewModel.kt,gyms/GymDetailViewModel.kt,exercise/ExerciseDetailViewModel.kt,library/ExerciseLibraryViewModel.kt,measurements/MeasurementEditorViewModel.kt,measurements/MeasurementsViewModel.kt,workouts/WorkoutsViewModel.kt,components/ConfigurationCloneViewModel.kt}`, `data/{GymRepositoryImpl.kt,ExerciseCatalogRepositoryImpl.kt}`, and the integrated CAL-01 `CalendarPlanRepository`. Confirm guest-sync preservation preflight is present and AI-01 backend auth tests are accepted. Resolve names only; do not mark a dependency implemented from this plan. | Contract checklist plus `rg` AI-call/picker/mutation inventory; no Gradle | Tracker contains actual symbols/files, current Room predecessor `N`, every AI entry point, and every old-entry mutation path requiring cancellation alone or a dataset-bound transactional guard. | AC-003,005–007 |
| T-002 | **Same writer, sole Room owner** — actual integrated equivalents of expected `data/backend/{BackendModels.kt,GuestMergeDao.kt,SyncSchema.kt,BackendSync.kt}`, `data/db/GymDatabase.kt`, `app/schemas/com.valerochka1337.valerochkagym.data.db.GymDatabase/<N+1>.json`; new `data/backend/DatasetAccess.kt`; migration/DAO tests | T-001 | Expose Room-derived access flow and durable dataset identity. If absent, extend only the existing owner row and handwritten `N -> N+1` migration; backfill once, preserve it across claim/reauth, rotate only inside `BackendSync`'s actual atomic accepted ownership/data replacement transaction, and fail closed on invalid state. Add the shared transaction-level `assertDataset(expectedDatasetId)` write primitive. Do not alter claim, ACK, conflict or preservation policy. | `*Migration<N>To<N+1>Test`, supported `*Migration1To<N+1>Test`, `*GuestMergeDaoTest`, new `*DatasetAccessTest` | Real Room tests prove first-emission loading, stable identity across restart/claim/ACK/sign-out, atomic rotation with replacement, blocked/cancelled replacement stability, stale-write rollback and all token-mismatch matrix rows. | AC-001,005,007 |
| T-003 | **Same writer** — `MainActivity.kt`; `ui/account/{AccountScreen.kt,AccountViewModel.kt,GuestAccessViewModel.kt}`; `ui/navigation/{MainScaffold.kt,MainScaffoldViewModel.kt,GymNavGraph.kt,DatasetNavigationGuard.kt}`; all actual mutation-owner files recorded by T-001 (expected paths listed there); focused VM/navigation/repository tests | T-002 | Replace session gate with dataset access gate; keep local scaffold available in valid Room phases. On dataset-ID change recreate the graph, clear old `NavBackStackEntry` `ViewModelStore`s and cancel old VM jobs; bind restored/requested routes and one-shot actions to the dataset, and revalidate detail rows. Route every T-001 write that can outlive its entry through the shared dataset check in the same Room transaction as its insert/update/delete. Preserve same-dataset route/active workout and reset only on actual replacement. Render guest, pending claim, recovery, expired-owner and mismatch states without relabelling data. | `*GuestAccessViewModelTest`, `*MainScaffoldViewModelTest`, `*GymRoutesTest`, affected editor/repository and account tests | Process recreation and late route/AI replies fail closed; an A editor suspended before a real local insert resumes after A-to-B replacement and proves zero B write. Same-dataset reauth/claim retains route and active workout. | AC-001,004–007 |
| T-004 | **Same writer** — new `data/ai/AiEligibility.kt`; integrated AI-01 `data/backend/SyncReadyAdapter.kt`, `data/ai/BackendAiRepository.kt`; `ui/library/{ExerciseLibraryViewModel.kt,ExerciseLibraryScreen.kt,AiExerciseCreationSheet.kt}`; `ui/measurements/{MeasurementEditorViewModel.kt,MeasurementEditorScreen.kt}`; DI module only if the new boundary requires binding; focused tests | T-001,T-002,T-003 and accepted AI-01 Android | Implement the typed matrix once and route every AI entry through it. Check eligibility before picker/request preparation, and dataset/epoch/eligibility after suspensions. Add persistent blocked-AI account/recovery card, keep manual paths usable, map server unauthorized, and clear rather than replay AI intent after account flow/recreation. | new `*AiEligibilityTest`; affected `*ExerciseLibraryViewModelTest`, `*ExerciseLibraryScreenTest`, `*MeasurementEditorViewModelTest`, measurement Compose test | Tests cover every eligibility result, zero-call/picker-before-ready, late response, login/process recreation without auto-retry, manual entry and unauthorized server mapping. | AC-003,004,006,007 |
| T-005 | **Same writer, regression ownership** — `ui/settings/{SettingsScreen.kt,SettingsViewModel.kt}`; affected account Compose tests; `ui/CalendarViewModelTest.kt`, CAL-01 repository tests, CAL-02 repository/worker fakes, workout/history tests already owning these flows | T-003,T-004 | Keep Account registration card persistent and accessible; prove no nag after workout. Add cross-feature regressions for offline workout lifecycle/history and local calendar CRUD, and assert registration/local CRUD produce zero Google transport/scheduling calls. Reuse accepted guest-sync/CAL-02 owner, active-workout and no-foreign-data tests rather than duplicate their algorithms. | `*AccountFormComposeTest`, affected settings Compose/VM tests, `*CalendarPlanRepositoryTest`, `*CalendarViewModelTest`, affected CAL-02 repository/worker tests, focused workout/history tests | AC-001/002/004/006 have end-to-end Android regressions at the lowest reliable layer, including restart and fontScale 2.0/semantics. | AC-001,002,004,006,007 |
| T-006 | **Same writer** — `app/build.gradle.kts`, tracker | T-002–T-005 | Compare target branch and increment `versionCode` and patch `versionName` exactly once. Record stable Gate I commands/results and changed-file ownership. | targeted tests above; `:app:compileDebugKotlin`; Spotless if configured | Stable diff has one feature version bump and all affected tests pass. | AC-001–007 |
| T-007 | Independent Android tester (sole Gradle owner) and strict read-only reviewer | stable T-006 | Audit ownership/token matrix, migration, navigation ABA/process death and stale writes, all AI entry points, picker ordering, calendar zero-I/O, active workout and accessibility. Run only missing targeted coverage; reserve full gates for T-009. | only missing targeted tests | Gate T/V returns no P0/P1 or one consolidated fix packet. | AC-001–007 |
| T-008 | Original writer, confirmed files only | T-007 findings | Apply one consolidated fix batch and rerun the smallest invalidated test set. | affected tests | Independent narrow recheck closes all P0/P1. | affected AC |
| T-009 | Root integration owner | T-007/T-008 | Check final file boundary/version and run root gates once on the stable diff. | `./gradlew :app:testDebugUnitTest`; `./gradlew :app:assembleDebug` | Evidence recorded; no backend deployment or real Google/AI request is claimed. | AC-001–007 |

## Ownership, waves and gates

One Android writer owns every production, Room/schema, navigation, account/settings, AI-gate, test and version edit so the dataset transaction and UI key cannot diverge. Wave 1 T-001; Wave 2 T-002 through T-006 sequentially; Wave 3 independent T-007; Wave 4 conditional T-008; Wave 5 T-009. No Gradle runs while the writer edits.

Required gates are handwritten incremental/full migration only if T-002 adds the field; real Room identity and atomic replacement tests; guest-sync owner/preflight/ACK and active-workout regressions; AI eligibility/picker/request race tests; saved-state/dataset ABA tests; CAL-01 offline and CAL-02 zero-call tests; immutable UDF states; TalkBack semantics, 48dp actions and `fontScale=2.0`. No backend, provider, manifest, permission, dependency, release or screenshot gate applies.

## Risks and rollback

Room and AtomicFile tokens cannot share a transaction, so access always follows Room identity while tokens can only enable matching recovery/network work. A saved navigation entry can outlive its data; dataset binding plus row revalidation prevents that entry from exposing a replacement dataset. Eligibility can become stale after a suspend; request epochs and final ownership/readiness checks discard it. Registration can finish before guest merge; the UI continues to show `CLAIMED` until the accepted final-ACK boundary.

Rollback is forward-compatible and non-destructive: an older client may ignore the identity field but must not remove it or reinterpret CLAIMED/OWNED as guest. No rollback clears personal data, journals, calendar metadata or AI state to bypass recovery.
