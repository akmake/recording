# Real-Time Events

> Last updated: 2026-03-12

---

## FCM Push Notifications

No WebSocket / Socket.IO. Real-time delivery uses **Firebase Cloud Messaging (FCM)**.

Service: `app/src/main/java/com/example/goodstart/FamilyHubFCMService.java`

### Incoming FCM message handling

| Data field `type` | Notification channel used             |
|-------------------|---------------------------------------|
| `"messages"`      | `MainActivity.CHANNEL_ID_MESSAGES`    |
| anything else     | `MainActivity.CHANNEL_ID_TASKS`       |

The service reads `remoteMessage.getNotification().getTitle()` and `.getBody()` for display.
On tap, the notification opens `MainActivity`.

### Token management

`onNewToken()` is called by Firebase when the FCM registration token changes.
**Current behavior:** token is received but NOT saved to Firestore.
This means server-triggered push notifications to specific users will not work until token persistence is implemented. See `docs/known-unknowns.md`.

---

## Firestore Real-Time Listeners

Each fragment sets up its own Firestore `addSnapshotListener()` for live data.
This is not documented per-event here — see the individual module docs in `docs/modules/`.

---

## No WebSocket

No Socket.IO, OkHttp WebSocket, or MQTT in use.
