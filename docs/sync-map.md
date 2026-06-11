# Sync Map — Change Impact Rules

> Last updated: 2026-03-20

Canonical source for: what files change together.
**All paths are relative to the repo root.**

---

## When changing: `app/src/main/java/com/example/goodstart/MainActivity.java`

Also review:
- `app/src/main/java/com/example/goodstart/HomeFragment.java` — receives `householdId`, `userName` via `newInstance()`
- `app/src/main/java/com/example/goodstart/TasksFragment.java` — receives `householdId`, `partnerUid`, `partnerName`
- `app/src/main/java/com/example/goodstart/ShoppingFragment.java` — receives `householdId`
- `app/src/main/java/com/example/goodstart/FamilyHubFCMService.java` — references `CHANNEL_ID_MESSAGES` and `CHANNEL_ID_TASKS`
- `app/src/main/res/layout/activity_main_nav.xml` — hosts `fragmentContainer`
- `docs/auth-flow.md` — if auth/session logic changes
- `docs/state-management.md` — if session fields change

---

## When changing: `app/src/main/java/com/example/goodstart/models/HouseholdTask.java`

Also review:
- `app/src/main/java/com/example/goodstart/TasksFragment.java` — reads/writes task fields
- `app/src/main/java/com/example/goodstart/adapters/TaskAdapter.java` — binds task fields to views
- `docs/database.md` — update model table

---

## When changing: `app/src/main/java/com/example/goodstart/models/ShoppingItem.java`

Also review:
- `app/src/main/java/com/example/goodstart/ShoppingFragment.java` — reads/writes shopping fields
- `app/src/main/java/com/example/goodstart/adapters/ShoppingAdapter.java` — binds shopping fields to views
- Category constants (`CAT_*`) — any code that uses string literals instead of constants
- `docs/database.md` — update model table

---

## When changing: `app/src/main/java/com/example/goodstart/models/Message.java`

Also review:
- `app/src/main/java/com/example/goodstart/FamilyChatFragment.java` — family chat
- `app/src/main/java/com/example/goodstart/ChatFragment.java` — base chat (if shared)
- `app/src/main/java/com/example/goodstart/ChannelChatFragment.java` — channel chat
- `app/src/main/java/com/example/goodstart/adapters/ChatAdapter.java` — binds message fields
- `docs/database.md` — update model table

---

## When changing: `app/src/main/java/com/example/goodstart/models/Channel.java`

Also review:
- `app/src/main/java/com/example/goodstart/ChannelsFragment.java` — lists channels
- `app/src/main/java/com/example/goodstart/ChannelChatFragment.java` — channel detail
- `app/src/main/java/com/example/goodstart/adapters/ChannelAdapter.java` — binds channel fields
- `docs/database.md` — update model table

---

## When changing: `app/src/main/java/com/example/goodstart/models/Expense.java`

Also review:
- `app/src/main/java/com/example/goodstart/ExpensesFragment.java` — reads/writes expense fields
- `app/src/main/java/com/example/goodstart/adapters/ExpenseAdapter.java` — binds expense fields
- `docs/database.md` — update model table

---

## When changing: `app/src/main/java/com/example/goodstart/models/ZmanItem.java`

Also review:
- `app/src/main/java/com/example/goodstart/adapters/ZmanAdapter.java` — binds zman fields
- `app/src/main/java/com/example/goodstart/ZmanimAlarmReceiver.java` — uses zman ID/label
- `docs/database.md` — update model table

---

## When changing: `app/src/main/java/com/example/goodstart/LoginActivity.java`

Also review:
- `app/src/main/java/com/example/goodstart/RegisterActivity.java` — paired auth screen
- `app/src/main/java/com/example/goodstart/HouseholdSetupActivity.java` — next step after login
- `app/src/main/java/com/example/goodstart/MainActivity.java` — destination after successful login
- `docs/auth-flow.md` — update flow diagram if logic changes

---

## When changing: `app/src/main/java/com/example/goodstart/RegisterActivity.java`

Also review:
- Firestore `users` document fields set on registration — if fields change, update `LoginActivity.java` and `MainActivity.java` reads
- `app/src/main/java/com/example/goodstart/HouseholdSetupActivity.java` — always next step
- `docs/auth-flow.md`
- `docs/database.md` — users collection table

---

## When changing: `app/src/main/java/com/example/goodstart/HouseholdSetupActivity.java`

Also review:
- Firestore `households` collection schema — if fields change
- Firestore `users/{uid}.householdId` field — if assignment logic changes
- `app/src/main/java/com/example/goodstart/MainActivity.java` — reads `householdId` from user doc
- `docs/auth-flow.md` — household gating section
- `docs/database.md` — households collection table

---

## When changing: `app/src/main/java/com/example/goodstart/api/SefariaService.java`

Also review:
- `app/src/main/java/com/example/goodstart/api/RetrofitClient.java` — provides the singleton
- `app/src/main/java/com/example/goodstart/adapters/RambamPagerAdapter.java` — calls both endpoints; parses `SefariaResponse`
- `docs/api-reference.md` — update endpoint or response tables

---

## When changing: `app/src/main/java/com/example/goodstart/api/RetrofitClient.java`

