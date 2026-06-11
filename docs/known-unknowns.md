# Known Unknowns

> Last updated: 2026-03-12

Open questions discovered during the documentation scan. Prevents every future AI session from reinvestigating the same gaps.

| Item                                    | Location                                                                       | Status                                      |
|-----------------------------------------|--------------------------------------------------------------------------------|---------------------------------------------|
| `partnerUid` and `partnerName` fields   | `MainActivity.java:25-26`                                                      | Declared, never populated — always empty string; partner loading logic may be missing |
| Expenses Firestore path                 | `ExpensesFragment.java` (not scanned)                                          | unknown — likely `households/{id}/expenses` but unverified |
| `FamilyManagementFragment` content      | `FamilyManagementFragment.java` (not scanned)                                  | unknown — purpose and Firestore collections unclear |
| FCM token not saved to Firestore        | `FamilyHubFCMService.java:52-54`                                               | Server-side push to specific users is broken; `onNewToken` is a no-op |
| `MainActivity` notification channel creation | `MainActivity.java` (no `createNotificationChannel` call found in scan)   | CHANNEL_ID_MESSAGES and CHANNEL_ID_TASKS are constants — channel creation location unknown |
| `ChatFragment` vs `FamilyChatFragment`  | Both exist in package                                                           | Relationship unclear — ChatFragment may be a base class or duplicate |
| `Channel.inviteLink` field              | `models/Channel.java:14`                                                       | Field present but usage/generation logic unknown |
| Dev bypass removal plan                 | `MainActivity.java:43-48`                                                      | Dev-only code that bypasses Firebase Auth; no guard to prevent shipping to production |
| `usesCleartextTraffic="true"`           | `AndroidManifest.xml:17`                                                       | Allowed in manifest — should be removed or scoped before production release |
| HTTP body logging enabled               | `api/RetrofitClient.java:14-15`                                                | BODY-level logging always on — should be disabled for release builds |
| `FamilyManagementFragment` navigation   | `MainActivity.java` (no `showFamilyManagementFragment` found)                  | How this fragment is reached is unknown |
| `ChannelsFragment` Firestore path for non-household channels | `ChannelsFragment.java` (not scanned)                | Channels appear to be top-level but cross-household access rules unclear |
| Rambam 334-day cycle hard-coding        | `RambamPagerAdapter.java:51, 67`                                               | Cycle start date 2024-07-14 is hardcoded; when next cycle starts this will be wrong |
| `google-services.json` not in repo      | repo root / `app/` directory                                                   | Required to build; must be obtained from Firebase Console for new dev environments |
