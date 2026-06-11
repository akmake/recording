# Module: Shopping List

> Last updated: 2026-03-12

## Overview
Shared household shopping list with category filtering and quantity support. Items are checked off and can be added by any household member.

## User Flow

1. Home screen "Shopping" card → `MainActivity.showShoppingFragment()`
2. `ShoppingFragment` receives `householdId`
3. Loads items from Firestore `households/{householdId}/shopping` (real-time listener)
4. User adds item with name, category, quantity
5. User checks/unchecks items; checked items visually distinguished

## Key Files

| File                                                    | Purpose                                     |
|---------------------------------------------------------|---------------------------------------------|
| `ShoppingFragment.java`                                 | Main UI, Firestore reads/writes             |
| `adapters/ShoppingAdapter.java`                         | RecyclerView adapter                        |
| `models/ShoppingItem.java`                              | Data model with category constants          |

## API Routes

No HTTP API. All Firestore.

Firestore path: `households/{householdId}/shopping`

## Schemas Used

`ShoppingItem` — see `docs/database.md`.
Categories: supermarket (default), electronics, pharmacy, clothing, home, other.

## Coupling

| If you change...                  | Also update...                                          |
|-----------------------------------|---------------------------------------------------------|
| `ShoppingItem` model fields       | `ShoppingFragment.java`, `ShoppingAdapter.java`, `docs/database.md` |
| Category constants (`CAT_*`)      | Any code using raw string comparisons for categories    |

## Known Issues / Pitfalls

- No bulk delete of checked items — unknown if `ShoppingFragment` implements this (not scanned fully).
- Default category falls back to `CAT_SUPERMARKET` if null (defensive code in model).
