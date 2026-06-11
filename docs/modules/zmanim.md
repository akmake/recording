# Module: Zmanim (Halachic Times)

> Last updated: 2026-03-12

## Overview
Calculates and displays daily halachic times (zmanim) using GPS location and the KosherJava library. Supports per-zman alarm scheduling with configurable offset.

## User Flow

1. User navigates to Zmanim view (entry point unknown — not found in scanned code, likely a nav card or bottom tab)
2. `ZmanAdapter` renders a list of `ZmanItem` objects with label, sub-label, formatted time, icon
3. User can toggle alarm bell icon per zman → schedules/cancels an exact `AlarmManager` alarm
4. Alarm fires → `ZmanimAlarmReceiver.onReceive()` → posts notification on `zmanim_alerts` channel
5. Settings screen (`ZmanimSettingsFragment`) — toggle visibility of each zman, set alarm offset (minutes), configure Rambam text size and scroll speed

## Key Files

| File                                                     | Purpose                                                    |
|----------------------------------------------------------|------------------------------------------------------------|
| `adapters/ZmanAdapter.java`                              | Renders ZmanItem list, alarm toggle, alarm scheduling      |
| `models/ZmanItem.java`                                   | Data model: id, label, sublabel, time, isAlarmEnabled, icon |
| `ZmanimAlarmReceiver.java`                               | BroadcastReceiver, posts zman notification                 |
| `ZmanimSettingsFragment.java`                            | Visibility toggles, alarm offset, Rambam speed/text size   |
| `res/layout/fragment_zmanim_settings.xml`                | Settings UI                                                |
| `res/layout/item_zman.xml`                               | Row layout for zman list                                   |
| `res/drawable/ic_notifications_active.xml`               | Alarm enabled icon                                         |
| `res/drawable/ic_notifications_none.xml`                 | Alarm disabled icon                                        |

## Schemas Used

`ZmanItem` — device-only, not in Firestore.
SharedPreferences `ZmanimSettings` — see `docs/database.md`.

## Coupling

| If you change...                      | Also update...                                             |
|---------------------------------------|------------------------------------------------------------|
| `ZmanItem` fields                     | `ZmanAdapter.java`, `docs/database.md`                     |
| `ZmanimSettings` SharedPreferences keys | `ZmanimSettingsFragment.java`, `docs/database.md`        |
| `ZmanimAlarmReceiver.EXTRA_ZMAN_NAME` | `ZmanAdapter.java` (sends the extra), `docs/architecture.md` |
| Notification channel `zmanim_alerts`  | `docs/architecture.md`, `ZmanimAlarmReceiver.java`         |

## Known Issues / Pitfalls

- Fragment that hosts the zman list (likely `ZmanimFragment` or similar) was not found during scan — entry point is unknown.
- `ACCESS_FINE_LOCATION` permission is declared; runtime permission request handling was not scanned — may silently fail if permission is denied.
- `SCHEDULE_EXACT_ALARM` requires explicit user grant on Android 12+; no in-app prompt was found in scanned code.
