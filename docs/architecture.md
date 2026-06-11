# Architecture

> Last updated: 2026-03-12

Canonical source for: build config, Firebase services, Android permissions, notification channels.

---

## Build Configuration

| Property         | Value                        |
|------------------|------------------------------|
| App ID           | `com.example.goodstart`      |
| minSdk           | 24 (Android 7.0)             |
| targetSdk / compileSdk | 34                    |
| versionCode      | 2                            |
| versionName      | 2.0                          |
| Java version     | 17                           |
| Build file       | `app/build.gradle`           |

## Firebase Configuration

No `.env` file. Firebase is configured via `google-services.json` (not committed to the repo — must be obtained from Firebase Console).

| Service              | Gradle dependency                          | Used for                              |
|----------------------|--------------------------------------------|---------------------------------------|
| Firebase Auth        | `firebase-auth`                            | User login/register, session          |
| Firestore            | `firebase-firestore`                       | All shared data (tasks, chat, etc.)   |
| Firebase Messaging   | `firebase-messaging`                       | Push notifications (FCM)              |
| Firebase Storage     | `firebase-storage`                         | Media uploads (image/voice messages)  |
| Firebase Analytics   | `firebase-analytics`                       | Analytics (passive, no direct usage)  |

Firebase BOM version: `34.9.0`

## External Dependencies

| Library                              | Version  | Purpose                                  |
|--------------------------------------|----------|------------------------------------------|
| KosherJava zmanim                    | 2.5.0    | Halachic times calculation               |
| Retrofit2                            | 2.9.0    | HTTP client for Sefaria API              |
| converter-gson                       | 2.9.0    | JSON deserialization                     |
| OkHttp logging-interceptor           | 4.9.0    | HTTP logging (enabled at BODY level)     |
| Material Components                  | 1.11.0   | UI components                            |
| ViewPager2                           | 1.0.0    | Rambam swipeable pages                   |
| RecyclerView                         | 1.3.2    | Lists throughout the app                 |

## Android Permissions

| Permission                        | Required by                                      |
|-----------------------------------|--------------------------------------------------|
| INTERNET                          | Firestore, Sefaria API, FCM                      |
| ACCESS_NETWORK_STATE              | Connectivity checks                              |
| ACCESS_FINE_LOCATION              | Zmanim (GPS-based halachic times)                |
| ACCESS_COARSE_LOCATION            | Zmanim fallback location                         |
| SCHEDULE_EXACT_ALARM              | Zmanim alarm scheduling                          |
| VIBRATE                           | Zmanim alarm notifications                       |
| POST_NOTIFICATIONS                | FCM push + Zmanim alarm notifications            |

## Notification Channels

| Channel ID           | Constant location          | Purpose                    |
|----------------------|----------------------------|----------------------------|
| `messages_channel`   | `MainActivity.CHANNEL_ID_MESSAGES` | Chat message push notifications |
| `tasks_channel`      | `MainActivity.CHANNEL_ID_TASKS`    | Task-related push notifications |
| `zmanim_alerts`      | `ZmanimAlarmReceiver.CHANNEL_ID`   | Halachic time alarm alerts  |

## Activities (Manifest-registered)

| Activity                  | Exported | Role                                          |
|---------------------------|----------|-----------------------------------------------|
| `MainActivity`            | true     | App entry, fragment host, launcher activity   |
| `LoginActivity`           | false    | Email/password login                          |
| `RegisterActivity`        | false    | New user registration                         |
| `HouseholdSetupActivity`  | false    | Create or join a household                    |

## Background Components

| Component                 | Type              | Trigger                                          |
|---------------------------|-------------------|--------------------------------------------------|
| `FamilyHubFCMService`     | Service           | `com.google.firebase.MESSAGING_EVENT` intent     |
| `ZmanimAlarmReceiver`     | BroadcastReceiver | `AlarmManager` exact alarm (user-scheduled)      |

## App Theme

- Theme: `Theme.GoodStart` (`res/values/`)
- RTL supported: `android:supportsRtl="true"`
- CleartextTraffic: allowed (`android:usesCleartextTraffic="true"`) — review before production

## Sefaria API Base URL

`https://www.sefaria.org/` — see `api/RetrofitClient.java`
Timeouts: 30 s connect / read / write.
