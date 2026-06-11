# Contracts & Conventions

> Last updated: 2026-03-12

Canonical source for: naming conventions, Firestore patterns, code conventions that repeat across modules.

---

## Firestore Data Patterns

### Document ID as model ID
All models use the Firestore document ID as their `id` field. The pattern everywhere:
```java
doc.toObject(ModelClass.class);
model.setId(doc.getId());
```
Never use a manually assigned ID — always use the Firestore-generated doc ID.

### Server timestamps
All `timestamp` / `createdAt` / `lastMessageTime` fields are annotated `@ServerTimestamp` — they are set by Firestore on the server, not by the client. Never set these fields manually when writing.

### HouseholdId scoping
All shared household data lives under `households/{householdId}/` as subcollections.
Never create top-level collections for household-specific data (except `channels` which is cross-household).

### Display name duplication
Every write that references a user stores both `{field}` (UID) and `{field}Name` (String):
e.g., `addedBy` + `addedByName`, `assignedTo` + `assignedToName`, `createdBy` + `createdByName`.
This avoids extra Firestore lookups when displaying lists.

---

## Fragment Factory Pattern

All fragments use a static `newInstance(...)` factory method instead of a public constructor.
Arguments are passed via `Bundle` and read with `getArguments().getString(key)` in `onCreateView`.

Pattern:
```java
public static MyFragment newInstance(String householdId) {
    MyFragment f = new MyFragment();
    Bundle args = new Bundle();
    args.putString("householdId", householdId);
    f.setArguments(args);
    return f;
}
```

---

## Navigation Pattern

All navigation is driven from `MainActivity`:
- Fragment transactions use `replace()` + `addToBackStack(null)` (for drill-down) or just `replace()` (for root replacement).
- Fragments call back to MainActivity by casting: `((MainActivity) getActivity()).showXxxFragment()`.
- Back navigation: double-press back to exit app (2-second window). Single back press pops the fragment back stack.

---

## Model Conventions

- All model classes are in `models/` package.
- Models have a no-arg constructor (required by Firestore deserialization).
- Category constants are `static final String` on the model class (e.g., `ShoppingItem.CAT_SUPERMARKET`).
- Message types are `static final String` on the model class (`Message.TYPE_TEXT`, etc.).

---

## Adapter Conventions

- All adapters are in `adapters/` package.
- Adapters extend `RecyclerView.Adapter<ViewHolder>`.
- ViewHolder is a static inner class.

---

## Language / Locale

- App is Hebrew-first. All UI strings in the app use Hebrew.
- `android:supportsRtl="true"` — all layouts must be RTL-aware.
- Use `layoutDirection=RTL` and `textDirection=RTL` on programmatically created views.
- String resources are in `res/values/strings.xml` — use string references, not hardcoded Hebrew in Java where possible (some hardcoded strings exist in current code).

---

## Notification Channel Registration

Notification channels must be created before posting notifications on Android 8+.
`ZmanimAlarmReceiver` creates its channel inline on receipt.
`MainActivity` channel IDs (`CHANNEL_ID_MESSAGES`, `CHANNEL_ID_TASKS`) — channel creation location: unknown — requires clarification.

---

## No Backend Error Format

There is no custom backend. Firestore errors surface as `Exception` objects.
Error display pattern: `Toast.makeText(context, e.getMessage(), Toast.LENGTH_SHORT).show()` or `errorText.setText(e.getMessage())`.
