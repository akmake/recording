# Module: Chat

> Last updated: 2026-03-12

## Overview
Two chat surfaces: private household family chat and public/semi-public channels. Both use the same `Message` model stored in Firestore subcollections. Supports text, image, and voice messages with reply threading.

## User Flow

**Family Chat:**
1. Navigate to family chat → `FamilyChatFragment` (or `ChatFragment` — relationship unclear)
2. Loads `households/{householdId}/messages` real-time listener
3. Send text/image/voice; reply to existing messages

**Channels:**
1. Navigate to channels → `ChannelsFragment` lists available `channels` (top-level collection)
2. Tap a channel → `ChannelChatFragment` loads `channels/{channelId}/messages`
3. Create new channel or join via invite link

## Key Files

| File                                                       | Purpose                                              |
|------------------------------------------------------------|------------------------------------------------------|
| `FamilyChatFragment.java`                                  | Family-private chat; `households/{id}/messages`      |
| `ChatFragment.java`                                        | unknown — possibly base class or standalone chat     |
| `ChannelsFragment.java`                                    | Channel list; `channels` top-level collection        |
| `ChannelChatFragment.java`                                 | Single channel chat; `channels/{id}/messages`        |
| `adapters/ChatAdapter.java`                                | Renders message bubbles (text/image/voice)           |
| `adapters/ChannelAdapter.java`                             | Renders channel list items                           |
| `models/Message.java`                                      | Message data model                                   |
| `models/Channel.java`                                      | Channel data model                                   |

## API Routes

No HTTP API. All Firestore.

| Collection path                              | Used by                          |
|----------------------------------------------|----------------------------------|
| `households/{householdId}/messages`          | `FamilyChatFragment`             |
| `channels`                                   | `ChannelsFragment`               |
| `channels/{channelId}/messages`              | `ChannelChatFragment`            |

## Schemas Used

`Message` — see `docs/database.md`. Types: text, image, voice.
`Channel` — see `docs/database.md`.

## Coupling

| If you change...               | Also update...                                                     |
|--------------------------------|--------------------------------------------------------------------|
| `Message` model fields         | `FamilyChatFragment.java`, `ChannelChatFragment.java`, `ChatAdapter.java`, `docs/database.md` |
| `Channel` model fields         | `ChannelsFragment.java`, `ChannelAdapter.java`, `docs/database.md` |
| Media upload (image/voice)     | `FamilyChatFragment.java` / `ChannelChatFragment.java` + `firebase-storage` dependency |

## Known Issues / Pitfalls

- `ChatFragment` vs `FamilyChatFragment` — purpose relationship unknown. May be duplicate code or base class.
- `Channel.inviteLink` field exists but generation/usage logic was not found in scanned code.
- `Message.read` field exists but read-receipt update logic was not verified.
- FCM notifications for messages (`type = "messages"`) require server-side push — FCM token is not saved to Firestore, so push delivery to specific users is broken. See `docs/known-unknowns.md`.
- Firebase Storage is included as a dependency but storage security rules were not reviewed.
