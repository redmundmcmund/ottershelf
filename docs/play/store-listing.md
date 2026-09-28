# Store listing: settings and assets

Play Console: **Grow users > Store presence > Main store listing**, and **Policy > App content**
for the declarations. The listing texts and images live in
`fastlane/metadata/android/en-US/`, the layout that fastlane `supply` (and F-Droid) read.

## App details

| Field | Value | Source |
|---|---|---|
| App name (max 30) | Ottershelf: ebook reader | `fastlane/metadata/android/en-US/title.txt` |
| Short description (max 80) | Read, track and annotate the books on your own BookOrbit server. Open source. | `short_description.txt` |
| Full description (max 4000) | | `full_description.txt` |
| Release notes (max 500 per language) | | `changelogs/<versionCode>.txt` |
| Package name | `io.github.ottershelf` | |
| App or game | App | |
| Free or paid | Free | |

The texts avoid using "BookOrbit" as a name for the app: it appears only to say which server the
app works with and that the app is unofficial, as BookOrbit's additional terms require
(ADDITIONAL_TERMS.md, sections 3 and 4). Keep it that way in every language.

## Category and tags

- **Category:** Books & Reference
- **Tags:** choose up to five from Play's list in **Store settings > Tags**. Candidates, if
  offered: Ebook reader, Books, Reading, Comics reader, Book tracker. Only pick tags that describe
  the app; Play may reject misleading ones.

## Contact details

| Field | Value |
|---|---|
| Email (required, shown publicly) | [contact email] |
| Website | https://github.com/redmundmcmund/ottershelf |
| Phone (optional) | leave empty |
| Privacy policy URL (required) | [privacy policy URL on GitHub Pages, e.g. https://redmundmcmund.github.io/ottershelf/PRIVACY] |

The email address is required by Play; use a project address, not a personal one. The privacy
policy URL must serve PRIVACY.md as a public web page (GitHub Pages from the repository), without
a login.

## Graphic assets

| Asset | Required | Specification (Play) | File in the repository |
|---|---|---|---|
| App icon | Yes | 512 x 512 px PNG, up to 1 MB; Play asks for 32-bit PNG and applies its own mask and shadow, so the artwork must fill the square with no rounded corners or drop shadow | `fastlane/metadata/android/en-US/images/icon.png` (from `docs/brand/play-icon-512.png`; fully opaque, saved as 24-bit RGB) |
| Feature graphic | Yes | 1024 x 500 px, JPEG or 24-bit PNG without alpha, up to 15 MB | `fastlane/metadata/android/en-US/images/featureGraphic.png` |
| Phone screenshots | Yes, 2 to 8 | JPEG or 24-bit PNG without alpha, up to 8 MB each; each side 320 to 3840 px, the long side at most twice the short side. Use **1080 x 1920 px** portrait. At least four screenshots of 1080 px or more are needed for some promotional placements | `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png` ... `6.png` (to be generated) |
| 7-inch and 10-inch tablet screenshots | No | Same formats | not provided |
| Promo video | No | YouTube URL | not provided |

If the Play Console refuses the 24-bit icon, export `docs/brand/play-icon-512.png` as a 32-bit PNG
(it is already fully opaque) instead.

Screenshots should show the app with demo content only: public-domain books, no real names,
reading history or server addresses. Suggested set, matching the README captions:

1. Dashboard
2. Library grid
3. Book page
4. Ebook reader
5. Highlights and notes
6. Statistics

## App content declarations

| Declaration | Answer | Draft |
|---|---|---|
| Privacy policy | URL above | [PRIVACY.md](../../PRIVACY.md) |
| Ads | No, the app contains no ads | |
| App access | Restricted: demo server credentials | [app-access.md](app-access.md) |
| Content rating | IARC questionnaire | [content-rating.md](content-rating.md) |
| Target audience | 18 and over | [content-rating.md](content-rating.md) |
| Data safety | | [data-safety.md](data-safety.md) |
| Advertising ID | No: the app does not use the advertising ID and does not declare the `AD_ID` permission | |
| Foreground service permissions | `dataSync` | [foreground-service.md](foreground-service.md) |
| News app | No | |
| Government app | No | |
| Financial features | None | |
| Health | None | |

## Before each release

- `changelogs/<versionCode>.txt` exists for the new `versionCode` and is at most 500 characters.
- The screenshots still match the app.
- The data safety form and PRIVACY.md still match what the app sends.
