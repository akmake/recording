# FamilyHub — AI Agent Entry Point

> Last updated: 2026-03-12

⚠️ MANDATORY: Documentation Update Protocol

## Hard Requirements

- **Rule 1** — Before modifying any file, read `docs/sync-map.md` to find what else must change.
- **Rule 2** — After any code change, update the canonical documentation affected. No exceptions.
- **Rule 3** — If a doc is found to be outdated, update it immediately, even if it was not part of the task.
- **Rule 4** — Every new model, fragment, activity, SharedPreferences key, or Firestore collection must be documented before the task is complete.
- **Rule 5** — Never duplicate canonical information across documents. Cross-reference only.

---

## Quick Navigation

| Document                    | Canonical source for                                         |
|-----------------------------|--------------------------------------------------------------|
| `docs/architecture.md`      | dependencies, Firebase config, permissions, notification channels |
| `docs/auth-flow.md`         | Firebase Auth lifecycle, household gating, dev bypass        |
| `docs/database.md`          | Firestore collections, models, SharedPreferences             |
| `docs/api-reference.md`     | Sefaria REST API calls                                       |
| `docs/state-management.md`  | in-memory session state (MainActivity fields)                |
| `docs/socket-events.md`     | real-time features (FCM)                                     |
| `docs/contracts.md`         | naming conventions, Firestore field conventions, patterns    |
| `docs/sync-map.md`          | which files change together                                  |
| `docs/known-unknowns.md`    | open questions, unverified paths                             |
| `docs/modules/`             | one file per business domain                                 |

---

## Stack Overview

- **Platform:** Android (Java), minSdk 24, targetSdk 34, version 2.0
- **Package:** `com.example.goodstart`
- **Backend:** Firebase (Auth + Firestore + FCM + Storage)
- **External API:** Sefaria.org REST (Retrofit + OkHttp + Gson)
- **Jewish calendar:** KosherJava `zmanim:2.5.0`
- **UI language:** Hebrew RTL (Material 1.11.0)
- **No server-side code** — all logic runs on-device against Firebase

## Navigation Architecture

Single-Activity pattern. `MainActivity` owns one `fragmentContainer`. No Navigation Component — all fragment transactions are manual `getSupportFragmentManager()` calls. `MainActivity` exposes `showHomeFragment()`, `showTasksFragment()`, `showShoppingFragment()` as public methods; other fragments navigate directly.

Activity stack:
```
LoginActivity / RegisterActivity → HouseholdSetupActivity → MainActivity (fragment host)
```

## Key Architectural Decisions

- **Dev bypass:** If `FirebaseAuth.getCurrentUser()` is null, MainActivity uses hardcoded `householdId = "dev_household"` instead of redirecting to login.
- **Household as tenant:** All shared data (tasks, shopping, chat) is scoped under a Firestore `householdId`. Users without a householdId are redirected to `HouseholdSetupActivity`.
- **No local DB:** No Room/SQLite. All persistence is Firestore (shared) or SharedPreferences (device-local settings only).
- **FCM token not persisted:** `FamilyHubFCMService.onNewToken()` receives new tokens but does NOT save them to Firestore (noted as TODO).
- **Rambam cycle:** Hard-coded 334-day cycle starting 2024-07-14. Today's position is computed by `RambamPagerAdapter` at construction time.
