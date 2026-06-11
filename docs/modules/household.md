# Module: Household & Family Management

> Last updated: 2026-03-12

## Overview
Handles household creation/joining during onboarding and family member management post-setup.

## User Flow

**Onboarding (HouseholdSetupActivity):**
1. New user arrives after registration → must create or join a household
2. **Create:** generates 6-digit numeric code → Firestore `households.add({code, members:[uid], createdAt})` → updates `users/{uid}.householdId`
3. **Join:** enters code → queries `households where code == input` → adds uid to `members` array → sets `users/{uid}.householdId`
4. Success → `MainActivity`

**Post-onboarding (FamilyManagementFragment):**
Content unknown — not scanned. Likely shows household members, allows inviting via code, or manages member roles.

## Key Files

| File                                                    | Purpose                                          |
|---------------------------------------------------------|--------------------------------------------------|
| `HouseholdSetupActivity.java`                           | Create/join household at onboarding              |
| `FamilyManagementFragment.java`                         | Post-onboarding family management (not scanned)  |

## API Routes

No HTTP API. All Firestore.

| Operation                 | Firestore path                     |
|---------------------------|------------------------------------|
| Create household          | `households` (add doc)             |
| Join household            | `households` where `code == input` |
| Update user householdId   | `users/{uid}` (update)             |

## Schemas Used

`households` collection — see `docs/database.md`.
`users` collection — see `docs/database.md`.

## Coupling

| If you change...                 | Also update...                                               |
|----------------------------------|--------------------------------------------------------------|
| Household Firestore fields       | `HouseholdSetupActivity.java`, `MainActivity.java`, `docs/database.md` |
| Join code logic                  | `HouseholdSetupActivity.java:159` (6-digit random generator) |
| `householdId` assignment         | `docs/auth-flow.md`, `docs/state-management.md`              |

## Known Issues / Pitfalls

- Join code is a 6-digit random number — collision risk for large deployments (low for small households).
- `FamilyManagementFragment` content entirely unknown — see `docs/known-unknowns.md`.
- No code expiry or revocation mechanism for join codes.
- `partnerUid`/`partnerName` in `MainActivity` suggest a 2-person household model, but `households.members` is a list — multi-member support exists in data but may not be fully implemented in UI.
