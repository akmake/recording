# Database

> Last updated: 2026-03-12

Canonical source for: Firestore collections, model field summaries, SharedPreferences stores.

---

## Firestore Collections

### `users`
Document ID: Firebase Auth UID

| Field       | Type   | Notes                                      |
|-------------|--------|--------------------------------------------|
| name        | String | Display name, set at registration          |
| email       | String | Email address                              |
| uid         | String | Redundant copy of doc ID                   |
| householdId | String | Set after household create/join; gates main app |

Used by: `LoginActivity`, `RegisterActivity`, `HouseholdSetupActivity`, `MainActivity`

---

### `households`
Document ID: Firestore auto-generated

| Field     | Type             | Notes                                       |
|-----------|------------------|---------------------------------------------|
| code      | String           | 6-digit numeric join code                   |
| members   | List\<String\>   | UIDs of all household members               |
| createdAt | Timestamp        | Server timestamp                            |

Used by: `HouseholdSetupActivity`

---

### `households/{householdId}/tasks` (subcollection)
Model: `models/HouseholdTask.java`

| Field          | Type      | Notes                              |
|----------------|-----------|------------------------------------|
| id             | String    | Set from Firestore doc ID          |
| title          | String    |                                    |
| description    | String    |                                    |
| assignedTo     | String    | UID                                |
| assignedToName | String    | Display name                       |
| createdBy      | String    | UID                                |
| createdByName  | String    | Display name                       |
| completed      | boolean   | Default false                      |
| urgent         | boolean   |                                    |
| timestamp      | Timestamp | @ServerTimestamp                   |

Used by: `TasksFragment`, `TaskAdapter`

---

### `households/{householdId}/shopping` (subcollection)
Model: `models/ShoppingItem.java`

| Field       | Type      | Notes                                                    |
|-------------|-----------|----------------------------------------------------------|
| id          | String    | Set from Firestore doc ID                                |
| name        | String    |                                                          |
| checked     | boolean   | Default false                                            |
| addedBy     | String    | UID                                                      |
| addedByName | String    | Display name                                             |
| category    | String    | One of: supermarket, electronics, pharmacy, clothing, home, other |
| quantity    | int       | Default 1, min 1                                         |
| timestamp   | Timestamp | @ServerTimestamp                                         |

Used by: `ShoppingFragment`, `ShoppingAdapter`

---

### `households/{householdId}/messages` (subcollection)
Model: `models/Message.java` — family private chat

| Field            | Type      | Notes                              |
|------------------|-----------|------------------------------------|
| id               | String    | Set from Firestore doc ID          |
| senderId         | String    | UID                                |
| senderName       | String    | Display name                       |
| text             | String    |                                    |
| type             | String    | text / image / voice               |
| mediaUrl         | String    | Firebase Storage URL (image/voice) |
| voiceDurationSec | long      | For voice messages                 |
| timestamp        | Timestamp | @ServerTimestamp                   |
| read             | boolean   | Default false                      |
| replyToId        | String    | ID of replied-to message           |
| replyToText      | String    | Preview text of replied-to message |

Used by: `FamilyChatFragment`, `ChatFragment`, `ChatAdapter`

---

### `channels` (top-level collection)
Model: `models/Channel.java` — public/semi-public channels

| Field           | Type           | Notes                              |
|-----------------|----------------|------------------------------------|
| id              | String         | Set from Firestore doc ID          |
| name            | String         |                                    |
| description     | String         |                                    |
| createdBy       | String         | UID                                |
| createdByName   | String         |                                    |
| inviteLink      | String         | unknown — requires clarification   |
| members         | List\<String\> | UIDs                               |
| lastMessage     | String         | Preview text                       |
| lastMessageTime | Timestamp      |                                    |
| createdAt       | Timestamp      |                                    |
| membersCount    | int            |                                    |
| isPublic        | boolean        | Default true                       |

Used by: `ChannelsFragment`, `ChannelAdapter`

---

### `channels/{channelId}/messages` (subcollection)
Same model as household messages. Used by: `ChannelChatFragment`, `ChatAdapter`

---

### Expenses collection
Path: unknown — requires clarification (likely `households/{householdId}/expenses`).
Model: `models/Expense.java`

| Field       | Type      | Notes                       |
|-------------|-----------|-----------------------------|
| id          | String    | Set from Firestore doc ID   |
| title       | String    |                             |
| amount      | double    |                             |
| category    | String    |                             |
| addedBy     | String    | UID                         |
| addedByName | String    | Display name                |
| timestamp   | Timestamp | @ServerTimestamp            |

Used by: `ExpensesFragment`, `ExpenseAdapter`

---

## SharedPreferences Stores

### `ZmanimSettings` (device-local)
File: `ZmanimSettingsFragment.java`

| Key           | Type    | Default | Purpose                            |
|---------------|---------|---------|------------------------------------|
| show_sunrise  | boolean | true    | Show sunrise zman                  |
| show_shma_gra | boolean | true    | Show Shma (Gra) zman               |
| show_shma_mga | boolean | false   | Show Shma (MGA) zman               |
| show_tfila_gra| boolean | true    | Show Tefila (Gra) zman             |
| show_sunset   | boolean | true    | Show sunset zman                   |
| alarm_offset  | int     | 0       | Minutes before/after zman to alarm |

### `RambamPrefs` (device-local)
File: `ZmanimSettingsFragment.java`, `RambamPagerAdapter.java`

| Key                     | Type | Default | Purpose                           |
|-------------------------|------|---------|-----------------------------------|
| auto_scroll_speed       | int  | 3       | Speed value 0-9 (displayed as 1-10) |
| text_size_sp            | int  | 20      | Rambam text size in sp (14–28)    |
| scroll_pos_day_{n}      | int  | 0       | Saved scroll position per day page |
