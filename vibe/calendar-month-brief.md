# Stage 09 / #52 — month calendar, nearest workout, and history

## Status

**pass (Gate R).** CAL-01 is a delivery dependency, not a product blocker: stage 09 reads its
Room-backed local plan projection and does not add calendar persistence, sync, Google work, or a
second resolver.

## Scope and non-goals

Extend the existing `Календарь` tab with a month view, selected-day contents, a nearest upcoming
local plan instance, a five-item completed-history preview, month count, `Сегодня`, schedule entry,
and a completed-history view with period and program filters. Completed history stays distinct from
plans and opens the existing workout-detail route.

Out of scope: CAL-01 Room entities/migration/portable data, Google reads or writes, Google import,
plan-to-fact matching, early start, CAL-02 exception editing/sync UX, a new module, and new
dependencies. The view never creates or recreates a Google event.

## Accepted product decisions

- The tab title remains `Календарь`.
- History contains only workouts with non-null `finishedAt`; it is grouped by the local date of
  `startedAt`, preserving the current calendar behavior.
- The preview contains exactly five completed workouts, newest first; it does not change when the
  user browses a different month.
- The nearest plan is independent of the displayed/selected month.
- Month and selected-day data are read through bounded CAL-01 queries. The UI must not request an
  unbounded occurrence flow through the 2100 horizon or implement a separate recurrence/DST
  resolver.

## Candidate acceptance criteria

| ID | Criterion |
|---|---|
| AC-001 | A Monday-first, fixed 42-cell month presents local-date completed and planned activity. Each day exposes semantic text including completed/planned counts, so several entries are not reduced to an ambiguous visual mark. |
| AC-002 | `Сегодня` displays the current month and selects the current local date. The displayed-month count includes finished workouts and resolved local plan instances for that month, without writes. |
| AC-003 | Selecting a date shows all finished workouts whose `startedAt` renders to that local date and all CAL-01-resolved plan instances on it. Plans and history remain separately labelled when they share a day. |
| AC-004 | The nearest entry is the earliest resolved local plan occurrence at or after the injected clock instant, independent of month navigation. Equal instants use CAL-01's canonical identity ordering. |
| AC-005 | The preview contains the five latest finished workouts, ordered by `finishedAt` descending and then workout UUID; it opens `workout_detail/{id}` and is independent of month/day selection. |
| AC-006 | The full history filters completed records by local-date period and routine, retains historical `WorkoutEntity.name` after a routine is deleted, and opens the same detail screen. |
| AC-007 | Loading/migrating/error/empty states distinguish unavailable plan data from no plans. Offline local Room reads remain visible and no presentation interaction reaches Calendar API/Google. |
| AC-008 | Navigation/`Сегодня` use `GymMotion`; meaningful actions use `GymHaptics`; controls have 48dp targets, TalkBack label/state/action, fontScale 2.0 and compact/medium/expanded coverage. |

## Current execution and data flow

`GymNavGraph` mounts `CalendarScreen`, which observes `CalendarViewModel.monthUi` and `daySheet`
(`app/src/main/java/com/valerochka1337/valerochkagym/ui/navigation/GymNavGraph.kt:217`,
`app/src/main/java/com/valerochka1337/valerochkagym/ui/calendar/CalendarScreen.kt:72`).

The current ViewModel combines `WorkoutDao.observeFinishedWorkouts()`,
`ScheduledWorkoutDao.observeAll()`, DataStore `WeeklyScheduleRepository.observe()`, routine names,
and a minute tick into `CalendarData`, then derives a month and selected-day sheet
(`ui/calendar/CalendarViewModel.kt:140`, `:158`, `:167`). It derives completed dates from
`WorkoutEntity.startedAt` and reads current-zone dates (`:311`, `:323`, `:363`).

Finished history is already a Room flow ordered by `startedAt DESC`
(`data/db/dao/WorkoutDao.kt:131`). `WorkoutEntity` retains `name`, `startedAt`, optional
`routineId`, and `finishedAt` (`data/db/entity/WorkoutEntity.kt:21`), so a deleted routine does
not remove history: its FK is `SET_NULL` (`:16`). Existing details are reached via
`workout_detail/{id}` from Calendar (`ui/navigation/GymNavGraph.kt:218`).

The pre-CAL-01 schedule source is unsuitable for stage 09: `ScheduledWorkoutDao` reads legacy
ad-hoc rows (`data/db/dao/ScheduledWorkoutDao.kt:17`) and only the first DataStore weekly rule is
shown for a selected weekday (`ui/calendar/CalendarViewModel.kt:341`). The current
`CalendarRepository` performs Google operations before Room writes
(`data/google/CalendarRepository.kt:71`, `:91`, `:108`). CAL-01 replaces that with Room as the
calendar SSOT (`vibe/local-calendar-plan.md:53`).

## CAL-01 dependency and query contract

CAL-01 owns plan/rule/exception persistence and its sole time resolver. Stage 09 consumes a
read-only presentation API; it neither traverses raw Room records nor repeats recurrence/DST logic.
The API is bounded and should use the CAL-01 captured time-zone/anchor and exception semantics:

