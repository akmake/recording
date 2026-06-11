# State Management

> Last updated: 2026-03-12

Canonical source for: all in-memory session state.

---

## Overview

This is an Android app with no ViewModel, LiveData, or Redux-style store. All session state is held in `MainActivity` fields and passed to fragments via `newInstance()` bundle arguments.

There is no global application-level state object. No `Application` subclass was found with state.

---

## MainActivity Session Fields

File: `app/src/main/java/com/example/goodstart/MainActivity.java`

| Field          | Type   | Set by                        | Passed to                                           |
|----------------|--------|-------------------------------|-----------------------------------------------------|
| householdId    | String | Firestore on start (or dev bypass) | `HomeFragment`, `TasksFragment`, `ShoppingFragment` |
| currentUserUid | String | FirebaseAuth on start         | Accessible via `getCurrentUserUid()`                |
| userName       | String | Firestore on start            | `HomeFragment` via `newInstance()`                  |
| partnerUid     | String | unknown — requires clarification | `TasksFragment` via `newInstance()`              |
| partnerName    | String | unknown — requires clarification | `TasksFragment` via `newInstance()`              |

Public accessors:
- `getHouseholdId()` — returns householdId
- `getUserName()` — returns userName
- `getCurrentUserUid()` — returns currentUserUid

**Note:** `partnerUid` and `partnerName` are declared but never populated in the code scanned. They default to empty string. This is a known unknown — see `docs/known-unknowns.md`.

---

## Fragment Arguments (Bundle)

Fragments receive state via `Bundle` arguments set in `newInstance()` factory methods:

| Fragment         | Args passed                          |
|------------------|--------------------------------------|
| HomeFragment     | `userName`                           |
| TasksFragment    | `householdId`, `partnerUid`, `partnerName` |
| ShoppingFragment | `householdId`                        |

---

## Device-Local Persistent State

Managed via SharedPreferences — see `docs/database.md → SharedPreferences Stores`.

---

## No Client-Side Store

There are no:
- ViewModel / LiveData instances
- Stores (MobX, Redux, etc.)
- Application-level state singleton
- Shared Context / DI container

All state retrieval is on-demand from Firestore listeners within each fragment.
