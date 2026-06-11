# Module: Household Tasks

> Last updated: 2026-03-12

## Overview
Shared to-do list scoped to the household. Supports assignment to household members, urgency flag, and completion tracking.

## User Flow

1. Home screen "Tasks" card → `MainActivity.showTasksFragment()`
2. `TasksFragment` receives `householdId`, `partnerUid`, `partnerName`
3. Loads tasks from Firestore `households/{householdId}/tasks` (real-time listener)
4. User can add tasks (assigned to self or partner), mark complete, mark urgent
5. Task notifications via FCM `type = tasks` (server-side — FCM token not yet saved)

## Key Files

| File                                                    | Purpose                                   |
|---------------------------------------------------------|-------------------------------------------|
| `TasksFragment.java`                                    | Main UI, Firestore reads/writes           |
| `adapters/TaskAdapter.java`                             | RecyclerView adapter for task list        |
| `models/HouseholdTask.java`                             | Data model                                |

## API Routes

No HTTP API. All Firestore.

Firestore path: `households/{householdId}/tasks`
Operations: addSnapshotListener (real-time), add, update (completed, urgent fields)

## Schemas Used

`HouseholdTask` — see `docs/database.md`.
Key fields: title, description, assignedTo (UID), assignedToName, createdBy, createdByName, completed, urgent, timestamp.

## Coupling

| If you change...                   | Also update...                                    |
|------------------------------------|---------------------------------------------------|
| `HouseholdTask` model fields       | `TasksFragment.java`, `TaskAdapter.java`, `docs/database.md` |
| Firestore subcollection path       | `docs/database.md`, `docs/sync-map.md`            |
| Task assignment (partnerUid logic) | `MainActivity.java` (partner loading), `docs/state-management.md` |

## Known Issues / Pitfalls

- `partnerUid` and `partnerName` passed from `MainActivity` are always empty string — assignment to partner may not work correctly. See `docs/known-unknowns.md`.
- Task completion is a simple boolean toggle — no history or audit trail.
