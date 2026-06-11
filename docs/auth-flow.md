# Auth Flow

> Last updated: 2026-03-12

Canonical source for: Firebase Auth lifecycle, household gating, dev bypass, session state.

---

## Flow Diagram

```
App launch → MainActivity.onCreate()
    └─ FirebaseAuth.getCurrentUser()
         ├─ null → DEV BYPASS: householdId="dev_household", go to HomeFragment
         └─ user exists → Firestore users/{uid}.get()
               ├─ doc missing OR householdId null/empty → HouseholdSetupActivity
               └─ householdId found → HomeFragment
```

Login flow:
```
LoginActivity → FirebaseAuth.signInWithEmailAndPassword()
    └─ success → Firestore users/{uid}.get()
          ├─ householdId exists → MainActivity
          └─ no householdId / no doc → HouseholdSetupActivity
```

Register flow:
```
RegisterActivity → FirebaseAuth.createUserWithEmailAndPassword()
    └─ success → Firestore users/{uid}.set({name, email, uid})
          └─ HouseholdSetupActivity (always, householdId not yet set)
```

## Token / Session Storage

| Mechanism        | Where                 | Details                                          |
|------------------|-----------------------|--------------------------------------------------|
| Firebase session | Device keystore       | Managed by Firebase SDK; persists across restarts|
| householdId      | In-memory only        | Loaded into `MainActivity.householdId` on start  |
| userName         | In-memory only        | Loaded from Firestore `users/{uid}.name`         |

No JWT, no custom tokens, no cookies. Firebase handles the session entirely.

## Dev Bypass

File: `app/src/main/java/com/example/goodstart/MainActivity.java:43-48`

When `FirebaseAuth.getCurrentUser()` returns null (no signed-in user), the app does NOT redirect to LoginActivity. Instead it sets:
- `householdId = "dev_household"`
- `currentUserUid = "dev_user"`
- `userName = "מפתח"`

**Risk:** This allows unauthenticated use of all fragments. Must be removed before production release.

## Firestore User Document

Collection: `users`, document ID = Firebase Auth UID

Fields set on registration:
- `name` (String)
- `email` (String)
- `uid` (String)

Fields added after household creation/join:
- `householdId` (String — Firestore auto-generated doc ID)

## Household Gating

`HouseholdSetupActivity` is the gate between auth and the main app.
Users can either:
- **Create** a new household → generates a 6-digit numeric code, creates `households/{id}` doc, sets `users/{uid}.householdId`
- **Join** an existing household by entering a code → queries `households` where `code == input`, adds uid to `members` array, sets `users/{uid}.householdId`

## Session Fields in MainActivity

See `docs/state-management.md` for all in-memory fields.

## Common Auth Issues

| Issue                            | Cause                                              |
|----------------------------------|----------------------------------------------------|
| User sees HouseholdSetup on every login | `householdId` not set in Firestore user doc   |
| Login bypassed in dev            | `FirebaseAuth.getCurrentUser()` returns null; dev bypass triggers |
| Auto-login exception on LoginActivity start | `navigateToMain()` called, then exception → `auth.signOut()` |
| FCM token not associated to user | `FamilyHubFCMService.onNewToken()` doesn't save to Firestore |