Also review:
- `app/src/main/java/com/example/goodstart/api/SefariaService.java` — Sefaria service interface
- `app/src/main/java/com/example/goodstart/api/StudyService.java` — Shieor study server service interface
- `app/src/main/java/com/example/goodstart/adapters/RambamPagerAdapter.java` — uses Sefaria service
- `app/src/main/java/com/example/goodstart/HomeFragment.java` — uses study service
- `docs/api-reference.md` — base URL or timeout changes

---

## When changing: `app/src/main/java/com/example/goodstart/HomeFragment.java`

Also review:
- `app/src/main/res/layout/fragment_home.xml` — all view IDs
- `app/src/main/res/layout/item_study_card.xml` — study card view IDs
- `app/src/main/java/com/example/goodstart/api/StudyService.java` — API interface
- `app/src/main/java/com/example/goodstart/models/StudyDay.java` — response model
- `app/src/main/java/com/example/goodstart/models/Study.java` — study item model
- `app/src/main/java/com/example/goodstart/MainActivity.java` — navigation methods called from HomeFragment

---

## When changing: `app/src/main/java/com/example/goodstart/adapters/RambamPagerAdapter.java`

Also review:
- `app/src/main/java/com/example/goodstart/HomeFragment.java` — hosts the ViewPager2
- `app/src/main/java/com/example/goodstart/ZmanimSettingsFragment.java` — saves `RambamPrefs` keys consumed by this adapter
- `app/src/main/res/layout/item_rambam_page.xml` — view IDs used in ViewHolder
- `docs/modules/rambam.md`

---

## When changing: `app/src/main/java/com/example/goodstart/ZmanimSettingsFragment.java`

Also review:
- `app/src/main/java/com/example/goodstart/adapters/ZmanAdapter.java` — reads `ZmanimSettings` prefs
- `app/src/main/java/com/example/goodstart/adapters/RambamPagerAdapter.java` — reads `RambamPrefs`
- `app/src/main/java/com/example/goodstart/ZmanimAlarmReceiver.java` — reads alarm_offset pref
- `app/src/main/res/layout/fragment_zmanim_settings.xml` — all view IDs
- `docs/database.md` — SharedPreferences tables

---

## When changing: `app/src/main/java/com/example/goodstart/ZmanimAlarmReceiver.java`

Also review:
- `app/src/main/AndroidManifest.xml` — receiver registration
- `app/src/main/java/com/example/goodstart/alarm/ZmanimAlarmService.kt` — started by this receiver
- `docs/architecture.md` — notification channel `zmanim_alarm_channel`

---

## When changing: `app/src/main/java/com/example/goodstart/alarm/ZmanimAlarmService.kt`

Also review:
- `app/src/main/AndroidManifest.xml` — service registration (`foregroundServiceType="mediaPlayback"`)
- `app/src/main/java/com/example/goodstart/ZmanimAlarmReceiver.java` — starts this service
- `app/src/main/java/com/example/goodstart/alarm/AlarmConfig.kt` — data passed via extras
- `docs/architecture.md` — notification channel `zmanim_alarm_channel`

---

## When changing: `app/src/main/java/com/example/goodstart/alarm/AlarmConfig.kt`

Also review:
- `app/src/main/java/com/example/goodstart/ui/viewmodel/ZmanimViewModel.kt` — serializes/deserializes via Gson to SharedPreferences `ZmanimAlarms`
- `app/src/main/java/com/example/goodstart/ui/screen/ZmanimAlarmDialog.kt` — binds all fields to UI

---

## When changing: `app/src/main/java/com/example/goodstart/FamilyHubFCMService.java`

Also review:
- `app/src/main/AndroidManifest.xml` — service registration
- `app/src/main/java/com/example/goodstart/MainActivity.java` — `CHANNEL_ID_MESSAGES`, `CHANNEL_ID_TASKS` constants
- `docs/socket-events.md` — FCM handling table
- `docs/architecture.md` — notification channels table

---

## When changing: `app/src/main/AndroidManifest.xml`

Also review:
- `docs/architecture.md` — permissions and components tables
- Any activity added/removed must be reflected in manifest

---

## When changing: `app/src/main/java/com/example/goodstart/util/MamaarExtractor.kt`

Also review:
- `app/src/main/java/com/example/goodstart/ui/viewmodel/MamaarimViewModel.kt` — calls extractor
- `app/src/main/java/com/example/goodstart/model/Mamaar.kt` — data model
- `docs/modules/mamaarim.md` (if created) — feature docs

---

## When changing: `app/src/main/java/com/example/goodstart/ui/viewmodel/MamaarimViewModel.kt`

Also review:
- `app/src/main/java/com/example/goodstart/ui/screen/MamaarimScreen.kt` — list screen
- `app/src/main/java/com/example/goodstart/ui/screen/MamaarReaderScreen.kt` — reader screen
- `app/src/main/java/com/example/goodstart/util/MamaarExtractor.kt` — extraction logic

---

## When changing: `app/build.gradle`

Also review:
- `docs/architecture.md` — build config and dependency tables
- Firebase BOM version affects all Firebase services simultaneously
