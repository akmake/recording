# Module: Auth & Household Setup

> Last updated: 2026-03-12

## Overview
Handles Firebase email/password authentication and the mandatory household onboarding step that gates access to the main app.

## User Flow

1. App starts → `MainActivity` checks `FirebaseAuth.getCurrentUser()`
2. If null → dev bypass (see `docs/auth-flow.md`) OR redirect to `LoginActivity`
3. `LoginActivity` → email/password → Firebase sign-in → check Firestore `users/{uid}.householdId`
4. No householdId → `HouseholdSetupActivity`
5. `HouseholdSetupActivity`: create household (generates 6-digit code) OR join with code
6. After household → `MainActivity` loads `householdId` + `userName` → `HomeFragment`

New user path: `RegisterActivity` → creates Firebase user + Firestore user doc → `HouseholdSetupActivity`

## Key Files

| File                                                           | Purpose                                  |
|----------------------------------------------------------------|------------------------------------------|
| `LoginActivity.java`                                           | Email/password login, auto-login check   |
| `RegisterActivity.java`                                        | New user creation, Firestore user doc    |
| `HouseholdSetupActivity.java`                                  | Create/join household, set householdId   |
| `MainActivity.java`                                            | Session loader, dev bypass               |

## Schemas Used

- `users` collection: name, email, uid, householdId — see `docs/database.md`
- `households` collection: code, members, createdAt — see `docs/database.md`

## Coupling

| If you change...                      | Also update...                                      |
|---------------------------------------|-----------------------------------------------------|
| Firestore user doc fields             | `LoginActivity`, `MainActivity`, `docs/database.md` |
| Household creation/join logic         | `docs/auth-flow.md`, `docs/database.md`             |
| Activity navigation flow              | `docs/auth-flow.md` flow diagram                    |

## Known Issues / Pitfalls

- **Dev bypass** in `MainActivity:43-48` — when no Firebase user is logged in, app skips auth entirely. Must be removed before production.
- `partnerUid` / `partnerName` in MainActivity are never populated during login (see `docs/known-unknowns.md`).
- `RegisterActivity` always goes to `HouseholdSetupActivity`; if user already has a household (edge case), they'll re-setup.
- `LoginActivity.navigateToMain()` catches exceptions from `navigateToMain()` at startup and calls `auth.signOut()` silently.
