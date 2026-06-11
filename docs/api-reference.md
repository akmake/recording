# API Reference

> Last updated: 2026-03-20

Canonical source for: all external HTTP API calls made by the app.

This app calls two external REST APIs: **Sefaria.org** (text fetching) and the **Shieor study server** (daily study schedule).
All Firebase communication is via the Firebase SDK (not HTTP).

---

## Sefaria API

Base URL: `https://www.sefaria.org/`
Client: Retrofit singleton in `api/RetrofitClient.java`
Interface: `api/SefariaService.java`
Timeouts: 30s connect / read / write

### Endpoints

| Method | Path                          | Parameters               | Response type             | Purpose                                   |
|--------|-------------------------------|--------------------------|---------------------------|-------------------------------------------|
| GET    | `api/texts/{ref}?context=0`   | `ref` = URL-safe text ref | `SefariaResponse`         | Fetch Hebrew text of a Rambam chapter/range |
| GET    | `api/calendars`               | `date` = `yyyy-MM-dd`    | `JsonObject`              | Get today's Jewish calendar items (Daf Yomi, Rambam, etc.) |

### SefariaResponse fields used

| Field   | Type        | Notes                                                              |
|---------|-------------|--------------------------------------------------------------------|
| he      | JsonElement | 1-D or 2-D array of Hebrew halacha strings; may contain HTML tags |
| ref     | String      | Full text ref                                                      |
| title   | String      | English title                                                      |
| heTitle | String      | Hebrew title — displayed in UI                                     |

### Calendar response parsing

`getCalendars()` returns `JsonObject` with `calendar_items` array. Each item has:
- `title.en` / `title.he` — title (nested object, NOT plain string)
- `displayValue.en` / `displayValue.he` — display label
- `url` — URL-safe ref (used as path for `getRambamChapter`)
- `ref` — full ref

Rambam item selection strategy (in `RambamPagerAdapter.ViewHolder.bind()`):
1. Pass 0: find item where `title.en` contains "rambam" AND ("3" or "three") → 3-chapter daily track
2. Pass 1 fallback: find any item where `title.en` contains "rambam"

### Notes

- HTTP body logging is enabled at `BODY` level in dev — disable for production.
- `cleartext traffic` is allowed in manifest — review before release.
- Sefaria `he` field may be a `String[]` (1-D) or `String[][]` (2-D) depending on whether the day covers one or multiple chapters. See `RambamPagerAdapter.extractChapters()`.

---

## Shieor Study Server API

Base URL: `http://10.0.2.2:5000/` (emulator) — change `STUDY_SERVER_URL` in `RetrofitClient.java` for production.
Client: Retrofit singleton in `api/RetrofitClient.getStudyService()`
Interface: `api/StudyService.java`
Timeouts: 30s connect / read / write

### Endpoints

| Method | Path              | Parameters         | Response type | Purpose                                   |
|--------|-------------------|--------------------|---------------|-------------------------------------------|
| GET    | `api/study/day`   | `date` = `yyyy-MM-dd` | `StudyDay`   | Get all daily study items for a given date |

### StudyDay response fields

| Field       | Type                  | Notes                              |
|-------------|-----------------------|------------------------------------|
| date        | String                | Normalized date `yyyy-MM-dd`       |
| hebrewDate  | String                | Hebrew date string                 |
| studies     | Map&lt;String, Study&gt; | Keys: chumash, rambam, rambamOne, tanya, seferHamitzvot, shnayimMikra |

### Study object fields

| Field     | Type    | Notes                                         |
|-----------|---------|-----------------------------------------------|
| key       | String  | Study identifier (e.g. "rambam")              |
| title     | String  | Hebrew display title                          |
| subtitle  | String  | Short description                             |
| accent    | String  | Card accent: "blue", "emerald", "violet", "amber" |
| kind      | String  | Type label shown on card                      |
| label     | String  | Today's specific label (e.g. chapter name)    |
| ref       | String  | Sefaria ref for detail navigation             |
| preview   | String  | First ~180 chars of Hebrew text               |
| available | boolean | false if the study type has no data today     |
