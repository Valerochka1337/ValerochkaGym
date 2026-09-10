# Stage 09 / #52 — month calendar, nearest plan, history

## Goal, scope, assumptions

Extend the existing `Календарь` tab with a Monday-first fixed 42-cell month, selected-day agenda,
month count, `Сегодня`, nearest local-plan instance, five-item finished preview, and filtered full
history. Finished history is grouped by injected-zone local date of `WorkoutEntity.startedAt` and
includes only non-null `finishedAt`; historical `WorkoutEntity.name` remains after routine deletion.

Dependencies are completed CAL-01 Room projection/resolver and Stage 12 owner-ready sync. This is a
read-only presentation feature: no second calendar store, migration, recurrence/DST resolver,
Google read/write, Google event recreation, plan-to-fact matching, active-workout inclusion or
Calendar API transport. The tab title stays `Календарь`.

## Acceptance criteria

| ID | Acceptance criterion |
|---|---|
| AC-001 | A Monday-first 42-cell month exposes local completed/planned counts in each day’s semantic text, including multiple entries. |
| AC-002 | `Сегодня` selects current injected-zone date/month; the month count is finished workouts plus resolved local instances and never writes. |
| AC-003 | Day agenda shows all completed `startedAt` records and all source-distinct resolved local plan instances, separately labelled. |
| AC-004 | Nearest is the CAL-01 resolver’s earliest occurrence at/after injected clock instant, independent of browsing; equal times retain canonical identity order. |
| AC-005 | Preview is exactly five finished workouts ordered `finishedAt DESC`, then workout UUID, independent of selection, and opens `workout_detail/{id}`. |
| AC-006 | Full history filters completed records by injected-zone local-date period and routine; deleted-routine history retains name and opens the same detail route. |
| AC-007 | Migrating/loading/error/empty distinguish unavailable plans from no plans; offline Room content remains visible and all UI reads make zero Google/Calendar API calls. |
| AC-008 | Navigation/today use `GymMotion`; meaningful actions use `GymHaptics`; 48dp, TalkBack state/action, fontScale 2.0 and compact/medium/expanded behavior pass. |

## Frozen flow and contracts

`WorkoutDao.observeFinishedWorkouts + CalendarPlanRepository.observeInstancesIn(displayedMonth)
+ CalendarPlanRepository.observeInstancesIn(selectedDay) + observeNearestInstance(clock.instant)
→ pure presentation helpers → immutable CalendarViewModel StateFlow → CalendarScreen/sheet/history
→ existing workout-detail navigation`.

`CalendarPlanRepository` is CAL-01’s sole resolver and exposes only:

```kotlin
fun observeInstancesIn(range: LocalDateRange, displayZoneSnapshot: ZoneId): Flow<CalendarPlanReadState>
fun observeNearestInstance(from: Instant): Flow<CalendarPlanReadState>
```

The UI requests inclusive bounds only through `observeInstancesIn(range, displayZoneSnapshot)` for
displayed `YearMonth` and selected `LocalDate`; membership is the **effective resolved instant** rendered
in that UI-zone snapshot. A `MOVED` occurrence arriving from another original range is included, one
moved out is excluded; `CANCELLED` is excluded from planned counts, day agenda and nearest. Original
instance key remains identity only, never the presentation membership key. Nearest is one bounded query.
It never consumes a recurrence flow to 2100, raw plan/rule/exception tables, or
implements DST. CAL-01 returns canonical `ResolvedCalendarInstance` identity/source/original local
date-time-zone/resolved instant/cancellation-move/routine display state. Stage 09 supplies injected
clock and zone to grouping/filtering; the resolver keeps CAL-01’s captured anchor/zone and gap/overlap
rules. No mutable domain state is held by Compose; selected date/month/filter use `SavedStateHandle`
primitives, and flows use `stateIn(WhileSubscribed(5000))`.

One injected minute ticker produces the shared clock snapshot for all derived state; `onResume` emits
an immediate snapshot. It drives `flatMapLatest { observeNearestInstance(snapshot.instant) }`, so an
expired nearest refreshes with no Room mutation and exact equality is eligible. It does not expand a
range or create a resolver job per surface; cancellation/recreation follows the ViewModel flow.

