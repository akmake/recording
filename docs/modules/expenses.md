# Module: Expenses

> Last updated: 2026-03-12

## Overview
Household expense tracking. Allows adding categorized expenses with amounts. Shared across household members.

## User Flow

Entry point: unknown — `ExpensesFragment` navigation trigger not found in scanned code.

1. `ExpensesFragment` loads expenses from Firestore (path unverified — likely `households/{householdId}/expenses`)
2. User adds expense: title, amount, category
3. List shows all household expenses with who added them

## Key Files

| File                                                    | Purpose                                     |
|---------------------------------------------------------|---------------------------------------------|
| `ExpensesFragment.java`                                 | Main UI, Firestore reads/writes (not scanned)|
| `adapters/ExpenseAdapter.java`                          | RecyclerView adapter (not scanned)          |
| `models/Expense.java`                                   | Data model                                  |

## API Routes

No HTTP API. All Firestore.

Firestore path: unknown — requires clarification. Likely `households/{householdId}/expenses`.

## Schemas Used

`Expense` — see `docs/database.md`. Fields: id, title, amount (double), category, addedBy (UID), addedByName, timestamp.

## Coupling

| If you change...             | Also update...                                             |
|------------------------------|------------------------------------------------------------|
| `Expense` model fields       | `ExpensesFragment.java`, `ExpenseAdapter.java`, `docs/database.md` |
| Firestore path               | `docs/database.md`, `docs/sync-map.md`                     |

## Known Issues / Pitfalls

- `ExpensesFragment.java` was not scanned — Firestore path and full feature set unknown.
- No currency or locale handling observed in `Expense.amount` (plain `double`).