```kotlin
interface CalendarPlanRepository {
  fun observeInstancesIn(range: LocalDateRange): Flow<CalendarPlanReadState>
  fun observeNearestInstance(from: Instant): Flow<CalendarPlanReadState>
}

sealed interface CalendarPlanReadState {
  data object Migrating : CalendarPlanReadState
  data class Ready(
    val instances: List<ResolvedCalendarInstance>,
  ) : CalendarPlanReadState
  data class Error(val message: String) : CalendarPlanReadState
}
```

`observeInstancesIn` is called only for the displayed month (for cells/count) or selected local day
(for its contents); it returns all source-distinct instances in that inclusive local-date range.
`observeNearestInstance(from)` returns at most the next occurrence across one-offs and rules. Its
implementation may inspect rule candidates internally, but must not materialize every recurrence to
2100. The `ResolvedCalendarInstance` must supply a canonical plan/rule/instance identity, source,
routine display state, original local date-time/zone, resolved instant, and cancellation/move state.

CAL-01's frozen rules remain authoritative: one-offs preserve an instant and IANA display zone;
rules preserve local wall time; a DST gap resolves to the first valid instant after the gap; an
overlap chooses the earlier offset; exception identity stays at original local date/time/zone
(`vibe/local-calendar-plan.md:29`, `:37`). `from` and all UI grouping should receive injected
clock/zone dependencies, replacing the current direct `System.currentTimeMillis()` and
`ZoneId.systemDefault()` usage (`ui/calendar/CalendarViewModel.kt:119`, `:208`, `:367`).

Recommended pure presentation helpers consume only these repository results plus
`WorkoutDao.observeFinishedWorkouts()`:

- `monthSummary(history, monthInstances, displayedMonth, zone)`
- `entriesForDay(history, dayInstances, selectedDate, zone)`
- `recentCompleted(history, limit = 5)`
- `filterCompletedHistory(history, localDateRange, routineId, zone)`

They do not resolve recurrence. `nearest` is supplied by CAL-01 as a bounded query; ties follow
its canonical identity order.

## Affected files and layers

| Layer | Files / responsibility |
|---|---|
| UI | `ui/calendar/CalendarScreen.kt`, `CalendarSheets.kt`, `CalendarFormat.kt`: month count, today, nearest, preview/full-history surfaces and accessibility. |
| UI state | `ui/calendar/CalendarViewModel.kt`: compose Room history with bounded CAL-01 read states; preserve `stateIn(WhileSubscribed(5000))` and one-shot events. |
| Domain/presentation | A small pure calendar presentation-query file in the package selected by CAL-01; no duplicate resolver. |
| Repository | CAL-01 `data/calendar/CalendarPlanRepository.kt`: the bounded read-only query contract above. |
| Navigation | `ui/navigation/GymNavGraph.kt`: only if a full-history destination is introduced; reuse existing `workoutDetail`. |
| Tests | `ui/CalendarViewModelTest.kt`, `ui/CalendarFormatTest.kt`, plus pure query tests. |

## Project invariants

- Room is the post-CAL-01 calendar SSOT; Google links/tokens/events never enter this read model.
- A plan, a resolved occurrence, and a completed workout are separate identities. Plan edits or
  cancellation never alter history.
- UI keeps immutable state and uses `stateIn(WhileSubscribed(5000))`; tests keep collectors live
  before observing `.value`, matching `CalendarViewModelTest.kt:351`.
- Colors use `MaterialTheme.colorScheme`; animation uses `GymMotion`; haptics use `gymHaptics()`;
  no chart or animation dependency is added.
- The 42-cell grid, Monday-first behavior and completed-over-planned precedence are existing
  behavior (`ui/calendar/CalendarFormat.kt:12`, `:37`, `:68`), but counts/text must carry any
  information a single mark cannot.

## Android risks and recommended verification

- **Time/DST/process recreation:** derive historical dates from injected zone and completed
  `startedAt`; consume CAL-01 occurrences only. Test month boundaries, gap/overlap supplied by the
  repository, clock equality, time-zone change and recreation.
- **Recurrence scale:** test repository calls prove month/day/nearest bounds; add a regression that
  a distant end bound is never requested or collected by the UI.
- **Concurrency/lifecycle:** `WhileSubscribed` sources restart safely; cancellation propagates;
  no view-owned recurrence job or Google call. Test migration→ready, error→ready, and changing
  Room flows while browsing another month.
- **History integrity:** test multiple workouts on one local day, active workout exclusion,
  deleted routine, same `finishedAt` UUID ordering, and preview independence from selection.
- **Accessibility/adaptive UI:** Compose semantics tests for day counts, nearest status and
  actions; fontScale 2.0; compact/medium/expanded. Existing cells already merge date/state
  semantics (`ui/calendar/CalendarScreen.kt:290`).

No runtime permission, worker, manifest, schema migration, security, or background-work change is
required by stage 09 itself. CAL-01 retains responsibility for those persistence/sync boundaries.

## Official sources used

none

## Files changed

`vibe/calendar-month-brief.md` only.