Pure helpers are `monthSummary`, `entriesForDay`, `recentCompleted(limit=5)`, and
`filterCompletedHistory`. They receive completed Room rows plus bounded results; active workouts,
unfinished rows and Google identity are excluded. Repository states map independently: plan
`Migrating`/`Error` is not “no plan”; history may remain content while plan is unavailable.

## Tasks

| ID | Exact files | Owner | Depends on | Actions | Automated verification | Done condition | AC |
|---|---|---|---|---|---|---|---|
| T-001 | CAL-01-owned `data/calendar/CalendarPlanRepository.kt` and existing resolver tests; new presentation `ui/calendar/CalendarPresentationQueries.kt`, `ui/CalendarPresentationQueriesTest.kt` | implementation writer | CAL-01 complete | Freeze `range, displayZoneSnapshot` effective-instant membership. Test Jan→Feb move, Feb→Jan reverse move, cancellation exclusion, and one-off near midnight in another zone; retain original key only as identity. | `./gradlew :app:testDebugUnitTest --tests "*CalendarPresentationQueriesTest" --tests "*CalendarPlanRepositoryTest"` | Month/day bounds and moved/cancelled membership are deterministic. | AC-001–AC-006 |
| T-002 | `ui/calendar/{CalendarViewModel.kt,CalendarScreen.kt,CalendarSheets.kt,CalendarFormat.kt}`, `ui/navigation/GymNavGraph.kt` if full-history destination is needed; `ui/CalendarViewModelTest.kt`, `ui/CalendarFormatTest.kt`, `ui/calendar/CalendarScreenTest.kt` | implementation writer | T-001, Stage 12 | Compose bounded plan states with Room history and one injected minute ticker/on-resume snapshot shared by all derivations; `flatMapLatest` nearest. Test advancing beyond nearest without DB mutation, equality, recreation and cancellation; add UDF/UI states/accessibility. Never call Google/calendar transport. | `./gradlew :app:testDebugUnitTest --tests "*CalendarViewModelTest" --tests "*CalendarFormatTest" --tests "*CalendarScreenTest"` | All paths display correct source-separated content without writes or horizon expansion. | AC-001–AC-008 |
| T-003 | `app/build.gradle.kts`, `vibe/calendar-month-plan-track.md` | implementation writer | T-002 | Compare integrated target, apply exactly one #52 versionCode/patch bump, record targeted results. | `./gradlew :app:compileDebugKotlin` | Exactly one increment and no unrelated version change. | AC-001–AC-008 |
| T-004 | *(no edits)* | independent tester + readonly Sol/high reviewer | T-003 | Tester audits range calls, time/clock boundaries, multiple same-day entries, empty/error transitions and no transport. Reviewer checks CAL-01 boundary, history identity, navigation, semantics/adaptive behavior and version. | smallest invalidated targeted tests; read-only review | No P0/P1; one writer fixes consolidated findings. | AC-001–AC-008 |
| T-005 | `vibe/calendar-month-plan-track.md` | root session | T-004 | Record evidence and run final Android gates once after stable fixes. | `./gradlew :app:testDebugUnitTest` then `./gradlew :app:assembleDebug` | Every AC has evidence. | AC-001–AC-008 |

## Ownership, waves, gates, risks

One implementation writer owns T-001–T-003, including navigation and version choke points. T-001
and T-002 are serial because the bounded contract is frozen first. Tester and Sol/high reviewer run
in parallel after the stable diff; root runs final gates. This is fast-path UI work, with strict
review of injected clock/flow and CAL-01 boundary; it introduces no new durable/system contract.

Relevant tests cover Jan/Feb inbound and outbound moved instances, cancellation exclusion, one-off
zone-near-midnight, month edges, supplied DST occurrence, minute/on-resume clock equality, advancing
past nearest without Room mutation, recreation/cancellation, changing flow while browsing, migration→ready/error→ready, distant-range
nonrequest, multiple entries, active exclusion, deleted routine, UUID tie ordering, no Google API
fake calls, semantics/action/state, 48dp, fontScale 2.0 and compact/medium/expanded layouts.

Risk: CAL-01/Stage 12 availability blocks implementation; never substitute legacy schedule flow or
unbounded resolver. Rollback is UI-only: Room plans/history remain untouched, with no Google side
effect. Gate P self-check: each AC maps to one writer task and automated evidence; bounded resolver,
history date/order, source separation, ownership and version rule are frozen.
