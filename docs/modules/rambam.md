# Module: Daily Rambam Reader

> Last updated: 2026-03-12

## Overview
Displays the daily Rambam (Mishneh Torah) portion in Hebrew, fetched from Sefaria.org. Uses a 334-day yearly cycle with swipeable pages (one per day), auto-scroll, and configurable text size.

## User Flow

1. `HomeFragment` creates `RambamPagerAdapter` → ViewPager2 with 334 pages
2. Opens to today's page (position = days since 2024-07-14 cycle start, mod 334)
3. Each page: fetches `api/calendars?date={date}` → finds Rambam 3-chapter item → fetches `api/texts/{ref}`
4. Hebrew text rendered programmatically with Hebrew ordinal numbers (א, ב, ג…) and Frank Ruhl Libre font
5. User can swipe to adjacent days; scroll position is saved per-day in `RambamPrefs`
6. Auto-scroll button toggles smooth auto-scroll (speed from `RambamPrefs.auto_scroll_speed`)
7. Settings gear icon → `ZmanimSettingsFragment` (also controls Rambam text size + scroll speed)

## Key Files

| File                                              | Purpose                                            |
|---------------------------------------------------|----------------------------------------------------|
| `HomeFragment.java`                               | Host of ViewPager2, Hebrew date header, navigation |
| `adapters/RambamPagerAdapter.java`                | 334-page adapter, Sefaria API calls, text rendering|
| `api/RetrofitClient.java`                         | Retrofit singleton for Sefaria                     |
| `api/SefariaService.java`                         | API interface + response models                    |
| `ZmanimSettingsFragment.java`                     | Controls text size + auto-scroll speed             |
| `res/layout/fragment_home.xml`                    | ViewPager2, date header, auto-scroll button        |
| `res/layout/item_rambam_page.xml`                 | Individual page layout                             |
| `res/font/frank_ruhl_libre.xml`                   | Hebrew font                                        |

## API Routes

See `docs/api-reference.md` — `GET api/calendars` + `GET api/texts/{ref}`.

## Schemas Used

No Firestore. SharedPreferences only — see `docs/database.md → RambamPrefs`.

## Coupling

| If you change...                            | Also update...                                          |
|---------------------------------------------|---------------------------------------------------------|
| Sefaria API endpoints or response format    | `SefariaService.java`, `RambamPagerAdapter.java`, `docs/api-reference.md` |
| `RambamPrefs` SharedPreferences keys        | `ZmanimSettingsFragment.java`, `docs/database.md`       |
| `item_rambam_page.xml` view IDs             | `RambamPagerAdapter.ViewHolder` field bindings          |
| Cycle start date (2024-07-14) or length (334) | `RambamPagerAdapter.java:67,51`                        |

## Known Issues / Pitfalls

- **Hard-coded cycle start date** `2024-07-14` at `RambamPagerAdapter.java:67` — when the next Rambam cycle begins, this date must be updated manually.
- The `he` field from Sefaria can be a 1-D or 2-D array — `extractChapters()` handles both, but if Sefaria changes format it will silently return empty content.
- Scroll position is saved per `"scroll_pos_day_{position}"` key — 334 keys accumulate in SharedPreferences over time.
- HTTP body logging is always enabled — performance impact on slow connections.
- `cleartext traffic` is allowed in manifest — Sefaria is HTTPS but the setting is broader than needed.
