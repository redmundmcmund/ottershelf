# Ottershelf: architecture

Ottershelf is a native Android client for self-hosted BookOrbit servers (`/api/v1`), for phones
running Android 12+ (minSdk 31, targetSdk 36, compileSdk 37). The layouts are designed for a phone
screen of about 411x891 dp. The app has been tested on a Pixel 8 Pro running GrapheneOS and a
Nothing CMF Phone 2 Pro.

It grew out of an earlier BookOrbit client written for the Nexus 7 tablet, called "the Nexus app"
in this guide and in code comments: many screens keep that app's layout, and the data layer is
ported from it with the logic unchanged. The compromises that app made for the tablet's old Android
version are dropped.

This file is the developer guide: how the code is organised, what each part does, and the rules
for changing it.

- One Gradle module: `:app`.
- One activity: `MainActivity`.
- Jetpack Compose with Material 3 (stable 1.4.0) and Navigation 3.
- Manual dependency injection: `AppContainer`. No Hilt, no KSP.

## Package layout (`io.github.ottershelf`)

| Package | What it holds | Owner |
|---|---|---|
| `App.kt` | `OttershelfApp` (the Application and the Coil `ImageLoader`), `AppContainer` (DI), `Context.appContainer` | data layer |
| `MainActivity.kt` | The only activity: splash screen (held until the saved appearance is read), `enableEdgeToEdge()`, `setContent { OttershelfTheme(prefs = container.themePrefs) { AppRoot() } }`, sync on resume | shell |
| `core.model` | `Models.kt`, `RequestModels.kt`: `@Serializable` API types and request bodies. Pure Kotlin. | data layer |
| `core.network` | `Api` (REST client and auth), `ApiException`, `ApiJson` (the one Json config), `Connectivity` | data layer |
| `core.session` | `Session` (server, user, encrypted tokens), `TokenCipher` / `KeystoreTokenCipher`, `AuthState` (signed in or out, current user) | data layer |
| `core.sync` | `ProgressStore` (positions, sessions, conflicts), `ProgressRemote`, `SyncCoordinator`, `SyncScheduler` + `SyncWorker` (WorkManager), `ReadingChanges`, `PageProgress` + `PageReaderProgress` (the page-based readers' positions and sessions, see "Readers"), `ReadingVisit` (a reader opening's sessions: none sent, and no position saved, until the user moves) | data layer |
| `core.format` | `BookFormats` (which reader opens a format, which file a book opens and keeps offline), `ReaderKind` (see "Readers") | data layer |
| `core.readerprefs` | Reader settings on the device and the account: `ReaderSettingsStore`, `ReaderSettingsSpecs` (`CbxReaderSettings`, `PdfReaderSettings`), `ReaderPrefsRemote` / `ApiReaderPrefsRemote` (see "Readers") | data layer |
| `core.download` | `Downloads`, `DownloadWorker`, `DownloadNotifications`, `LocalEpub`, `DownloadedBook`, `DownloadEvent`, `OfflineFiles` / `OfflineCopy` (any format kept offline, see "Readers") | data layer |
| `core.settings` | `settingsDataStore`: the single Preferences DataStore; `AppSettings` (the app's users.settings key `bookorbitAndroid`, versioned), `AppSettingsRepository` (read from `auth/me`, written whole, offline-safe), `JsonMerge` | data layer |
| `core.tracking` | The reading tracker (see "Tracking"): `TrackingRepository`, `TrackingRemote` / `ApiTrackingRemote`, `TrackingModels.kt`, `TrackingQueue`, `TrackingScheduler` + `TrackingWorker`, `PageMath`, `TimerEngine` (+ `ActiveTimer`, `FinishedTimer`, `TimerState`), `TimedParts` (+ `ActiveSpan`: a timed session split by day), `TimerNotifications` / `TimerIntents` / `TimerActionReceiver` / `TimerRestoreReceiver` | data layer |
| `core.theme` | `ThemePrefs` (the six web appearance settings and their ids: `ThemeMode`, `Accent`, `Radius`, `ThemeBackground`), `ThemeRepository` (device copy + server sync), `ThemeRemote` and its request bodies | theme |
| `core.util` | `IsoTime` (java.time) and other pure helpers | data layer |
| `ui.nav` | `Route` / `Chrome` (+ `Route.sourceKey` / `.query`), `AppNavigator`, `AppNavigatorState` / `SearchBarState`, `AppRoot` / `AppScaffold` (drawer + NavDisplay), `AppDrawer.kt` (`AppDrawerSheet`, `DrawerEntry`, `DrawerAction`, `compactCount`), `RootFrame.kt` (`RootFrame`, `RootTopBar`, `TopBarActions`), `NavGraph.kt`, `appViewModel` / `LocalAppContainer`, `ShellViewModel` / `DrawerUiState`, `PendingRoute` (a screen asked for by a notification) | shell |
| `ui.theme` | `OttershelfTheme` (+ `OttershelfTheme.colors` / `.radii` / `.prefs`), `OttershelfColors` and `ottershelfColors()` (the web tokens), `oklch()`, `OttershelfRadii`, `OttershelfTypography` / `OttershelfFonts`, `BackgroundPainter` / `BackgroundPatterns` / `Modifier.backgroundPattern` / `PatternBackground`; `patterns/AppearancePatterns.kt` holds the patterns beyond none and dots | theme (patterns file: appearance work) |
| `ui.components` | Shared composables: `BookCover`, `BookCoverPlaceholder`, `BookGridItem`, `ShelfCover`, badges (`StatusBadge`, `StatusIcon`, `FormatBadge`, `FormatChip`, `SeriesBadge`, `CountPill`), `PillProgressBar`, `CoverProgressBar`, `DashCard`, `SectionHeader`, `CardTitle`, `CardRow`, `EmptyState`, `ErrorState`, `LoadingState`, `SkeletonBox`, `AccentButton`, `SecondaryButton`; and the shell's `DetailScaffold`, `PlaceholderContent`, `rememberNotificationPermissionRequest`; the readers' `ImmersiveSystemBars`, and `KeepScreenOn` / `KeepScreenOnWhileReading` (readers, timer) | theme + shell (additions welcome, see below) |
| `ui.icons` | `Lucide` (the whole catalogue in `assets/lucide.txt`, by PascalCase name), `LucideIcon(name, ...)`, `LUCIDE_BUILT_IN` (generated by `tools/gen-lucide-builtin.sh`), `AppIcons` (ready-made vectors for the shell) | icons |
| `feature.login` | `LoginScreen`, `LoginViewModel` (Nexus LoginActivity: the server field starts blank, no server is built into the app, and shows the stored one after a sign-in; https only, AppContainer.signIn, errors inline; the Ottershelf badge and wordmark on the pattern, in the web login's layout), `ServerAddress` (the typed address tidied: lower-case host, no port 443, no trailing slash; a user, query or fragment refused; the stored spelling kept for the same server, so the account key, and with it the user's downloads and queued progress, never depends on how it was typed; `ServerAddressTest`) | login |
| `feature.home` | `HomeScreen`, `HomeViewModel` (the Dashboard: the Nexus DashboardFragment's freshness rules behind a `DashboardRemote`, tested in `HomeViewModelTest`; reports each load to `AppNavigator.dashboardLoaded`), `DashboardRemote.kt` (`ApiDashboardRemote`, `ShelfPrefs`), `WidgetCards.kt`, `WidgetCharts.kt`, `ShelfCards.kt`, `CustomiseSheet.kt`, `Reorder.kt` (`ReorderState`: drag to reorder in a LazyColumn), `model/` (`DashboardModels.kt`, `DashboardOrder.kt`, `CustomiseDraft.kt`: pure, `DashboardModelsTest`, `DashboardOrderTest`); see "The Dashboard" | home |
| `feature.library` | `BookListScreen` (a source's books: a root list, or an author's/series' books pushed), `AuthorsScreen`, `SeriesScreen` and their ViewModels; `Pager` / `PagedState` (the Nexus paging: 60 per page, pull to refresh, repeated items skipped, `restart()` for a new sort); sorting and filtering (see "Sorting and filtering the book grids"): `LibrarySort.kt` (`SortField`, `ListKind`, `ListSort`, `querySegments`, the query bodies: pure, `LibrarySortTest`), `BookListLoader` + `BookListRemote` / `ApiBookListRemote` (`BookListLoaderTest`), `LibrarySortPrefs`, `LibrarySortSheet.kt` (`SortButton`, `SortChips`, `SortSheet` / `SortSheetContent`), `SortableList`; the views (see "Grid, list and the quick view"): `LibraryView.kt` (`ListView`, `ViewMode`, `GridColumns`, `PinchSteps`, `anchoredTop`, `cellHeightAt`: pure, `LibraryViewTest`), `LibraryViewPrefs` (`LibraryViewPrefsTest`), `LibraryGrid.kt` (`BookGridFrame` + the pinch, `BookRow`, `ViewMenuButton` / `ViewMenuContent`, `GridMetrics`), `QuickView.kt` (`BookQuickViewSheet`, `BookQuickViewContent`) + `QuickViewModel.kt` (`BookQuickViewModel`, `QuickViewUiState`); `BookListEdits` (books edited on this phone, see "Editing a book's details") | library |
| `feature.book` | `BookDetailScreen`, `BookDetailViewModel`, `CoverTint` (the web's cover tint), `BookPreview` (the tapped grid card, so the page shows title and thumbnail at once); the tracking part (see "The book page's tracking"): `BookTracker` (owned by the ViewModel), `BookTracking.kt` (`BookTrackingUiState`, `progressOf`, `timelineOf`), `BookTrackingSection.kt` (progress card, rating and review, reading log), `TrackingDialogs.kt`, `FinishCelebration.kt` | book |
| `feature.reader` | `ReaderScreen` (the WebView in an `AndroidView`, immersive mode, activity_reader.xml's bars in Compose), `ReaderChrome.kt` (`ReaderContent`, the footer, the TOC sheet, the two-positions dialog), `ReaderSettings.kt` (the settings sheet, `SpacingSteps`), `ReaderColorPicker.kt` (the custom page colour dialog), `ReaderStyles` (settings to the page's CSS and layout, `PageColor`: pure, `ReaderStylesTest`), `ReadingTime` (the footer's time left, `ReadingTimeTest`), `ReaderPaceStore` (see "The foliate reader's layout"), `ReaderViewModel` (the Nexus ReaderActivity's open/save/push/session/conflict logic and the `window.Android` bridge; the final save runs from `onCleared` in the app scope), `ReaderRequests` (answers only this book's `epub/<id>/info` and `epub/<id>/file/...` for an EPUB, or `books/files/<fileId>/serve` for the formats read whole: downloaded copy first (`LocalBook`), else the server; anything else 403), `ReaderPrefsStore` (DataStore `reader.prefs`); `annotations/`: highlights, notes, bookmarks and in-book search (see "Reader annotations") | reader |
| `feature.comics` | The comics reader (see "Readers", "The comics reader"): `ComicsScreen` (Comics(bookId, fileId, title): immersive, the settings sheet, the two-positions dialog), `ComicsViewModel` (opening, pages, progress, settings, the next comic), `ComicsChrome.kt` (`ComicsContent`, bars, `NextIssueCard`), `ComicsPages.kt` (the pager of pages or spreads with Telephoto zoom, the vertical strip, tap zones, `PageTurner`), `ComicsSettings.kt`, `ComicPages.kt` (`ComicSource`: server or downloaded CBZ; `LocalCbz` page order; `LocalPageFetcher`; `ComicImages`, the reader's own Coil loader), `SpreadLayout` (the web's spread rules) | comics |
| `feature.pdf` | The PDF reader (Pdf(bookId, fileId, title, page?); see "The PDF reader"): `PdfScreen` (immersive, sheets, password prompt), `PdfViewModel`, `PdfEngine` (platform PdfRenderer on its own thread) + `PageBitmapCache` + `EnginePageSource`, `PdfViewer.kt` (`ContinuousPages`, `PagedPages`, `readerGestures`), `PdfChrome.kt` (`PdfContent`, bars, contents/search/settings sheets, `PdfPasswordDialog`), `PdfFiles` (downloaded copy, or `serve` into the cache), `PdfOutline` (the document outline reader), `PdfModels.kt` (`PdfUiState`, `PdfSettings`, `PdfMath`: pure, `PdfMathTest`, `PdfOutlineTest`) | pdf |
| `feature.downloads` | `DownloadsScreen`, `DownloadsViewModel` (the drawer's Downloaded: the book grid of `Downloads.list()` with local positions, downloads on their way first with WorkManager progress, feature.library's views (grid or list, pinch, the view menu; kept as `library.view.<account>.downloaded`) and its quick view on a long press (Read, Book page, Remove download asked first, status); fully offline) | downloads |
| `feature.requests` | `RequestsScreen`, `RequestsViewModel` (Nexus RequestsActivity: metadata search and request, My requests polled every 5 s while something moves, a detail bottom sheet with the web's pipeline; `RequestPrefill`: opened as RequestBook(isbn, title?, author?) from the scanner, the search already run for that ISBN) | requests |
| `feature.scan` | ISBN barcode scanning (see "Scanning ISBNs"): `ScanScreen` (Scan(forTimer): permission, camera, navigation) + `ScanViewModel` (lookup, chooser, not-in-library, typed ISBN), `ScanContent.kt` (the overlay, reticle, permission panels and the sheets), `BarcodeCamera` (CameraX `ImageAnalysis` + zxing-cpp, EAN-13 only), `Isbn` / `IsbnInput` (pure: barcode and typed parsing, a frame's first ISBN, check digits, 10/13 conversion), `ScanQuery.kt` (pure: the `isbn` rule query, the title query, `bestCandidate`, `sameWork`, `ScanCard`), `ScanRemote` / `ApiScanRemote`, `ScanEntry.kt` (`ScanForTimerAction`, the Currently Reading card's "Scan a book"); `IsbnTest`, `ScanQueryTest`, `ScanViewModelTest` | scan |
| `feature.settings` | `SettingsScreen` (account, Appearance, sign-out via AppContainer.signOut, version), `AppearanceScreen` (the web's Display > Theme page: storage mode, colour scheme, 64 accents, radius, surface opacity, dark brightness, pattern gallery; every change live through ThemeRepository), their ViewModels, `SettingsParts.kt` (the web's `.settings-*` card pieces) | settings |
| `feature.timer` | `TimerScreen` + `TimerViewModel` (Timer(bookId): count up or countdown picked before starting, the time large in a DashCard with the big pause/resume button, Cancel (confirmed) and Save, keep screen on (`KeepScreenOn`), the book strip "p. 130 / 464"; Save pauses and opens `SaveSessionSheet`), `TimerResultScreen` + `TimerResultViewModel` (TimerResult(bookId, sessionId): the saved session's `SessionSummary`, or the stopped timer's save form when opened from the notification), `TimerSessions` (book pages, save = stop + `logTimedSession` + `clearFinished`, remembers an edited page total and the unit), `TimerModels.kt` (`TimerBook`, `SaveForm`, `ProgressInput`, `SessionSummary`: pure, `TimerModelsTest`), `TimerBar.kt` (`RunningTimerViewModel`, the Dashboard's `RunningTimerBar`, the reader's `ReaderTimerNotice`) | timer |
| `feature.calendar` | `CalendarScreen` + `CalendarViewModel` (root, the drawer's Tracking > Calendar: the Book Calendar month grid with covers and the readings' marks, `CalendarCache` per month on the device, `MonthImage` saves the month card to Pictures/Ottershelf, `CalendarToolbar`), `DayScreen` + `DayViewModel` (Day(date)), `ReadingGoalsScreen` + `ReadingGoalsViewModel` (from the calendar's toolbar and Settings), `CalendarModels.kt` (`CalendarMonth`/`CalendarDay`/`CalendarBook`, `CalendarMark`/`MarkKind`/`DayStack`, `CalendarMath`: pure, `CalendarModelsTest`, `CalendarMarksTest`), `CalendarParts.kt` (`GoalRing`, `StatTile`, `durationLabel`); a book's start and end dates (see "Reading dates"): `ReadingDates.kt` (`DatesTarget`, `DatesForm`, `DatesPlan`, `ReadingDates`: pure, `ReadingDatesTest`), `ReadingDatesRemote.kt` (`PickerBook`, `PickerQueries`, `ReadingDatesRemote` / `ApiReadingDatesRemote`), `ReadingDatesViewModel` (+ `readingDatesViewModel()`; `ReadingDatesViewModelTest`), `ReadingDatesSheet.kt` (`ReadingDatesHost`, `BookPickerContent`, `ReadingDatesContent`) | calendar |
| `feature.history` | `HistoryScreen` + `HistoryViewModel` (root, the drawer's Tracking > History, beside the Calendar: every reading of every book with its dates; see "The reading history"), `HistoryRemote` / `ApiHistoryRemote` (the book query and each book's `reading-attempts`), `HistoryLoader` (pages, then readings six at a time, only for changed books), `HistoryCache` (`historyCacheFile`), `ReadingLog` + `SavedReadings` (the same data for the Calendar's and Day's marks, with the readings saved on this device on top), `HistoryModels.kt` (`HistoryCard`, `HistoryEntry`, `HistoryGroup`, `HistoryFilter`, `HistoryMath`: pure, `HistoryMathTest`) | history |
| `feature.stats` | `StatsScreen` + `StatsViewModel` (root, the drawer's Tracking > Statistics: Reading and Library tabs; every chart loads on its own), `StatsRemote` / `ApiStatsRemote` (`user-statistics/*` and `statistics/*` GETs over `Api.send`), `model/StatsModels.kt`, `StatsMath` (pure: buckets, heatmap year, ticks, funnel, period changes (`Change`: a percentage, or "up from 18 s" on a tiny base), the year's finished books from `completion-timeline` (`withFinishedBooks`: the overview's own count can come from sessions); `StatsMathTest`), `ChartPalette` (the dataviz colour rules and validator math; `ChartPaletteTest` runs the validator on every accent), `Charts.kt` (Canvas column, line, scatter, heatmap and ranked-bar charts with a tap/drag readout), `StatsChartCards.kt`, `LibraryStats.kt`, `StatsParts.kt` | stats |
| `feature.achievements` | `AchievementsScreen` + `AchievementsViewModel` (root, the drawer's Tracking > Achievements: `GET achievements` as Nexus sections per category with a ring and earned/total, a two-column grid of badge cards, filter All / Earned / In progress, a bottom sheet per badge with its tiers and context book), `AchievementLogic` (tiers grouped as the web does, kebab icon names to Lucide, filters; `AchievementLogicTest`), `RarityPalette` (validated rarity colours), `Celebration.kt` (`CelebrationViewModel` + `AchievementCelebrationHost`: the unlock toast in the shell), `RewindScreen` + `RewindViewModel` + `RewindMath` (the year in review; `RewindMathTest`), `AchievementsRemote` / `ApiAchievementsRemote`, `model/AchievementModels.kt` | achievements |
| `feature.notes` | Highlights and notes (see "Highlights and notes"): `NotesScreen` + `NotesViewModel` (root, the drawer's Tracking > Notes), `BookHighlightsScreen` + `BookHighlightsViewModel` (BookHighlights(bookId, title)), `BookHighlightsSection` (the book page's card, its own `BookHighlightsSummaryViewModel`), `MemorizeScreen` (+ `MemorizeViewModel`), `QuoteCard.kt` (`QuoteCardDialog`: the share-as-image card, `CoverArt`, `CoverBlur`), `NotesShare` (FileProvider share, MediaStore save), `NotesRemote` / `ApiNotesRemote` + `NotesRepository` + `RandomNotes` + `NoteChanges`, `NotesLogic` (pure; `NotesLogicTest`), `NoteParts.kt` (cards, colour bar, style panel, note editor), `model/NoteModels.kt` | notes |
| `feature.quotes` | Quotes typed or photographed (see "Quotes"): `AddQuoteScreen` (AddQuote(bookId?, title?, bookFixed, scan): the form, `BookPickerSheet`, the camera permission dialogs) + `AddQuoteViewModel` (form, picker, scan steps, save), `ScanContent.kt` (CameraX viewfinder and shutter, reading, the line picker over the photo), `PageReader` (decode upright, Tesseract through Tesseract4Android), `Tessdata` (the bundled English model, copied out for Tesseract), `OcrLayout` + `TessLine` (pure: Tesseract's lines as blocks and lines; `OcrLayoutTest`), `QuoteCleanup` + `OcrLine`/`OcrPage` (pure: hyphenation, line breaks), `QuotePosition` (the placeholder CFI, page labels), `QuotesRemote` / `ApiQuotesRemote` + `CreateQuoteBody`, `QuotePhotos` (kept source photos) + `QuotePhotoDialog` (shows one), `QuoteEntry.kt` (`QuoteButtons` for the book page, `AddQuoteAction` for the Notes toolbar); `QuoteLogicTest` | quotes |
| `feature.bookedit` | Editing a book's title, authors, series, number and cover (see "Editing a book's details"): `BookEditScreen` (BookEdit(bookId), from the book page's pencil) + `BookEditContent` (stateless), `BookEditViewModel` (form, save, unlock, cover changes; `canEditDetails`), `BookEditModels.kt` (`EditForm`, `MetadataChanges` (the PATCH body), `problemsOf`, `EditRules`, `LockGroup`, `EditError`: pure, `BookEditModelsTest`), `Suggestions.kt` (`SuggestionSearch`: debounced, stale answers dropped; `authorRows`, `seriesRows`: pure, `SuggestionsTest`), `BookEditRemote` / `ApiBookEditRemote`, `CoverImage.kt` (`CoverImage` downscale and JPEG, `CoverScale`, `CameraCrop`: `CoverImageTest`), `CoverCamera.kt` (CameraX with a 2:3 guide), `CoverSearch.kt` (the online search sheet), `BookEditDialogs.kt`, `BookEdits.kt` (`BookEditPublisher`, `CoverCache`: `EditedBooksTest`; the lists: `BookListEditsTest`); `BookEditViewModelTest`, `BookEditScreenshotTest` | bookedit |
| `feature.seriesnext` | The next book in a series (see "Next in series"): `SeriesNext` (pure: `SeriesNumber`, `pick`, the reader's end fractions; `SeriesNextTest`), `SeriesNextLoader` + `SeriesNextRemote` / `ApiSeriesNextRemote` (`SeriesNextState`, `NextBook`; `SeriesNextLoaderTest`), `SeriesNextViewModel` (+ `rememberSeriesNext` for the book page, `rememberSeriesNextAfterSession` for the timer's result), `SeriesNextCards.kt` (`NextInSeriesCard`, `NextInSeriesRow`, the reader's `EndOfBookCard` / `EndOfBookCardContent`) | series next |

Assets (`app/src/main/assets`) come from the Nexus app. `foliate/` is vendored foliate-js with the
Nexus patches (all kept: the bug fixes and the performance ones), plus `foliate/vendor/`
(`fflate.js` for MOBI/KF8, `zip.js` for zipped formats: unmodified from BookOrbit's
`client/public/assets/foliate`); `reader/` is the reader page and bridge, and `lucide.txt` holds
1838 icons. `reader/` is adapted for the phone: the Chromium 100 polyfills are gone; it applies
what the app computes from the reading settings (`ReaderStyles.pageSettings`: the chapters' CSS,
the page colours, the paginator's attributes, the fixed-layout spreads; see "The foliate reader's
layout"); it reads the formats other than EPUB whole (see "Readers"); and it pushes the full
location to the app shortly after every settle and on `visibilitychange`/`pagehide`, so closing
the reader never waits for the WebView. `tessdata/eng.traineddata` is Tesseract's English model
(tessdata_fast, unmodified; its commit and hash are in THIRD_PARTY_NOTICES.md), for the quotes'
page reading (see "Quotes").
`MainActivity` handles rotation itself (`configChanges`), so the reader keeps its WebView.

## Data layer

The data layer is ported from the Nexus app's `data/` package and `BookOrbitApp.kt`. The logic and
the KDoc explanations are kept. Only these things changed:

**`Api`**
- Auth is unchanged: bearer token, refresh 30 s before expiry, a synchronized refresh with
  rotation, and an authenticator for 401s.
- The token goes only to the server itself: `isOwnServer` compares the parsed scheme, host, port
  and base path (a string prefix let `https://server@evil.example/` covers through). A refresh
  failure other than the server's own rejection (below) is transient (only an IOException leaves an
  OkHttp thread), and a refresh that was in flight during sign-out can't store its tokens again
  (`Session.credentialsGeneration`).
- A refresh has 15 s in all, DNS included (it holds the lock every request waits on). One that
  fails for a passing reason fails the request with an IOException, never an `ApiException` with
  the refresh's status, rather than sending it without a token; the old token still serves for the
  seconds it has left. After an answer (a 5xx, 403 or 429, a proxy's page) or a failure that took
  5 s or more, no refresh is tried for 30 s and such requests fail at once. A failure that came
  back at once (no network yet) is tried again by the next request. The pause is timed with
  `System.nanoTime`, so setting the phone's clock back can't stretch it.
- Only a 401 or 400 from `auth/refresh` signs out: those are the server's own rejections, while a
  403 can only come from something in front of it and counts as passing. The rejection clears only
  the tokens it was sent with (`Session.clearCredentials(ifGeneration)`), so a refresh of the old
  session that fails after the user signed out and in again doesn't sign out the new one.
- Sign-out forgets the tokens on the device first, then revokes the session on the server in the
  background (`Api.revoke`, 10 s limit).
- Login, refresh and logout never follow a redirect (`authClient`): OkHttp would post a 307/308's
  body, the password or the refresh token, again to wherever it points. A redirect is an error.
- What it reads into memory is bounded, so a broken server can't exhaust it: JSON answers and the
  reader's `epub/<id>/info` to 16 MB, a cover or thumbnail kept with a download to 32 MB (both
  refused beyond that, with or without a Content-Length), an error's body to its first 64 KB, and a
  metadata search event to 1 MB (a longer one is skipped; the other candidates still come). Book
  files and the reader's resources stream, and aren't limited.
- The login device label is injected (`AppContainer.deviceLabel()`); `Api` no longer reads
  `android.os.Build`.
- The Nexus first-connection-sharing interceptor is removed.
- `Api` implements `ProgressRemote` and `ThemeRemote`.
- `proxy()` forwards only the reader's `/api/v1/epub/` routes. It checks the normalised URL, so
  `..` and `%2e%2e` can't get around it. Any other GET a page script asks for is refused, so the
  WebView can't use the token to read the rest of the API.

**`Session`**
- Stored in SharedPreferences as before.
- The access and refresh tokens are encrypted with an AES-256/GCM key in the Android Keystore
  (`KeystoreTokenCipher`). The key needs no user authentication, because background work runs with
  the screen locked.
- Each token is decrypted the first time it is read, never on the main thread just to know whether
  the user is signed in, and then cached in memory. Each decrypt is a Keystore round trip, so until the
  refresh token has been read, `isSignedIn` on the main thread only checks that one is stored (what
  `AuthState` sees in `Application.onCreate`); `AppContainer.start` reads it on an IO thread
  straight away. Any other thread (the workers, `stillSignedInAs`) decrypts before it answers.
  `Api.currentAccessToken` checks the expiry first, so an expired access token (the usual one at
  app start: they last 15 minutes) is never decrypted.
- `store()` still uses `commit()`, so a rotated refresh token is never lost.
- The server address is written only by a sign-in that worked, in the same commit as its tokens
  (`store(creds, server = ...)`; `serverUrl` has no setter, and the Nexus app saved it before
  trying). A failed attempt at another server therefore can't pair that server with the tokens
  already stored, which would send them there once they could be read again.
- If a token can't be decrypted, the app counts as signed out: `AppContainer.start`'s read signs it
  out (Login) a moment after start, and the tokens stay stored, in case the failure passes. For
  that moment the account's starters run as signed in, so the sign-out is a real one for them: a
  running timer is paused. The timer can't wait for the read, because `TimerEngine.start` has to
  begin loading its account at once, so that a Pause or Stop from the timer's notification, in a
  process just started for it, finds the timer.
- A key that can't encrypt is replaced once per `store()` (`TokenCipher.replaceKey`), so a
  Keystore copy broken for good doesn't fail every sign-in until the app's data is cleared. Both
  tokens are being replaced then, so nothing is lost. A failed decrypt leaves the key alone: it may
  pass. A sign-in whose tokens still can't be stored is revoked on the server straight away.
- Dates use java.time (`IsoTime`).

**`AuthState`** is new and replaces the Nexus `signedOut` flag and `app.user`:
- `status` is `SignedOut` or `SignedIn(accountKey)`. The shell shows Login while signed out.
- `user` is the cached `AuthUser`, which carries the permissions.
- Only `AppContainer.signIn` and `signOut` change it, plus `Api` when the server rejects the refresh
  token.
- `signIn` refuses accounts that still have their default password: it signs that session out
  again and throws `DefaultPasswordException`.

**`ProgressStore`**
- Persistence (SharedPreferences, per account) and every conflict rule (baseline, pending,
  attempted, conflict, `decideOnOpen`, `push`, `reconcile`, `syncAll`, stripping session ends) are
  unchanged.
- It now depends on `accountKey: () -> String` and `ProgressRemote` rather than on `Session` and
  `Api`, so it can be unit-tested (`ProgressStoreTest`).
- New `onQueued` callback, which schedules the WorkManager flush: only when nobody sends what
  waits (see "Sync").
- New `hasUnsent()`, which the worker uses to decide whether to retry.
- It must stay a process-wide singleton (`AppContainer.progress`). Readers and the sync worker
  share its open-file counts and its mutex.

**Sync** (`SyncCoordinator`, `SyncScheduler`, `SyncWorker`)
- `SyncCoordinator.sync(force, baselines)` is the Nexus `OttershelfApp.sync()` with the same
  coalescing, but sending what waits and refreshing the baselines (a GET per downloaded book: the
  server has no bulk route) are kept apart. It can be called from any thread; the work runs on the
  main thread.
  - The baselines are fetched at most every `SyncCoordinator.BASELINE_INTERVAL_MS` (30 s, the
    Nexus limit), forced or not, counted from the last fetch in this process (`lastBaselines`, in
    memory, so a new process fetches on its first resume). Sign-in always fetches, and sign-out
    forgets the time. The readers' quick syncs neither fetch nor count, and neither does a pass cut
    short (`syncAll` returns false: the connection went, a session couldn't go), so the reconnect
    or resume after it fetches.
  - `force` only means "run now for what's queued". A call that isn't forced, with the baselines
    not due, runs a pass without them only when `hasUnsent()` (read on IO: it decodes every record),
    so a resume with nothing waiting asks the server nothing.
  - The baselines matter for a downloaded book later opened offline (an online open asks the
    server itself), and they are the Downloaded screen's progress bars (`ProgressStore.percentage`,
    online too). The periodic job and a finished download refresh them as well. Within the
    interval, after reading elsewhere, such a book opens offline at the older position (the
    two-positions prompt settles it once the user is back online) and its bar shows the older one.
  - It runs:
    - on `MainActivity.onResume` (unlocking with the reader on top, or coming back from the camera,
      a picker or the share sheet, is a resume too),
    - when a reading session ends (the reader calls `sync(force = true, baselines = false)`),
    - 2 s after the connection comes back (flickers within the 2 s count once): `sync(force = true)`
      while an activity is started (`inForeground`, counted by `AppContainer` between onStart and
      onStop), so what waits goes at once but a flapping network doesn't refetch every download;
      otherwise (a process kept alive by a worker, say) a sync without baselines, only when
      something waits to be sent (`hasUnsent()`), so each wake from Doze doesn't make requests.
- WorkManager adds background sync, so progress queued offline is sent even after the app is
  killed:
  - The unique one-time work `progress-sync-flush` (KEEP policy, a CONNECTED constraint, a 30 s
    initial delay and exponential backoff; it retries while `hasUnsent()`) is the safety net for
    what couldn't be sent in process, as the tracker's flush is. `ProgressStore.onQueued` enqueues
    it:
    - from `savePending` / `enqueueSession` only when the reader is offline (`sendingNow = false`;
      online, the reader sends the position at once and the session's sync runs straight away),
    - after a `push` that FAILED or was REJECTED with something still unsent (a newer save behind
      a failed send included; after SENT, or NOTHING because a sync sent it, the reader's own
      `pushAgain` loop sends a save made meanwhile),
    - after a `syncAll` that stopped early or left something to retry (a network failure, a server
      error on a file, a refusal that keeps it), with something still unsent,
    - and `SyncCoordinator.sync` enqueues it (check and enqueue on IO) when it finds itself
      offline with something waiting (the connection gone since the caller looked), or when a pass
      asked for meanwhile can't run. The readers read the connection once per save, for both
      `sendingNow` and whether to push, so a connection lost in between still gets the flush, from
      the save or from the failed push.

    Never once the sign-in the send started under has ended (`stillSignedInAs`): sign-out cancelled
    the jobs. Reading costs no WorkManager enqueue, job or worker run per page or per screen-off.
    The trade-off: if the process dies during the ~1 s send, delivery waits for the next app open
    (the resume sync sends anything unsent) or the 12 h periodic job, instead of ~30 s. The conflict
    check keeps a late send safe.
  - `progress-sync-periodic` runs every 12 h while signed in, when connected and not on a low
    battery. It is a full sync that also refreshes the baselines of downloaded books. Every process
    start while signed in schedules it with KEEP (`SyncCoordinator.start`, on an IO thread: an
    enqueue and a SharedPreferences read have no place on `Application.onCreate`'s main thread),
    so the job and its timing are left alone; only a job from an older spec
    (`SyncScheduler.PERIODIC_SPEC`, remembered in the `sync` SharedPreferences; the 3 h job of
    0.1.7 and before) is replaced (UPDATE), once. UPDATE at every start would cancel and re-register
    the job each time, the run that started the process included. Raise `PERIODIC_SPEC` whenever
    the request changes.
  - Both call `SyncCoordinator.syncNow()`. They go through the same `ProgressStore` mutex as the
    in-process sync.
- Sign-out (and the server rejecting the refresh token) cancels both jobs and the in-process sync
  under way (`SyncCoordinator.onSignedOut`), request in flight included. The queue stays for that
  account's next sign-in. A pass asks `AppContainer.stillSignedInAs` (the credentials' generation
  and the account key, unchanged since the pass began) before each queued session, so a pass that
  outlives its sign-in (a worker's, say) stops at its next send. See "Security and privacy" for the
  one request that can still slip through.

**`Connectivity`**
- Follows `registerDefaultNetworkCallback`. The device counts as online only when the default
  network has INTERNET and VALIDATED.
- `AppContainer.online` exposes it.

**`ReadingChanges`** holds the Nexus `readingChanges`, `statusOverrides` and `dashboardClearedFor`:
- `statusOverrides` is now a `StateFlow`, so Compose grids recompose.
- `editedBooks` (`bookEdited(book)`): books whose details were edited on this device, as the server
  had them after the change, each with its place in the order of edits (`EditedBook.seq`);
  `BookCard.edited(map)` / `withDetails(book)` and `CurrentlyReadingBook.withDetails(book)`
  (`core/sync/EditedBooks.kt`) show a card loaded before the edit as edited. Only those: a screen
  applies `newEdits()` to what it shows, and to an answer asked for before an edit
  (`editMark` taken before asking, then `editedSince(mark)`); anything asked for after it is the
  server's word. (`updatedAt` can't decide it: the server doesn't move it for a change of authors
  alone.) See "Editing a book's details".
- Everything in it is thread-safe.
- It is reset on sign-out.

**`Downloads`**
- The storage layout is the Nexus one (`files/downloads/<account>/<bookId>/`, with `meta.json` written
  last), generalised to every keepable format (`book.<format>`; see "Readers"). The checks are too: EPUB validation, info/size match, free space, and swapping in the new
  copy with the old one set aside.
- Each download is now a `DownloadWorker`:
  - unique work per account and book, with a CONNECTED constraint;
  - promoted with `setForeground(dataSync)`, with a progress notification and a Cancel action;
  - a dropped connection, or the server briefly unable to answer (408, 429, 502-504), is retried
    twice with WorkManager's back-off (30 s, then 60 s; `DownloadWorker.isTransient`). A refusal, a
    500 (the server's answer for an EPUB it can't parse) or a file that arrived whole but can't be
    kept (an EPUB, CBZ or KEPUB Android's zip reader refuses: `Downloads.openDownloadedEpub`,
    `OfflineFiles.problem`) fails at once; such a zip whose length wasn't known is retried, since
    its end may be missing.
- `active` is derived from WorkManager's `WorkInfo`: progress, and `waiting` while the work is
  queued.
- Results come out as `events: SharedFlow<DownloadEvent>` instead of Toasts. The shell shows them as
  snackbars while visible and confirms each with `shown(event)`. When nothing is collecting, the
  data layer posts a notification instead, and so it does for any result not confirmed by the time
  the shell stops collecting.
- After a completed download, the baseline sync for that book runs in the app scope (as on the
  Nexus), so the work ends as soon as the copy is in place.
- The request (book page, file, target folder) is staged in `files/download-requests/`. Its
  presence also means "still wanted": `delete()` removes it (under the same lock as a download's
  final swap), so a download finishing just as its book is removed can't bring the copy back.
- `saveDetail` (the kept book page, saved by the book page, the quick view and an edit, sometimes
  at once) writes under that lock too: two saves can't garble `detail.json` through one temp file,
  and one can't land in a folder being removed.
- `cleanUpLeftovers()` skips the folders of downloads that are still queued or running. It restores
  an old copy if the process was killed mid-swap (the Nexus cleanup would have deleted it).

**`LocalEpub`** is unchanged, apart from a pure `percentDecode` in place of
`android.net.Uri.decode`. It now runs on a plain JVM.

**Coil 3** (`OttershelfApp.newImageLoader`):
- `coil-network-okhttp` over a `newBuilder()` of `api.client` (`coverFetcher`), so cover requests
  carry the bearer token and share its connections, but with an OkHttp `Dispatcher` of their own
  (4 requests per host): a screenful of covers never queues ahead of the API calls (a grid's next
  page, opening a book), which keep the Api client's 5 per host to themselves. The same cover asked
  for twice at once (a book on two shelves) is downloaded once (`DeDupeConcurrentRequestStrategy`).
- A 128 MB disk cache in `cacheDir/covers` (thumbnails: the book page takes its cover from the thumbnail too, so a few thousand fit).
- Coil 3's default `CacheStrategy` ignores cache headers. This is the old
  `respectCacheHeaders(false)`: thumbnail URLs are versioned.
- Everything else is at defaults: ARGB_8888, hardware bitmaps, a memory cache of 20% of the app's
  memory class, crossfade on.
- Its caches, like the comics reader's (`ComicImages`, `cacheDir/comics`), are keyed by URL alone,
  so sign-out clears both loaders' memory and disk caches, and deletes the PDF reader's served
  copies (`cacheDir/pdf`): the next account on the phone sees only what it fetches itself.
- The book page draws the grid's thumbnail (400x600 at most, usually cached already) and fetches
  the full cover (`books/:id/cover`: the original file, often megabytes, which would push dozens of
  thumbnails out of the cache) only when the thumbnail is missing, fails, or is under 0.8x the
  height of its 160x240 dp box in pixels (`feature.book.DetailCover`, decided when the thumbnail
  loads; `DetailCoverTest`). A 2:3 thumbnail (600 px tall) is enough up to 3.125x. So at 2.625x only a
  cover wider than 0.79 of its height fetches the original (a comic's square one), at 3x one wider
  than 0.69, and above 3.125x every cover. The
  finish celebration draws the same one.

**`ThemeRepository`** (`core.theme`, `AppContainer.theme`; `AppContainer.themePrefs` is its
`StateFlow<ThemePrefs>`) keeps the six web appearance settings exactly as the web client does
(`useThemeSync.ts`, `AppearancePreferenceStorage.vue`):
- **Device copy:** the settings DataStore (`theme.*` keys). Defaults and fallbacks are the web's:
  system, blue, default radius, `vinyl` when nothing is stored but `dots` for an unknown stored id,
  brightness 35, surface opacity 92. MainActivity keeps the splash up until it is read (`loaded`).
- **Local mode** (`AuthUser.settings.syncThemePreferences` missing or false): nothing is sent.
- **Sync mode:** once per signed-in account and process (the first activity's creation while
  signed in, sign-in, or the flag turning on), `GET user-preferences/theme`; a process started for
  a worker, an alarm or boot never starts the theme at all. Every valid field of a non-null answer
  replaces the device value, field by field, and is never sent back. `update { }` saves on the
  device at once and sends the whole object 1.5 s after the last change
  (`PUT user-preferences/theme`, always with `surfaceOpacity`). A waiting save is sent when the app
  leaves the screen (the web's `pagehide`) and dropped on sign-out. A failed save is not retried
  (as on the web); it is reported on `events` (`ThemeSyncEvent.SaveFailed`, string
  `components_theme_save_failed`) for the shell to show.
- **Switching modes:** `setStorageMode(sync)` sends `PATCH users/me/theme-storage-mode`, updates
  the stored user and, when turning sync on, applies the account's preferences or seeds an empty
  account with the device's. It throws when the server refuses (demo accounts) or is unreachable.
- `AuthUser` now carries `settings.syncThemePreferences` (`UserSettings`); `Api` implements
  `ThemeRemote` (the three calls), so `ThemeRepositoryTest` runs against a fake.

### Rules for the data layer
- Request bodies must match the server DTOs exactly: the server rejects unknown fields. Check
  `server/src/modules/**/dto` in the BookOrbit source before adding a field.
- Everything the device stores goes through `ApiJson`.
- Work that must finish after a screen closes (a final progress save, sign-out) runs in
  `AppContainer.appScope`, not `viewModelScope`.
- The data layer never shows UI. It exposes `StateFlow`s and `SharedFlow`s; screens decide how to
  show them.

## Tracking

Bookmory-style reading tracking on top of BookOrbit's own data. Decisions: paper reading is
logged against the library's copy of the book; the server's midnight day split (in
`users.settings.timezone`) is kept; app-only data lives in one users.settings key; no Bookmory
import; highlights, notes and quotes are their own features (see "Highlights and notes" and
"Quotes"). Everything is in `AppContainer`:

| Object | What it is |
|---|---|
| `container.tracking` | `TrackingRepository`: sessions, status, readings, rating, review, statistics, achievements |
| `container.appSettings` | `AppSettingsRepository`: the app's own key (`AppSettings`) |
| `container.timer` | `TimerEngine`: the reading timer and its notification |
| `core.tracking.PageMath` | pages, Day N, reading number, pace, time left (pure) |

**App settings (`core.settings.AppSettings`, users.settings `bookorbitAndroid`).** Edition page
totals (`pageTotals`), hand-set current pages (`pageOverrides`, with the time they were set),
progress units, the daily goal in minutes, kept quote photos (`quotePhotos`), timer defaults (`TimerPrefs`: mode, countdown minutes,
keep screen on), the Dashboard's order of cards (`dashboardOrder`, see "The Dashboard"). Maps are keyed by book id. `v` is the schema version: adding a field with a
default needs nothing; reinterpreting one bumps `VERSION` and adds a step to `migrate`. Keys a
newer app wrote are kept when this one writes. Features may add small fields (a shared-file edit).
- Read: `appSettings.settings` (StateFlow). It starts from the device copy (DataStore
  `tracking.settings.<account>`), else the cached `AuthUser.settings.bookorbitAndroid`; any fresher
  user fetched from `auth/me` replaces it while nothing is waiting to be sent. `AppSettings.decode`
  is lenient part by part: a map entry it can't read (a newer app's progress unit) is left out, a
  field it can't read takes its default, and the rest is read (`AppSettingsModelTest`). It takes a
  bad part apart no deeper than the model goes (three levels), so a deeply nested value is dropped
  whole.
- Write: `appSettings.update { it.copy(dailyGoalMinutes = 30) }`. Only what the change changed is
  written onto the stored key, down to single map entries (`AppSettings.encodeChange`), so a part
  this app couldn't read stays as it was instead of going back to its default on every device.
  Saved on the device at once,
  sent 1.5 s after the last change: `GET auth/me`, a three-way merge (`JsonMerge`: what this
  device last saw, what it wants, what the server has; both-sides conflicts go to the last writer),
  then `PATCH users/me/settings {settings: {bookorbitAndroid: <whole key>}}`. The server merges
  settings shallowly (jsonb `||`), so no other top-level key is touched. Offline, the change waits
  on the device and `TrackingWorker` retries when connected; a waiting save also starts when the
  app leaves the screen. Past 32 KB the oldest hand-set pages are dropped.
- The yearly goal is the server's (`dashboardConfig.readingGoal`, books per year):
  `tracking.setYearlyGoal(n)` rewrites the whole dashboardConfig with only that changed;
  `tracking.yearlyGoal()` reads it from the cached user.

**`TrackingRepository`** (every body checked against the server DTOs):
- Reads (suspend, throw the Api's exceptions): `sessions(bookId, SessionQuery)` (items + stats:
  `latestEndProgress`, `paceProgressDelta`/`paceDurationSeconds`, daily summaries),
  `attempts(bookId)`, `activityOverview()` (snapshot/streaks, goal; the full JSON in `raw`),
  `activityCalendar(year)` (a 400 for a year before the first session is an empty year),
  `activityDay(LocalDate)`, `sessionTimeline(year, isoWeek)`, `claimCelebration()` /
  `acknowledgeCelebration(claimId)` (one that failed to send is kept in memory and sent before the
  next claim; each claim is announced on `claimed`; a claim a closing screen couldn't show goes
  to `handOverClaim` / `unshownClaims` for the shell).
- Sessions are queued (DataStore `tracking.queue.<account>`, written before each send) and return
  `SessionSaveResult` (Sent, Queued, TooShort, Rejected):
  - `logTimedSession(bookId, fileId, sessionId, startedAtMs, endedAtMs, activeSeconds,
    startProgress?, endProgress?, spans, stored)`: `POST books/files/:fileId/sessions` with the
    timer's UUID, `source: "android"`, active `durationSeconds`, `progressDelta` from start/end
    progress. Retry-safe, so it is resent until a 2xx; it also drives the server's automatic status
    and achievements. Under 10 active seconds nothing is kept (the server would drop it). A book
    with no file (`fileId` null) goes through the manual route with the rounded minutes, queued
    under the timer's id (never twice). `spans` (the timer's active stretches): a pause across
    midnight in the account's timezone splits it into one session per day (`TimedParts`: the last
    part keeps the id, earlier ones get ids derived from it; the end position goes with the last,
    the progress gained is shared by active time), because the server spreads a session's time
    evenly over its wall-clock span. `stored` runs once it is on the device, before the send.
  - `logManualSession(bookId, startedAtMs, minutes, endProgress?, format?, startReadingOn?)`:
    `POST books/:bookId/sessions`, whole minutes 1..1440, not in the future. Not retry-safe: it is
    marked unconfirmed before sending, and an unconfirmed one is looked for in the book's sessions
    (source manual, same start within 1 s, same duration) before it is sent again. It doesn't change
    the status by itself; `startReadingOn` queues that with it: once the session is in, a book still
    unread or want to read (read again from `GET books/:id`) is set to reading from that day.
  - A 4xx other than 401/408/429 drops the session (`events: TrackingEvent.SessionRejected` for
    queued ones), and so does a 404/403 when an unconfirmed manual session is looked for (the book
    is gone). A 401 (no valid token: signed out, an expired refresh token) keeps it for that
    account's next sign-in, as `ProgressStore` does; a pass also stops as soon as the account signs
    out. Offline or 5xx waits for `TrackingWorker` (unique work `tracking-flush`, CONNECTED,
    backoff). `pending` counts what waits.
- Direct writes (throw when they can't): `deleteSession(bookId, sessionId)`,
  `moveSession(bookId, sessionId, startMs, endMs)` (same duration; 409 on overlap; editing
  duration or progress means delete and log again), `setStatus(bookId, status?, startedAt,
  finishedAt)` with `Patch.Keep/Clear/Set(LocalDate)` (dates may not be in the future; also
  updates `ReadingChanges.statusOverrides`), `createPastRead`, `updateAttempt`, `deleteAttempt`,
  `startReread(bookId, resetProgress)`, `setRating(bookId, 1..5 or null)` via
  `POST books/bulk-set-rating` (only the user's own rating; offer it only when `canRate`:
  library_edit_metadata and not demo), `setPersonalNote(bookId, text)` (the private review).
- After every write: `ReadingChanges.changed()` (the Dashboard refreshes), `version` goes up
  (calendars, stats) and `bookVersion(bookId)` goes up: a book page collects it and reloads. A
  change made through another route (the book page's status picker still calls `Api.setStatus`)
  announces itself the same way with `changedElsewhere(bookId)`.
- `events` (`TrackingEvent.SessionRejected`): the shell shows each as a snackbar while the app is
  on screen; one refused while nothing collects (background flush) is not announced.
- `BookDetail` now carries `rating` and `personalNote`; `ReadStatusInfo` carries `startedAt` /
  `finishedAt` (read them with `PageMath.dateOf`, never shifted to local time).

**`PageMath`.** `pageTotal(book, settings)` (the total of the user's edition, else `pageCount`),
`percentToPage` / `pageToPercent` (round-trip exact), `currentPercent(latestEndProgress,
fileProgress)` (the higher, as the web shows it), `startPercent(positions, readingSinceMs)` (where a
timed session starts: the most recent position, not the higher one; a reread without a position of
its own starts at 0), `currentPage(total, percent, override, lastSessionEndMs)` (a hand-set
page wins until a later session), `dayNumber(readStatus.startedAt, today)`,
`readingNumber(attempts, reading)` (completed readings + the open one), `pace(stats, total)`
(percent and pages per hour), `timeLeftSeconds`, `pagesOf(progressDelta, total)`,
`trackingFile(files)` (primary, else first: the file a timed session is recorded against).

**The timer (`TimerEngine`, `container.timer`).**
- One timer per account: `start(bookId, fileId, title, mode, targetMinutes?, startProgress?)`
  returns Started, AlreadyRunning (same book) or Busy (another book). `pause()`, `resume()`,
  `toggle()`, `stop(atMs?, announce)`, `discard()`, `fillIn(...)` (a timer started before its
  book loaded learns its file, title and start position), `refreshNotifications()`. All are
  suspend and finish their write even if the caller is cancelled; call them from
  `container.appScope` or a ViewModel. The active stretches are kept (`spans`) for the split by day.
- `state: StateFlow<TimerState>` holds `active: ActiveTimer?` (wall-clock start, accumulated
  active ms, `runningSinceMs` null while paused, count-up or countdown with `targetMs`) and
  `finished: FinishedTimer?`. Show `active.activeMs(now)` / `remainingMs(now)` with
  `timer.ticks()` (every second). Paused time never counts; a timer stopped while paused ends at
  the pause.
- Persisted in the settings DataStore (`tracking.timer.<account>`): it survives the app being
  killed, and the notifications (the ongoing one, or a waiting "save your session") are posted
  again at app start, sign-in, and after a reboot or an app update (`TimerRestoreReceiver`,
  BOOT_COMPLETED / MY_PACKAGE_REPLACED, just starts the app). Signing out (or the server ending
  the session) pauses a running timer there and hides it; signing in as the same account brings it
  back, paused.
- `stop()` makes the `FinishedTimer` the result screen saves: `tracking.logTimedSession(
  bookId, fileId, sessionId, startedAtMs, endedAtMs, activeSeconds, startProgress, endProgress,
  spans, stored = { timer.clearFinished(sessionId) })`, so it is forgotten once stored and before
  the send; `TimerSessions` also refuses a second save of the same session id in the process. An
  in-app save stops with `announce = false` (no "save your session" notification). `tooShort`
  (under 10 s) means there is nothing to save. If a newer timer stops first, the older result is
  saved without a page, so no time is lost. A timer without a `fileId` (started before its book
  loaded) has its file looked up at save time (`tracking.trackingFileId`).
- Notifications (`TimerNotifications`, channels `timer` (silent) and `timer_alerts`): an ongoing
  one with the system chronometer (counting up, or down to the target) and Pause/Resume and Stop
  buttons (`TimerActionReceiver`, no foreground service: the time is wall-clock arithmetic),
  private: where the user's lock screen hides sensitive content, its public version shows "Reading timer"
  with the clock and the buttons, not the book (`TimerNotificationsTest`);
  "save your session" after Stop; "time's up" when a countdown reaches its target (an inexact
  `setAndAllowWhileIdle` alarm, no exact-alarm permission). Tapping opens Timer(bookId) or
  TimerResult(bookId, sessionId): MainActivity turns the intent (`TimerIntents` extras) into a
  `ui.nav.PendingRoute`, which the shell navigates to. A result whose own Timer(bookId) is the top
  screen replaces it, so Done goes back to where the user was rather than to an empty timer.
- **POST_NOTIFICATIONS:** the UI asks when a timer starts, with the existing
  `val ask = rememberNotificationPermissionRequest { granted -> ... }` and `ask()` in the Start
  button's onClick; once granted, `refreshNotifications()` posts the running timer's notification.
  Without it the timer still works; only the notification is missing. "Time's up" is cancelled with
  the timer (stop, save, discard, sign-out).

**The book page's tracking (`feature.book`).** Under the Read/status/download buttons, a DashCard like
Bookmory's current-book card: "Day N" (reading, rereading or on hold), "First reading" / "Reread #N"
(`PageMath.readingNumber`), "p. 130 / 464" (tap the total: the user's edition, `pageTotals`; the pencil: a
`pageOverrides` page, never file progress, which would wipe the ebook position), the bar, pages/h (only with a page total; never %/h) and time
left, both hidden until `trustedPace` has 30 min of reading across 3 sessions, Start timer (Timer(bookId)), Log a session (manual session; a book not started yet is then set to
reading, queued with the session when offline) and Mark as finished. Below the description and files:
rating (only with `canRate`, disabled with a note when the book's `rating` field is metadata-locked;
tapping the current star clears it) and the private review, edited in place; then the reading log
(`timelineOf`: attempts' start/end and sessions by day, newest first, 25 sessions a page; readings older
than the oldest loaded session wait for it; a page that repeats a session reloads the top pages), where
tapping or long-pressing a session moves it (same duration) or deletes it (confirmed), and "Add a past
read" creates an attempt. Day N, the log's days and times and the dialogs' dates and times are in the
account's timezone (`BookTrackingUiState.zone`), like the Calendar and Day screens. The dialogs scroll and
are saved across recreation and process death (`TrackingDialogSaver`: sessions by id). Mark as finished sends
`status read` with `finishedAt` = today in the account's timezone (the server rejects "future" dates by
its own clock), then a full-screen Dialog (`FinishCelebrationContent`): confetti, cover, message; then
rating + review; then each `claimCelebration()` achievement, acknowledged when dismissed. Every write
bumps `bookVersion`, which reloads the tracker and the book (status, rating, note). Beyond that the
page asks again (the book, its position, sessions and readings: four requests) only when something
this phone sent moves `ReadingChanges` (a second later while it shows, so the reader's last push,
which lands after the user is back on the page, is caught; else when it shows again), when the app was
stopped while it showed, or once it is five minutes old. Back from the author's books, the series,
highlights or edit with nothing sent, nothing is asked. A move that comes after a refresh started
asks again (its answers may be from before it), except the one a tracking write brings with its own
reload (`PageFreshness`, `BookDetailRefreshTest`; the screen's part: `FollowShowing`,
`BookDetailShowingTest`).

**The timer screens (`feature.timer`).**
- Timer(bookId) follows `container.timer.state` (so it works when reopened from the notification);
  one timer at a time: another book's shows as "already running" with a way to it. Start starts at
  the tap; the book's file and start position (`TimerBook.startProgress`, from
  `PageMath.startPercent`) become the session's `fileId` and `startProgress`, filled in when the
  book arrives if it was still loading. In landscape the clock card and the rest sit side by side.
- Save pauses the timer (the session ends there) and asks for the page reached, with the page total of the user's edition
  shown and editable (saved to `pageTotals` when it differs from the server's), or a
  percentage (remembered in `progressUnits`); empty saves the time only. Then `stop()`,
  `tracking.logTimedSession(...)` in the app scope, `clearFinished`, and the result screen replaces
  the timer, so Done goes back to where the user was. Under 10 s: Discard only.
- The result: minutes, pages read, pages per hour (this session), pages left, time left (the
  book's pace from its session stats, else this session's), and the daily goal (`dailyGoalMinutes`
  against `activityDay(today)` in the server's zone) when one is set. Queued (offline) says so.
- Dashboard: `HomeContent(timerBookId, timerBar, onTimer)`; each Currently Reading row has a timer
  button next to the play button, and `TimerBars`: a slim "Reading <title> 12:34" bar opens the
  running timer, and "Save your session: 25:10" opens a stopped session still waiting to be saved.
- Reader: `ReaderTimerNotice(bookId)` warns when a timer is on for the book being opened (the
  reader records its own session), says how long it has run ("has run for 9 h 12 min", so a
  forgotten one stands out) and offers to stop it; the time up to the reader's opening is saved
  without a page (`ReaderTimerNoticeTest`).

**Statistics (`feature.stats`).** Tracking > Statistics, two tabs over one filter row (library, when the user has more than one; the period 30 / 90 / 365 days / this year, which scopes the windowed charts: daily time, peak hours, weekdays, genres, archetypes, funnel; the others keep the web's windows). Reading: the `activity-overview` (today, last 7 days against the 7 before with a 7-day strip that opens Day(date), streaks, books this year, the goal with ahead/on pace/behind and its monthly trajectory, the weekly rhythm), then 11 chart cards after the web's `statistics/components/user` (reading time only, no listening). Library: the `statistics/*` summary and ranked lists (formats, storage, added over time, authors, series, genres, languages, decades, largest books). Each card has loading, "not enough reading yet" (the web's thresholds) and tap-to-retry states; a reload keeps the old chart (and the overview and library figures) faded. Days are in the user's time zone (users.settings.timezone): the heatmap and weekdays come from `daily-reading`'s local days (`reading-heatmap` stops at today in UTC, `favorite-days` takes UTC weekdays), archetypes are moved by the zone's offset, the goal trajectory asks for 12 whole UTC months, and a new day since the last load (the screen kept, or the app left overnight) reloads every dated chart, on any reload or on showing again. The weekly chart leaves out a partial oldest week (its days still count in the total). Charts follow the dataviz skill: accent = the one series, grey = the comparison (a fainter grey beside the Grey accent), accent steps for magnitude and the funnel, a validated second colour for weekends; the selection is read only while drawing, so a tap doesn't rebuild a chart.

**Achievements and the year in review (`feature.achievements`).**
- Achievements (root, Tracking > Achievements; toolbar: Rewind): the catalogue's categories (reading, library, exploration, dedication, devices) as Nexus sections with a ring and earned/total, then badge cards two by two: the medallion (the icon from the server's kebab-case `iconName`, in the rarity colour with a full ring once earned; otherwise dim with progress toward the threshold in the accent), tier pips, name, rarity (dot + word), description, progress or the earned date, and the context book (tap opens it). Hidden ones not earned show a lock and "???". Tapping a card opens a sheet with every tier. Rarity colours (slate, blue, magenta, gold) pass the dataviz validator per mode (`AchievementLogicTest`).
- The unlock celebration (`AchievementCelebrationHost`, in AppScaffold; `CelebrationViewModelTest`): claims only while the app is in the foreground: `claimCelebration()` on coming to it (not while a blocking screen is on top, even on the first start), 2.5 s after any `tracking.version` bump or anything the reader's sync sent (`ReadingChanges`; a later trigger pushes the wait back, one while the request is out claims again if it found nothing) and on leaving the book page or the reader (both block it: the book page's finish flow claims for itself, in the app scope, handing a claim that lands after the page closed to the shell). A request already sent is never cancelled; its claim waits behind a blocking screen, but one held over 14 minutes (the server re-offers after 15) is dropped unacknowledged. Each claim shows as a toast card at the top; tap opens Achievements, the close button, swiping up or 6.5 s resumed (9 s for epic/legendary; leaving the app restarts the count) acknowledges it, then the next is claimed. Achievements reloads 2.5 s after a tracking write and at once when something is claimed.
- The year in review (Rewind(year?), pushed; from Statistics' and Achievements' toolbars; January opens last year): story cards in a pager with segments on top, built from `activity-calendar/:year` (time with listening, as the Calendar counts it; days, sessions, months, the year's longest streak and best day), `activity-overview` (all-time streak), the book query with a `finishedAt between` filter (covers, top authors) plus each book's readings (days taken, time), `completion-timeline` (per month, filtered to the year; its sum is the "N books" everywhere, rereads included), peak hours (the server's for this year; an earlier year from its `session-timeline` weeks) and `genre-reading-time` (this year only), and the achievements earned that year. The status keeps only the latest reading, so when the timeline counts more finishes than the query found, candidates (finished after the year, or reading, re-reading, on hold, abandoned or skimmed now; paged, at most 200 checked) are kept if one of their readings completed that year; finishes still not found show as "+N" beside the covers. The years offered always include this one (an empty January review of last year can move on). Each part fails on its own; its card is left out.

**The calendar and goals (`feature.calendar`).**
- Calendar (root, Tracking > Calendar; toolbar: Add a book (see "Reading dates"), History, Reading goals, Save as image): the "Book Calendar" card with the month between arrows (swipe works too; tapping the month returns to this month; no going past this month or before January of the server's first `availableYears`), its summary (days read, time, books), a Monday-first grid where each day shows the covers of the books read that day (one fills the cell, two or three overlap with the most read in front, more add "+N"; a day whose detail hasn't loaded shows a dot), today framed in the accent. Under it, the month's books (days and time), tapping one opens its page; tapping a day opens Day(date). Days and "today" are the server's (users.settings.timezone, else UTC).
- Loading: `activity-calendar/:year` (kept five minutes per year, while the user moves between months) says which days have reading; only those days' `activity-days/:day` are fetched, six at a time, and only when new or changed (time or session count) since the month was cached, never loaded, or today. Months are cached as JSON in `cacheDir/calendar/<account>/<YYYY-MM>.json`, so a month seen before shows at once and offline. A changed day whose detail failed keeps its old totals, so it is fetched again next time. Pull to refresh; a tracking write here (`tracking.version`) refreshes at once (300 ms later, so the ReadingChanges move it brings is covered by the same load). Showing the Calendar again (back from a book or the reader, the app resumed) refreshes only when something was sent from this phone since the last load (`ReadingChanges`: the readers' sessions skip the tracker; the year's calendar is asked again too), the day changed, the last load failed, or it is 10 minutes old: sessions from the Kobo or the web show by then, or on a pull (`CalendarResumeTest`). Day and Reading goals refresh when shown again at most every 30 s, or when the day changed.
- Save as image: the month card is recorded in a `GraphicsLayer`, put on the page colour and written as a PNG through MediaStore (Pictures/Ottershelf, no permission needed).
- Day: totals (time, sessions, books) with the daily goal's ring when `dailyGoalMinutes` is set, the books started, finished or given up that day ("Started and finished", from `ReadingLog`'s device copy), then each session (cover, title, start and end in the day's zone, time on that day, pages read from `progressDelta` and the user's page total: `pageTotals`, else the book's `pageCount`, fetched once per book; else the percentage). Tapping one opens the book (with a `TitleHint`). "Add a book for this day" at the end opens the dates' picker for that day.
- Marks: every reading of every book (History's data, `ReadingLog`: its device copy at once, then its loads, fetching only books whose status changed; on `tracking.version`, pull, and the Calendar's reloads above) marks its start day and its end day (`CalendarMath.marksOf`: finished and skimmed as finished, abandoned as given up; one per book and day, the end over the start). A day cell shows those books' covers too (`CalendarMath.stack`: finished or given up in front with a check or a cross in the status colour, then the books read, then the ones only started); the month's list adds the books only marked ("Started 3 Sept · Finished 24 Sept"), and the summary's book count is the list's. So a reading given dates by hand shows on its days without any session.
- Reading goals (from the calendar's toolbar and Settings > Reading > Reading goals): the yearly goal (`tracking.setYearlyGoal`, which rewrites the whole dashboardConfig; progress from `activity-overview.goal`, and the pace worked out on the phone against the goal shown, with the server's rule, because the server caches the overview for minutes and it can still carry the old goal); the daily goal (`appSettings.update { it.copy(dailyGoalMinutes = n) }`) against today's `activity-days` total; current and longest streak and the last 7 days from `activity-overview.snapshot`.

**Reading dates (`feature.calendar`: `ReadingDates*`).** Start and end dates for a book, from the Calendar's toolbar + (for today), a Day's "Add a book for this day", and History's row actions.
- **The picker** (a bottom sheet on the card colour, the quote picker's look): "Reading now" before the user types (`books/query` readStatus includesAny reading, rereading, on_hold, `lastReadAt` desc), else the quick search `q` (title, author, series; relevance, 30), 300 ms after the user stops typing; rows as the scanner's chooser (cover, title, author, `FormatChip`s, the user's status, `ReadingChanges` first). "Scan a book" opens Scan(pick = true): the scanner hands the book back (`feature.scan.ScanPicks`) and closes, and the sheet opens for it when the screen resumes; back without one, the picker again.
- **The sheet:** the book, its readings when it has some (radio rows: "Finished 3 Sept 2024" over "Started 1 Aug 2024 · 12 sessions", "Reading now", "Finished, no date", and "Add another reading: a reread"), How did it go? (Finished, Gave up; Skimmed for a skimmed reading, Still reading for the open one), Started (optional, clearable) and the end date (up to today in the account's zone; the day the user came from by default), what the save does to the user's status when it changes it (or that a book on Want to read stays there), Cancel and Save. Save waits for the readings (`canSave`).
- **Where the dates go** (`ReadingDates.defaultTarget`, `ReadingDatesTest`): the open reading if it started by that day (the user finished it; adding one would make a reread still going); else a closed reading without an end date (a status carried over by the server's backfill, or its placeholder before a reread); else a finish marked by hand (no start, no sessions) on or after that day (marking read with `PATCH books/:id/status` alone makes `{startedOn: null, endedOn: <that day>, completed, manual}`, so it was marked late); else a new reading. "That day" is the end date in the sheet: until the user picks a reading (tapping one, even the one already chosen, is a choice), moving the end date or the readings arriving works the choice out again, and the fields the user hasn't set follow the reading (`ReadingDates.refill`; the ones the user set stay as set, even when set while the readings were loading). So + for today on a book marked read by hand in June, with the end moved back to the real finish, fixes that reading instead of adding a second. The user can pick any. History opens the sheet on its row's reading, with its own dates; once the book's readings are fetched, the fields the user hasn't set take that reading's dates and outcome from the server (History's copy can be older, and Save sends every field, so a start filled in elsewhere since isn't cleared).
- **Saving** is the book page's past-read path (`TrackingRepository.createPastRead` / `updateAttempt`: `POST books/:bookId/reading-attempts {startedOn?, endedOn, outcome}`, `PATCH .../:attemptId {startedOn or null, endedOn, outcome}`, or `{startedOn}` alone for a reading kept open), in the app scope. The server then rebuilds the status from the readings (`ReadingDates.statusAfter`, reading-attempt.service.ts): an open reading makes it reading, re-reading or on hold; unread and want to read stay; else the newest reading by id decides, so an older reading given up, added now, sets abandoned (the sheet says so). A reading added to a book on unread is followed by `PATCH books/:id/status {status}` (read or abandoned, no dates: the new reading keeps its dates), so it counts as the user's in History and the calendar. A book on want to read stays there, as the server, the book page's past read and the web's reading log leave it (the user may mean to read it again; no status write, so no status-changed event or Kobo projection): the sheet says the reading won't show in the calendar or History (`ReadingDates.staysWanted`). Then the book and its readings are read again and handed to the screen (`ReadingDatesViewModel.saved` to `readingSaved`: the marks or rows show at once, over any load that started before the save); the tracker's own `version` bump reloads the calendar, the day and History. The status read again (else the one `statusAfter` works out) goes to `ReadingChanges.overrideStatus` (`ReadingDatesRemote.statusChanged`): the attempt routes change the user's status without the tracker's status writes, and the library's grids, Downloads and the picker would otherwise keep one a book page or the quick view put there earlier. A reading gone from the server (another device) can't be saved as a new one (`targetGone`). A new reading is added once: the POST isn't idempotent, so after one that failed without a refusal (no answer, or a 5xx: the server stores the reading before rebuilding the status), the next Save for that book looks among its readings for a new manual one with the dates sent and PATCHes it to the sheet's dates instead of adding a second (`ReadingDatesViewModel.create`; a 4xx means nothing was stored). Once the sheet, opened again, lists that reading, the user has seen it: a new reading with other dates is added (moving the one the user saw would lose it silently), and only exactly its dates again PATCH it. The attempt routes don't raise the server's status-changed achievement event (the status route does), as on the web's reading log.
- Screenshots: `calendar/add_picker`, `add_dates_new`, `add_dates_existing`, `edit_dates_open`, `month_marks`, `day_readings`, `history/row_actions` (`ReadingDatesScreenshotTest`, `HistoryScreenshotTest`).

**The reading history (`feature.history`).**
- History (root, Tracking > History, right after Calendar; toolbar: Calendar, and the Calendar's toolbar opens History; both are roots, so each replaces the other): every reading (BookOrbit's reading attempts, rereads as their own rows) as DashCards: "Reading now" (open readings, latest start first), then each year by the day its readings ended (a header with "N books finished · N days of reading · N given up", then a card per month, newest first), then closed readings without dates. A row: cover, title, author, the status icon and "Started 3 Sept · Finished 21 Sept" / "Reading since 3 Sept" / "Started 1 Aug · Gave up 12 Aug" (a date in another year shows it), the days it took (both ends counted; "Day N" while open), "Reread #N" (one more than the readings finished before it) and the user's rating as stars. Tap opens the book page (with a `TitleHint`). Chips: All / Finished (skimmed too) / Reading (on hold too) / Gave up, with counts; a year's summary counts all of its readings. Pull to refresh; empty, error and "couldn't refresh" states.
- Data: the server has no list of attempts across books, so `POST books/query` with `readStatus includesAny` reading, rereading, on_hold, read, skimmed, abandoned (200 a page, at most 10 pages), then `GET books/:id/reading-attempts` per book, six at a time. Unread and want to read are left out on purpose: setting a book back to one says the user hasn't read it (the server closes the open reading as abandoned, so a book opened by mistake would show as given up). Kept on the device (`cacheDir/history/<account>.json`): the list shows at once and offline, and later loads fetch readings only for books whose status row changed (`HistoryCard.fingerprint`: status, dates and `updatedAt`, which the server rewrites with every reading change); a pull fetches them all. A book whose readings fail keeps the saved ones (retried next time) and is counted in a "couldn't be loaded" line. Reloads on `tracking.version` and `readingChanges` (300 ms later while shown, so a write, which moves both, makes one load; else when shown again), and when shown again on a new day, after a failed load, or once the last load is 10 minutes old; back from a book page with nothing changed, nothing is asked (`HistoryResumeTest`).
- Row actions: a small pencil at each row's end ("Edit dates"), and "Add dates" on the rows without dates; both open the Calendar's dates sheet on that reading (see "Reading dates"). Tapping the row still opens the book.

## Readers

Every format the web reads, except audiobooks, opens in one of three readers. They share one look
(the EPUB reader's chrome: Compose top and bottom bars over the content, immersive, bottom sheets for
contents and settings) and one data path (the same progress queue, sessions and offline copies), so
tracking, the Dashboard and statistics see every format alike. All three keep the screen on only for
10 minutes after the last sign of reading (a page turn or move, the bars shown or hidden:
`KeepScreenOnWhileReading`, `READING_SCREEN_ON_MS`); after that the phone's own timeout applies, so
a book left open doesn't keep the screen lit.

| Reader | Formats | Route | Package |
|---|---|---|---|
| foliate (WebView) | EPUB, KEPUB, MOBI, AZW3, AZW, FB2 | `Reader(bookId, fileId, title, cfi?, format?)` | `feature.reader` |
| Comics | CBZ, CBR, CB7 | `Comics(bookId, fileId, title)` | `feature.comics` |
| PDF | PDF | `Pdf(bookId, fileId, title, page?)` | `feature.pdf` |

**Which file and which reader (`core.format.BookFormats`, `ui.nav.ReaderRouter`).**
- `BookFormats` is the web's format tables (packages/types `READER_OPENABLE_FORMATS`,
  `FORMAT_TO_GROUP`, `DEFAULT_FORMAT_PRIORITY`), audio left out; KEPUB goes to the foliate reader
  (the web doesn't open it). `readerFor(format)` gives the `ReaderKind`; `isPaged` is true for
  comics and PDF.
- `pickFile(book)` is the file Read opens, picked as the web does: among the files a reader here
  opens, the primary one, else the first by the library's format priority (`BookDetail.formatPriority`,
  the web's default order when empty), files of an unranked format last, in the server's order.
- `ReaderRouter` is the only place routes to readers are built: `route(bookId, fileId, format,
  title)`, `route(book)` (the book page's pick) and `annotation(...)` (a highlight: a CFI opens the
  foliate reader there, a `pageno` the PDF reader at that page). It is used by the book page's Read
  (`BookDetailUiState.readTarget`, the button says "Read PDF", "Read CBZ"... for anything but EPUB),
  the Dashboard's play buttons and The Long Wait (the widget's `readFileFormat`/`fileFormat`; any
  openable format), Downloaded (the copy's format), and the notes' "open in the reader"
  (`feature.notes.readerRoute`, `BookHighlightsUiState.readerRoute`). `Route.isReader` covers all
  three (the celebration toast waits while one is open).
- MOBI, AZW3, AZW, FB2 and KEPUB are routed to the foliate reader, which reads them whole (see
  "The foliate reader's other formats").

**Every format at a glance** (checked end to end in the code):

| Format | Opens from (all through `ReaderRouter`) | Offline copy | Read from | Position and sessions |
|---|---|---|---|---|
| EPUB | Book page, Dashboard, Downloaded, notes | `book.epub` + `info.json` (`openEpub`; an older download without `format` is this) | `epub/<id>/...` via `ReaderRequests` | `ReaderViewModel` → `ProgressStore` (cfi) |
| KEPUB, MOBI, AZW3, AZW, FB2 | Book page, Dashboard, Downloaded, notes | `book.<ext>` (`openOffline`) | `books/files/<id>/serve`, whole, via `ReaderRequests` | `ReaderViewModel` → `ProgressStore` (cfi) |
| PDF | Book page, Dashboard, Downloaded, notes (a `pageno` opens that page) | `book.pdf` (`openOffline`) | `PdfFiles`: the copy, else `serve` into `cacheDir/pdf` | `PageReaderProgress` → `ProgressStore` (pageNumber) |
| CBZ | Book page, Dashboard, Downloaded | `book.cbz` (`openOffline`) | `ComicSource`: zip entries, else `cbz/files/<id>/pages/<n>` | `PageReaderProgress` → `ProgressStore` (pageNumber) |
| CBR, CB7 | Book page, Dashboard | none (no RAR/7z reader on the phone; a CBZ beside it is kept instead) | `cbz/files/<id>/pages/<n>` (the server extracts) | `PageReaderProgress` → `ProgressStore` (pageNumber) |

A visit that never moved records nothing (`core.sync.ReadingVisit`): until the user turns a page, scrolls,
zooms or jumps since opening, no position is saved and no session sent (the server takes a file-route
session as reading activity and sets the book to Reading by itself). A session that ends before the
first move (the phone locked on the first page) is held and sent with that move; closing drops it.
Every reader's pushes run `ProgressStore.onSent` → `ReadingChanges.changed()`, so the Dashboard,
the book page and the achievements toast refresh as they do for EPUB. Sessions (the same
`ReadingSession` body for every format, with `source: android` like the timer's, so the server
doesn't record them as `web`) go through `enqueueSession` and a forced sync to the same
file routes, so the book page's reading log, the calendar and statistics count them.
`SyncCoordinator` takes server baselines for every downloaded file, whatever its format.

**The foliate reader's other formats (`feature.reader`, `assets/reader/reader.js`).** KEPUB, MOBI,
KF8/AZW3, AZW and FB2 open in the EPUB reader, as on the web (useFoliate.ts): the server's `epub/`
routes serve EPUB files only, so these are read as one whole file with foliate's `makeBook`.
- `Route.Reader.format` reaches `ReaderViewModel`; `ReaderRequests.readsWholeFile(format)` is true
  for every foliate format but EPUB (null is an older route: EPUB). `readerOpen` then gets
  `format`, and reader.js fetches `/api/v1/books/files/<fileId>/serve` (the web reader's route, not
  `download`), wraps it in a `File` named `book-file-<fileId>.<format>` typed `application/zip`
  exactly as the web does, and hands it to `makeBook`: MOBI/KF8/AZW by their `BOOKMOBI` header
  (`mobi.js` + `vendor/fflate.js`), FB2 by the `.fb2` name (`fb2.js`), any other zip (KEPUB) as an
  EPUB (`vendor/zip.js`). A MOBI whose PalmDOC header says encrypted (Kindle DRM) is refused with
  a message instead of showing garbage (mobi.js doesn't check). The section prefetch is off for
  these (the book is in memory).
- `ReaderRequests` answers exactly that route for this file (`isFileRoute`), and nothing of the
  `epub/` routes for such a book: the downloaded copy first (`Downloads.openOffline`, as
  `LocalBook.Whole`; an EPUB is `LocalBook.Epub`), else the server with the account's client
  (60 s read timeout), streamed into the WebView. The book shows once the whole file is in.
- Positions, sessions, bookmarks, highlights and search are the EPUB reader's, unchanged: foliate
  makes CFIs for these sections as for an EPUB's (`/6/<2(i+1)>!...`, section index based), and the
  save body carries what foliate computes (cfi, percentage, the KOReader xpointer; the server syncs
  Kobo positions for EPUB files only), exactly like the web reader's. Highlights draw through the
  overlayer in every one of these formats. The selection popup offers only Copy and Share when
  foliate gives a selection no CFI (`SelectionPopupState.canHighlight`).
- Checked in Chromium against Calibre-made samples (MOBI6, KF8/AZW3, FB2, a zipped EPUB as KEPUB,
  and a MOBI flagged encrypted) through reader.js with a stand-in bridge: open, title and TOC, a
  position's CFI restored to the same place, a selection's CFI, the highlight drawn, search hits
  (`tools/reader-js-test/run.sh`, rerun after changing reader.js or foliate).

**The foliate reader's layout (`feature.reader`: `ReaderStyles`, `ReaderSettings.kt`, `ReadingTime`, `assets/reader/reader.js`).**
- **Settings** stay on the phone (`ReaderPrefs`, DataStore `reader.prefs`). The original keys keep
  their names; the added ones are the web's (packages/types `EpubReaderSettings`), with its values,
  ranges and steps (`ReaderPrefs.normalized()`), so they can move to `reader/defaults/epub` later:
  `paragraphSpacing` (0 = the book's, to 2 em), `letterSpacing` / `wordSpacing` / `textIndent` (null
  = the book's; 0..0.2, 0..0.5, 0..4 em), `maxInlineSize` (400..1600 px; Narrow/Medium/Wide/Full are
  560/720/1160/1600, read back by the web's bands; 720 was always the reader's), `fixedLayoutSpread`
  (`auto`/`none`), `footerDisplayMode` (0 page, 1 time left in the book, 2 in the chapter). Only
  `customBg` / `customFg` (the Custom page colour) have no web key (the server's schema is strict, so
  they would stay on the phone).
- **Styles:** `ReaderStyles.pageSettings(prefs, appColors)` is everything reader.js applies: the
  chapters' CSS through foliate's renderer `setStyles` (it goes into every chapter as it loads, so it
  survives chapter changes), the page colours, and the paginator's attributes. The original settings
  produce exactly the CSS reader.js used to build itself (`ReaderStylesTest` keeps the old template);
  the spacing rules are the web's (useReaderState.ts), written only when set, so a book's own
  styling is left alone otherwise. Page colours: App, Original, Sepia, Dark, Black, and Custom (the user's
  background and text, `ReaderColorPicker`: presets, hue/saturation/lightness, hex; WCAG contrast
  shown, cut to one decimal so 4.48 reads 4.4, a warning under 4.5:1; links take the accent when it
  has 3:1 there, else the text colour).
- **Scrolled flow** (Reading flow: Pages / Scroll; foliate's `flow="scrolled"`, one chapter as one
  continuous page in the paginator's native scroller). No page-turn zones: a tap anywhere shows or
  hides the bars. The volume keys move 85% of a screen, and at a chapter's end go on to the next
  (foliate's `next(distance)`). A swipe up that starts at the end of a chapter opens the next one at
  its start, a swipe down at the start the previous one at its end (`attachPull`, passive listeners);
  "Swipe up for the next chapter" shows in the page's bottom margin there (`chapterEnd` on the
  relocate). At the end of the last chapter the relocate says `bookEnd` instead (see "The end of the
  book" below): foliate's fraction there falls short of 1 by the share of the last chapter on screen
  (0.98 at the bottom of a book ending in a short afterword). A selection's popup hides while the text scrolls and comes back where it is; a tapped
  highlight's closes when the text moves. Position, sessions and the no-move rule: foliate reports
  the user's scrolls as `reason: 'scroll'`, so they are `turned` like a page turn. Three paginator patches
  ([bookorbit-android]): its own anchors are told apart from the user's scrolls by where they left the
  scroller (a flag used to stay set when an anchor moved nothing and swallowed the user's next scroll, so
  it wasn't counted and the position didn't follow), with the browser's scroll anchoring off;
  switching from pages to scroll measures the chapter at the screen's width (it was measured at all
  its columns' width, anchoring the place wrong until a resize corrected it); and a Margins change
  lays the chapter out again (in pages the container resizes and its observer does that, in the
  scrolled flow nothing resized and the old side padding stayed until the next chapter).
- **Pages: the next chapter prepared.** foliate's pre-render patch ([bookorbit-android]) builds the
  next chapter in a hidden View after the current one, in the container's flow, and swaps it in on a
  forward turn. Any other chapter change (a contents pick, the slider, a jump, a turn back into the
  previous chapter) discards it before the new View goes in, and so does an idle moment in the last
  chapter: left there, it came first and pushed the new chapter a page-height down, a blank page.
- **The section prefetch** (reader.js `cachedFetch`, streamed EPUBs only): every file the streaming
  loader reads goes through a response cache, and once a turn has painted the section two ahead is
  fetched into it (the one ahead is held loaded), so a book of one-page sections doesn't wait on
  the native side at each turn. It keeps at most 96 bodies, dropping the oldest, and 12 MB, dropping
  the oldest of those that have come in (one still coming in, such as that prefetch, stays). A body
  over 1 MB (a large image or a fixed-layout page) is handed over but not kept, since foliate keeps
  its own blob of what the held sections use, and a section over 1 MB by the manifest isn't
  prefetched (dropped as it came in, it would be fetched again when held). Failures aren't kept.
  `tools/reader-js-test/cache.sh` checks it with a stand-in server.
- **Fixed-layout books** (`Opened.fixedLayout`): the sheet has only the page colour and Page
  spreads (Book default / Single page); the spread is set on the book's rendition before foliate
  opens it, so a change reopens the book in the page at its CFI (as the web does). They always turn
  pages, whatever the flow says.
- **Time left (the footer):** the bottom bar's last line; a tap cycles page, time left in the
  chapter, time left in the book (remembered in `footerDisplayMode`). `ReadingTime.timeLeft`: the user's
  pace in this book (percent per hour, the book page's `trustedPace` from `books/:id/sessions`
  stats, kept per book on the phone by `ReaderPaceStore`, settings DataStore `reader.pace.<account>`,
  at most 100 books) for the book's remainder, and for the chapter its share of the book from
  foliate's size-based times; without a trusted pace, foliate's own estimate (relocate
  `time.section` / `time.total`).
- **The end of the book:** every relocate carries `bookEnd` (reader.js `atBookEnd`): nothing left
  to turn or scroll to, i.e. the paginator's `atEnd` (the last page of the last linear section), the
  bottom of that section when scrolled, or a fixed-layout page on screen that is the book's last
  (both sides of a landscape spread; in portrait only the side showing). The next book in the series
  shows there (see "Next in series"); foliate's fraction can't say it (paginated it marks the end
  of what shows, so a long book's last pages all come within half a percent of 1; scrolled, the
  top; a spread, its first page). Only a flag: the saved percentage stays foliate's, since the
  server works the user's status out from it.
- **Relocates to the app** (reader.js `queueRelocate`): at most one every 250 ms, and only when it
  differs from the last one sent. foliate relocates on every frame while a chapter's CSS animates
  its layout (60 bridge calls a second), and a few times as a section settles. The first after a
  pause goes at once, so a turn shows straight away; later ones wait, the newest replacing the one
  waiting with the user's move (`turned`) carried over; one still waiting goes when the page is hidden.
  The full location follows each one sent.
- **The user's reading** (`ReadingSigns`): only a relocate the user moved to (`turned`), and the one that the
  book opening in a page or a jump the user picked brings, is activity for the visit (`ReadingVisit`: a
  session ends after 5 minutes without any) and keeps the screen on (`ReaderUiState.activity`, not
  the fraction). Part-way down an animated chapter every relocate differs, so four a second still
  reach the app; each one used to keep a book left open there in one session that never ended,
  with the screen lit. `hostile.html` in tools/reader-js-test checks the page's side (an animated
  chapter at its top: 180 relocates in 3 s before, none after; part-way down, where they differ:
  181 before, 11 or 12 after, the last one where the page stopped), `ReadingSignsTest` the app's.
- `tools/reader-js-test/scroll.html?check=scroll|pages|fxl|fxl5` checks these through reader.js in
  Chromium on books `make-epubs.sh` builds (a page that isn't on screen sends no scroll events; the
  check sends the one the browser would; `pages`: jumps and turns back between chapters with the
  next one prepared, and the book's last page; `fxl5`: a last page on a spread's right). The
  settings sheet, colour picker, footer and chapter-end hint are in
  `ReaderScreenshotTest` (`reader/reader_settings*`, `reader_colour_picker`, `reader_chrome*`,
  `reader_scrolled_end`).

**Offline copies (`core.download`).** Any openable format can be downloaded except CBR and CB7
(`BookFormats.canKeepOffline`): the server extracts those archives and serves their pages as images
(`cbz/files/:id/pages/:n`), and the phone has no RAR or 7z reader, so an offline comic is always a
CBZ. The book page downloads `BookFormats.pickOfflineFile(book)` (the same pick among keepable
files: a CBR comic with a CBZ beside it keeps the CBZ; one without offers no download). While that
CBZ is kept (current, and still on the server), Read opens it online too, on the book page
(`BookDetailUiState.readTarget`) and the Dashboard's play buttons (`HomeViewModel.fileToRead`), so
the user's place stays in one file (`BookFormats.readsKeptCopyInstead`; positions are per file). The book's
progress on the server's cards still follows its primary CBR.
- Layout: `files/downloads/<account>/<bookId>/book.<format>` (`OfflineFiles.name`), plus
  `info.json` for an EPUB only, `detail.json`, the covers, and `meta.json` (`DownloadedBook`, which
  now records `format`). A `meta.json` without a format is an older download: an EPUB in exactly
  this layout (`book.epub` + `info.json`), so nothing is migrated.
- Checks before a copy is kept: EPUBs as before (`LocalEpub`: container, info matches); a PDF's
  `%PDF-` header; a CBZ has at least one page the comics reader shows (`OfflineFiles.isCbzPage`, the
  rule `LocalCbz` pages by), no page declaring over 128 MB inflated, and pages declaring at most 100
  times the file together (a zip bomb is refused); a KEPUB a zip; MOBI/AZW/AZW3/FB2 non-empty
  (`OfflineFiles.problem`). A body shorter than its Content-Length is retried, and so is a zip that
  won't open when neither Content-Length nor the server's size says the whole body arrived.
- Opening offline: `Downloads.openEpub(bookId, fileId)` (the foliate reader's `LocalEpub`, EPUB
  only; its whole-file formats use `openOffline`) and `Downloads.openOffline(bookId, fileId)` for
  any format: an `OfflineCopy(format, file)` of this exact file, or null to read from the server.
  Call it off the main thread.

**Page-based progress (`core.sync.PageProgress`, `PageReaderProgress`).**
- The server's `SaveProgressDto` has `pageNumber` (an integer column; KOReader writes it for paged
  files too). A page reader saves `{cfi: null, pageNumber, percentage}` with percentage =
  page / pageCount * 100, as the web's CBZ and PDF readers do (`PageProgress.body`; a two-page
  spread reports its last page). There is no other position string for pages. It opens at the saved
  page, else one estimated from the percentage (`PageProgress.startPage`).
- `ProgressStore.Position` carries `pageNumber`; `sameAs` compares it only when both sides have
  one, so EPUB positions (never a page number) compare exactly as before. `FileProgress` and
  `SaveProgress` carry it too (null for EPUB; `explicitNulls = false` leaves it out of the body).
- `PageReaderProgress` (`container.pageReaderProgress(bookId, fileId, viewModelScope)`) is the EPUB
  reader's open/save/push/session rules without a WebView, over the same `ProgressStore`:
  `open(hasLocalCopy, jumpPage?)` decides where to start (server, this device's newer offline
  position, or `choice`/`choose(keepMine)` when both moved; `Opening.freshStart` after a manual
  Read/Unread, nothing saved until a page turn; the same for `jumpPage`); `onPage(page, count)` on
  every page change (2 s debounce, device first, then a checked or unchecked push from the app
  scope), `onActivity()` for zoom and scrolling, `onPause()`, `close()` from `onCleared`; `jumps`
  emits the server's position when a conflict found mid-book is settled for it. Sessions end after
  5 minutes idle or on leaving, under 10 s are dropped, and go through `enqueueSession` + a sync
  (`ReadingVisit`, as in the EPUB reader: nothing is saved or sent until the user moves since opening: a
  page other than the first shown, or `onGesture()` for the user's zoom or scroll; `onActivity()` alone
  isn't a move),
  so they reach tracking, the Dashboard and statistics. `PageReaderProgressTest` covers it.

**Reader settings sync (`core.readerprefs`).** The web's per-format reader settings
(`reader/defaults`, `reader/preferences/:bookFileId`; useReaderSettings.ts):
- `CbxReaderSettings` / `PdfReaderSettings` with the web's defaults, and `ReaderSettingsSpecs.Cbx` /
  `.Pdf`: the web's sanitisers (only known keys with valid values, the server's schema rules; PDF's
  old `wrapped` read as vertical), the merge (built-in defaults < the account's group default < the
  book's own keys) and `changes(before, after)` (what a PATCH `set` carries). `spec.withDefaults(t)`
  is the same spec with other built-in defaults (the PDF reader's vertical strip).
- `container.readerSettings(spec, fileId)` gives a `ReaderSettingsStore`: `load()`, `effective`,
  `customized`, `updateBook { it.copy(...) }`, `resetBook()`, `updateDefault { }`,
  `resetDefault()`. Always kept on the device (settings DataStore `readerprefs.default.<account>.<group>`,
  `readerprefs.book.<account>.<fileId>`); in the account's reader sync mode
  (`AuthUser.settings.syncReaderPreferences`) `load()` takes the server's and changes are PATCHed key
  by key (`ApiReaderPrefsRemote`, bodies exactly `PatchDefaultDto` / `PatchPreferenceDto`), resets
  DELETEd; failures aren't retried, as on the web. The EPUB reader keeps its own `ReaderPrefsStore`.

**The comics reader (`feature.comics`).** The web's CbzReaderView in the EPUB reader's chrome.
- **Pages:** from the downloaded CBZ when this exact file is kept (`Downloads.openOffline`), else the
  server: `GET cbz/files/:fileId/pages` for the count, `cbz/files/:fileId/pages/:n` for each image
  (the server extracts CBR and CB7 itself). The local page list is the server's (`LocalCbz`: files
  only, no hidden path part, the server's image extensions, stored/deflated with bytes
  (`OfflineFiles.isCbzPage`, which the download checks too), natural order like
  `localeCompare(numeric, base)`), so page numbers, and so positions, match online and offline.
  Images go through `ComicImages`, the reader's own Coil loader: the account's authenticated client,
  its own 256 MB disk cache (`cacheDir/comics`, so a comic never evicts the covers), a fetcher for
  zip entries (`LocalPageFetcher`: each page is copied once into that disk cache and read as a file,
  so a large page is never held whole in memory and Telephoto tiles it offline too; a page yields
  no more than its zip entry declares, and one declaring over 128 MB isn't read, so a zip bomb fails
  the page (Retry) instead of filling the storage; the copies stay when the reader closes, so
  reading it again writes nothing), no crossfade,
  and an OkHttp dispatcher of its own (3 requests per host), so pages never hold up the API calls
  or the covers. Its memory cache (20% of the memory class) would keep the decoded pages until the
  app went to the background, so a closing reader drops its comic's (`ComicImages.forget` by
  `ComicSource.cacheKeyPrefix`: the server's page URL base, or `comic:<copy path>:`); after Read
  next the next comic's reader already exists and its pages stay. Once the pages on screen are in,
  the 5 ahead in reading order and then the 2 behind are prefetched, decoded small, which also gives
  their sizes (the spreads' wide-page rule).
- **Paged mode:** a `HorizontalPager` of `SpreadLayout` screens (`reverseLayout` for right to left);
  single pages are Telephoto `ZoomableAsyncImage`s (pinch, double tap to 2.5x, pan, up to 4x, large
  pages tiled from the disk cache), fit page / width / height / actual as `ContentScale`s; a spread
  is two `AsyncImage`s in one `Modifier.zoomable`. Two-page view shows spreads in landscape (the web
  switches at 900px, wider than a phone) or everywhere with "Two pages in portrait too"
  (`forceTwoPage`); cover alone, shifted alignment, wide pages alone unless "In spreads", spread gap.
- **Infinite / No gaps (long strip):** a `LazyColumn` of pages (8dp gaps or none); pinch or double
  tap widens the column up to 3x around the fingers (or the tap) and it scrolls sideways; the page
  taking most of the screen is the current one. Once per opening the list goes to the chosen page
  before anything is reported (`stripPlaced`: a list restored after the process was killed keeps
  its old place). A page much taller than wide (a webtoon) is decoded at the column's width as a
  software bitmap of at most 12 MP, not at Coil's 4096 px cap. A page is laid out at most 250,000 px
  tall, or 60,000 px next to a column 8,191 px wide or more (`stripPageHeight`: Compose can't
  measure 262,143 px, or 65,535 px next to such a column); a taller one (a merged chapter) shows
  whole in that height, narrower than the column (a pinch widens it no further). A page that fails
  to load, here or in a spread, shows Retry.
- **Controls:** taps on the outer quarters turn the page (mirrored for right to left; in the strip
  they scroll 85% of a screen), the middle toggles the bars; volume keys turn pages. Top bar: Back,
  title, a mark when reading the downloaded copy, settings. Bottom bar: page slider (right to left
  for manga) and "12 / 180" (a spread "12-13 / 180") with the percentage.
- **Progress:** `PageReaderProgress` (open at the saved page, the fresh-start snackbar, the
  two-positions dialog, `onPage` for every page that settles, a spread reporting its last page;
  `onActivity` for zoom and scrolling; `onPause`/`close`). The timer notice (`ReaderTimerNotice`) as
  in the EPUB reader.
- **Settings** (sheet: the web's panel): `container.readerSettings(ReaderSettingsSpecs.Cbx, fileId)`;
  changes are this comic's (`updateBook`, Reset = `resetBook`), "Use for all comics" writes them as
  the account's cbx default (`updateDefault`, then `resetBook`). Background colours are the web's
  fixed ones.
- **Next in series:** `GET series/:seriesId/books/:bookId/next?formatGroup=cbx` (the book's
  `seriesId`); the web's NextIssueCard over the last page (paged) or after the last page (strip).
  Read next replaces this reader with the next comic's (`ReaderRouter`), so Back returns to where the user
  came from; with auto-advance a turn past the last page opens it (by tap, volume key, or a swipe
  onward of 48dp caught by a `NestedScrollConnection` on the pager; half a second after the last
  page came up, paged mode only).
- Tests: `ComicsLogicTest` (spreads, tap zones, the CBZ page order), `LocalPageFetcherTest` (the
  disk cache, a page's size caps), `ComicMemoryCacheTest` (a closed comic's pages leave the memory
  cache, other comics' stay), `OfflineFilesTest` (the download's CBZ check),
  `ComicsStripTest` (a very tall page in the strips), `ComicsScreenshotTest` (`comics/reader`,
  `comics/settings`, dark).

**The PDF reader (`feature.pdf`).**
- **Engine: the platform `PdfRenderer`** (pdfium, in the OS; API 35 added text, search, links and
  password loading; on Android 12 to 14 the same features come as `PdfRendererPreV` with the PDF
  module's S extension 13, a Google Play system update, and without it the original renderer only
  draws pages: no search (the button is hidden), no links, and a protected PDF gets a message
  instead of the password prompt), not androidx.pdf: that library is still beta, its viewer is a Fragment with its
  own toolbar, search and scroller (it can't take the EPUB reader's chrome), and its document
  service runs over IPC; it would also be a new dependency. `PdfEngine` wraps one open document: the
  renderer isn't thread-safe and opens one page at a time, so every call runs in order on the
  document's own `Dispatchers.IO.limitedParallelism(1)`, and a call whose page scrolled away never
  starts; page sizes are measured 4 pages per call, so a render never waits long behind them. A
  renderer made as the reader closes, or replaced by a Retry, is closed, not left to the finalizer.
  Neither API exposes the outline, so `PdfOutline` reads it from the file itself (classic
  and stream cross-references, object streams, Flate + PNG predictors, the page tree, explicit,
  named and action destinations, PDFDocEncoding/UTF-16 titles; a damaged file is scanned for its
  objects). A crafted file can't make it fill the heap or run on: at most 500,000 cross-reference
  entries (a cross-reference stream with rows of no bytes is skipped), streams of at most 64 MB,
  64 MB of decoded object streams held at once and 256 MB decoded in all, no predictor row longer
  than its data, a million values in its arrays and dictionaries (`ValueBudget`: past that nothing
  more is read). An encrypted file, or one without an outline, gets page thumbnails instead.
- **The file (`PdfFiles`):** the downloaded copy when this exact file is kept offline
  (`Downloads.openOffline`), else `GET books/files/:fileId/serve` (the web reader's route, not
  `download`) streamed with `api.client` into `cacheDir/pdf/<account>/<fileId>.pdf` with progress;
  a cached copy is reused while a one-byte range request says the server's size is the same (and
  offline, or with no answer), and deleted when it says 403 or 404 (the refusal is shown); the last
  3 are kept. A truncated body or one without `%PDF-` isn't kept; a cached copy that won't open (or
  has no page) is deleted so Retry fetches it again.
- **Pages (`PdfViewer.kt`):** whole-page bitmaps at the page's size on screen (fit width or fit
  page, at zoom 1), in a byte-bounded LRU (`PageBitmapCache`, a quarter of the heap limit, 32..160
  MB), emptied once the app is in the background (`TrimInBackground`: `TRIM_MEMORY_BACKGROUND` and
  up, where Coil clears its caches too, not a screen lock's `UI_HIDDEN`; the pages on screen keep
  theirs, `PdfTrimTest`); a page is capped at 8 MP. `ContinuousPages` (default): a LazyColumn laid
  out at zoom 1 and drawn scaled, its viewport the screen height / zoom, with every gesture its own
  (2D pan, fling, pinch around the fingers, double tap 1x/2.5x, max 6x); a page is laid out at most
  250,000 px tall, or 60,000 px on a screen 8,191 px wide or more (`stripPageSize`: past Compose's
  layout limit a page of an extreme shape at fit width crashed), a taller one scaled down to that,
  narrower, keeping its shape (`PdfStripTest`). `PagedPages`: a HorizontalPager, each page
  zooming and panning on its own (the pager is held while zoomed; the outer quarters turn pages). When
  a zoomed view rests 160 ms, the visible part of each page is rendered again at screen resolution and
  drawn over the page (`PageDetail`), so text is sharp at any zoom without huge bitmaps. Links: a tap
  on a goto link jumps, a web/mail link opens in another app. Night mode draws the pages through an
  inverting colour matrix (white paper to #1E1E1E).
- **Chrome:** the EPUB reader's (top bar Back, title, Contents, Search, Settings; bottom bar with the
  outline chapter, the search's "3 of 27" with previous/next/clear, the page slider and "Page 12 of
  300"; hidden while reading, a tap toggles it, immersive; volume keys turn pages; the screen kept on
  for 10 minutes after the last page, as in every reader).
  Contents: Outline and Pages (thumbnails) tabs. Search: pdfium's `searchText` page by page (results
  stream in, 500 at most, a line of context from `textContents`), matches highlighted on the pages,
  a pick scrolls the match a third down the screen. Password: `PdfPasswordDialog`, the password kept
  in memory for that opening only. The two-positions prompt and the timer notice are the EPUB
  reader's (`PositionChoiceDialog`, `ReaderTimerNotice`).
- **Settings:** `PdfSettings.Spec` = the web's PDF spec with a vertical strip as the phone default:
  `scrollMode` vertical = continuous, page/horizontal = single pages; `zoomMode` fit-width = fit
  width, the others fit page; changes are this book's (`updateBook`, as the web reader does), with
  Reset and "Use for all PDFs" (`updateDefault` + `resetBook`, i.e. `reader/defaults/pdf`). Night
  mode has no web key: settings DataStore `pdf.night.<account>`, for every PDF.
- **Progress:** `PageReaderProgress`: `open(hasLocalCopy, jumpPage = route.page)` runs while the file
  downloads (offline with no copy on the phone, the reader fails before deciding; Retry decides
  again when the decision was made without the server and nothing was read since, as the EPUB
  reader does); the viewer opens at `startPage`; the page with the most of it on screen (the earlier of
  two that show as much, so a short page put at the top stays the current one; the first at
  the top, the last at the end) goes to `onPage`; gestures call `onActivity`; `onPause` on ON_PAUSE,
  `close()` in `onCleared`; `jumps` scroll the viewer.

## Sorting and filtering the book grids (`feature.library`)

Every book grid (All books, each library, smart scope and collection, an author's or a series' books)
has a sort action in its toolbar (`SortButton`, a dot while the list isn't in its default order). It
opens `SortSheet`: Sort by, the order in words for the field (A to Z, Newest first, Reading first...),
and Show: Reading only (reading, re-reading), Hide read (status `read`), Hide unread (status `unread`
or no status: the server reads a missing status as unread, and its filter does too). Changes apply at
once. While the list isn't at its default, `SortChips` shows under the toolbar (the sort, each
filter, and Clear, which restores the default in one tap). A search inside a list keeps its sort
and filters. Nothing matching with filters on shows why (both hide toggles on get their own
explanation) and Clear filters.
- **Remembered** on the device per list (`LibrarySortPrefs`, settings DataStore
  `library.sort.<account>.<list>`): each drawer list by its source key; all authors' pages share one,
  all series' pages another. Defaults: title A to Z; a series in series order. A stored field the
  list no longer offers falls back to the default.
- **All books, libraries, scopes, collections: the server sorts and filters** (the books query,
  `POST books/query`, `libraries/:id/books`, `smart-scopes/:id/books/query`,
  `collections/:id/books/query`; body `ListQueryBody` = sort, pagination, filter, q only). Sorts:
  title, author (+ title), series (+ series index, title), date added, last read, published (each
  with a title tie-break). The filter is one `readStatus` rule (`excludesAll` for the hide toggles,
  `includesAny` for Reading only); a smart scope ANDs it with its own filter.
- **Read status order.** The server can sort by `readStatus`, but by the id's spelling (abandoned,
  on_hold, read, reading...). So a read status sort is five queries run one after another
  (`querySegments`): reading + re-reading, on hold + want to read, unread (and no status), read +
  skimmed, abandoned (reversed for "Reading last"), each `includesAny` its statuses minus the hidden
  ones, sorted by `readStatus` (alphabetical is right within each segment) then title.
  `BookListLoader` pages through them in turn: page 0 also counts every segment (size 1, in
  parallel), so the total is exact and empty segments cost no further request; a page never spans
  two segments.
- **An author's or a series' books** use their own routes (`authors/:id/books`: title, added,
  published year; `series/:id/books`: series order, title, added), which take no filter. Those sorts
  page on the server as before; a filter or the read status sort loads the whole list (100 a
  request, at most 2000 books) in the route's order and filters it, or orders it by status (stable),
  on the phone, which is correct because nothing is missing.
- A status set on a book page since the list loaded hides the book at once when the filters leave it
  out (`PagedState.shownBy` with `ReadingChanges.statusOverrides`).
- Screenshots: `library/books_sorted`, `library/sort_sheet`, `library/books_filtered_empty`.

## Grid, list and the quick view (`feature.library`)

Every book grid (All books, each library, scope and collection, an author's or a series' books, and
Downloaded) shows its books as covers in a grid or as rows in a list, at the cover size the user picked.
- **The view** (`ListView`: the mode and `cellDp`, the chosen cell width; null is the old grid of
  120dp cells) is kept on the device **per list, as the sort is** (`LibraryViewPrefs`, settings
  DataStore `library.view.<account>.<list>`, the list named by `ListKind.prefsKey`: each drawer list
  its own, every author's page one, every series' page another; Downloaded `downloaded`). A width
  rather than a column count, so the covers keep their size when the phone turns
  (`GridColumns.count`: three across in portrait is six in landscape). Cells are 64..200dp: two to
  six columns on the phone in portrait, five to thirteen in landscape (`GridColumns.range`). It is
  read before the first page, so a list never shows in the other view for a moment.
- **The toolbar's view action** (`ViewMenuButton`, before Sort and Search; Downloaded has it too):
  the current view's icon, opening a menu with Grid / List and, in the grid, "Covers per row" as a
  − n + stepper (for anyone who doesn't pinch; `GridMetrics` hands it the grid's width).
- **Pinch** (`BookGridFrame` + `GridPinch`, grid view only): two fingers take the events before the
  grid and the covers (the Initial pass, consumed), so nothing scrolls or is tapped meanwhile. The
  pinch begins where the second finger lands, at the middle of every finger down (the user's fingers rarely
  land together, and `calculateCentroid` leaves out one just landed, which anchored the zoom on the
  first). The grid is drawn zoomed about the fingers (`point * scale + offset` on a layer read only
  while drawing: no recomposition between steps); `PinchSteps` steps a column when the covers have
  grown or shrunk three quarters of the way (in scale) to the next count, with a `SegmentTick`
  haptic, and measures what's left against the new layout, so the covers stay the size they were
  drawn at and don't step straight back. The book under the fingers keeps its place: its new top
  comes from its new height (`cellHeightAt`, `anchoredTop`) and is asked for with
  `requestScrollToItem` in the same frame as the new count. Lifting past half way (and toward it)
  takes the step; the zoom and offset then spring back together and the size is saved. A pinch
  during the spring back goes on from what is drawn (nothing jumps). The modifier is remembered
  once, so a recomposition never restarts a pinch. Rows don't pinch. `GridPinchTest` pinches a real
  grid with Robolectric's touch injection (bigger, smaller, a small pinch changes nothing, one
  finger still scrolls, nothing is tapped, the book stays under the fingers, also when the second
  finger lands later elsewhere); `GridPinchStateTest` the drawing between events (a second pinch
  during the spring back).
- **Covers are asked for at their size** (`BookGridItem(coverWidth)` → `BookCover(requestWidth)` →
  `sizedCoverRequest`): an explicit size (the request starts at composition, and a memory-cache hit
  is immediate), `Precision.INEXACT` (a bigger cached copy serves a smaller cell) and
  `placeholderMemoryCacheKey` = the memory key (a versioned URL is its own key; a downloaded cover's
  file carries its modification time), so covers that grow after a step show the old copy until the
  sharper one is decoded. The server's thumbnails are at most 400x600. Grid items have a
  `contentType`; the pager's prefetch is in rows, so it follows the column count.
- **Cells skip when a page arrives.** Each cell's cover model is remembered per card
  (`remember(book) { coverOf(book) }` in `BookGridContent`; the author tiles' portraits and the series
  cards' fanned covers likewise), so every pass hands the cells the same instance: the cells take it
  as `Any?`, compared by identity, and a new URL string (`Api.thumbnailUrl` builds one each call)
  made every cell on screen recompose on each of the pager's two emissions per page. An edited book
  is a new card (a new `updatedAt`), so its URL is rebuilt. `CellModelsTest`: each shown cell's
  model is looked up once, and the cells' bodies don't run again (counted through the Compose
  compiler's trace markers, `Composer.setTracer`), which also catches a click lambda that stops
  being the same instance.
- **The list** (`BookRow`, rows at least 340dp wide: one column on the phone, two in landscape): a
  56dp cover, the title (two lines), authors, series and number, the formats (`FormatChip`, the
  primary file's first, three at most), the status in its colour, the user's rating as stars
  (`BookCard.rating`: the card query joins the user's own rating) and the progress, on a card with the lg
  radius and a border.
- **The quick view** (a long press on a cover or row in any book grid, on Downloaded and on the
  Dashboard's shelf covers; the Dashboard's reorder long press stays on the card titles):
  `BookQuickViewModel` (one per screen) and `BookQuickViewSheet`, a bottom sheet with the cover,
  title, authors, series, the user's rating, the progress and four lines of the description, then Read or
  "Continue · 42%" ("Read PDF" when it isn't EPUB), Book page, the status (tap for the list of
  statuses) and the download. Its state holds the book page's own `BookDetailUiState`, filled as the
  page fills it (the downloaded copy's page first, then `GET books/:id`), so Read opens the file and
  reader the page's Read would (`readTarget`: the kept CBZ, offline the copy) and the download button
  says and asks what the page's does (Download at once, asking for the notification permission;
  Stop, Update and Remove download confirmed). A status is set as the book page sets it: at once,
  `PATCH books/:id/status` from the app scope, `ReadingChanges.overrideStatus`, the downloaded copy's
  page, `tracking.changedElsewhere`; put back, with a line in the sheet, if refused. Downloaded's old
  long-press menu (Read, Book page, Remove download) is this now.
- Screenshots (light and dark): `library/view_grid_2`, `library/view_grid_5`, `library/view_list`
  (+ `_land`), `library/view_menu`, `library/quick_view`, `library/quick_view_statuses`,
  `library/quick_view_failed`, `downloads/downloads_list`, `downloads/downloads_grid_4`.

## Highlights and notes (`feature.notes`)

BookOrbit's annotations (web, KOReader and Kobo highlights; `annotation.controller.ts`, `annotation-hub.controller.ts`), read and edited on the phone. Highlights are made in the reader (see "Reader annotations"); these screens read and edit them.
- **Book page:** `BookDetailContent(highlights = { BookHighlightsSection(bookId, onOpen) })` under the files: a DashCard with the count, the colours as one bar with a legend, notes and chapters, the three newest (`books/:id/annotations?page=1&pageSize=3&sortBy=createdAt`). It opens BookHighlights.
- **BookHighlights:** the book's highlights by position, 100 a page, grouped by chapter (`NotesLogic.chapterGroups`; counts from the server's `chapterBreakdown`), each a card: style tile in its colour, text, the user's note (edited in place: `PATCH {note}`, blank sends `null`), the colour and style panel (`PATCH {color}` / `{style}`; text can't change), open in the reader (the jump file when a reader here opens it: a CFI in the foliate reader, a page in the PDF reader; `ReaderRouter.annotation`), share as image, a quote's kept photo (only when the file is on this phone), like, delete (confirmed; `DELETE` moves it to the web's trash). Edits show at once and roll back on failure (only the fields that edit changed; a failed delete puts back only that highlight; one edit per highlight at a time; a failed note reopens the editor with the user's text). Edits and deletes run in the app scope, so leaving the screen doesn't cancel them. Toolbar: Memorize and Export (`annotations/export?bookId=&format=md`, shared as a `.md` file, and as text when short).
- **Notes (root):** `GET annotations` newest first, 30 a page, with the shell's search (`search=`), and chips for book (`annotations/books`), colour (`annotations/overview` colours), with notes (`hasNote=true`) and Liked. Random note: a random index of the filtered total fetched as a one-item page (`RandomNotes`, no repeats until all were shown), in a bottom sheet with Another. Memorize: the same source, one note at a time, tap for the next (fetched ahead; a failed prefetch is fetched again on the tap, a failure shows Retry, and "none yet" only when the total is 0); each tap counts a review, written to the settings key every 10 and on leaving. Liked highlights are kept per book for the process (`LikedNotesCache`, one account): the Liked filter's chips and search filter on the phone, and its random note and Memorize share the list; a book is fetched again after a change to it, when it holds a new like, or on a pull (four books at a time).
- **App settings (`AppSettings.notes`, `NotesPrefs`):** `liked` (annotation id -> book id, so the Liked filter asks only those books through the hub; a liked id its book no longer has is looked for in the web's trash (`status=trashed`), where it keeps its like, and loses it only when in neither) and `reviews` (id -> count); at most 400 entries each: a 401st like is refused with a snackbar, and past 400 reviews the least reviewed go first (never the one just reviewed).
- **Share as image (`QuoteCardDialog`):** a Compose card laid out at 360dp (quote mark in the highlight's colour, the text in the serif shrinking to fit up to S/M/L, the user's note if asked, the book and the Ottershelf badge and wordmark) on the theme's page colour, a gradient from the cover's `CoverTint`, or the cover blurred (`CoverBlur`, 36x54 box blur), 1:1, 4:5 or 9:16. It is recorded into a `GraphicsLayer` at 1080 px wide (drawn again at that scale, so text stays sharp), then shared (`ACTION_SEND` through the FileProvider `<applicationId>.notes.share`, `cacheDir/notes-share`) or saved to Pictures/BookOrbit.
- `NoteChanges` (`NoteChange(bookId, origin)` on a SharedFlow) makes the book page's card, BookHighlights and Notes reload after a change to a book they show made on another of them, a quote added, or highlights synced from the reader (`ReaderNotes` / `ReaderNotesWorker` call it with `readingChanges.changed()`; bookmarks don't count). A screen skips its own changes (`origin`); BookHighlights and Notes reload the pages they had loaded, keeping the user's place, and the card's loads replace each other.

## The Dashboard (`feature.home`)

The web dashboard (client/src/features/dashboard, views/DashboardView.vue) in the Nexus layout.
- **Widgets:** all twelve web widgets as DashCards of one height (244dp): Currently Reading and Reading Streak (the Nexus cards, timer bar and buttons kept), Reading Goal (ring; Set goal and the pencil open ReadingGoals), Reading DNA, Monthly Challenge, Highlight of the Day, Neglected Gems (Add to queue = want to read, only from unread, no status or abandoned: the book's status is read first, and a book on Currently Reading isn't offered it; Shuffle), Reading Rhythm (14 columns, tap or drag for a day's readout), Diversity Score, Library Overview (tiles open All books, Authors, Series), Year Projection, The Long Wait (Start Reading opens the file's reader when one here opens its format, else the book). Which is the account's `dashboardConfig.widgets` (the web's defaults and normalisation, `model/DashboardModels.kt`). Portrait: one per row, two short (web `1x1`) neighbours side by side; landscape: two columns; a shelf between widgets has a row of its own (`cardRows`). Stacked on a row of its own, the Reading Streak is as tall as its content (the Nexus `arrange`) and Currently Reading shrinks to its books, up to the 244dp and scrolling beyond (`rowWrapsContent`); side by side both have the 244dp. The list is a LazyColumn of rows keyed by their first card.
- **One order of cards** (`model/DashboardOrder.kt`, `DashboardOrderTest`): widgets and shelves interleaved in any order. The widgets' order among themselves is `dashboardConfig.widgets` (so the web shows the same order, and a reorder there shows here); the whole order, hidden cards and shelves included, is the app settings key `AppSettings.dashboardOrder` (`"w:<type>"`, `"s:<shelf id>"`), so it survives a new phone. `cardOrder` reads them together: the saved order says which places are widgets and which shelves, the widget places take `dashboardConfig`'s order, the shelf places keep the saved order; a card it doesn't know gets a place at the end, one that's gone drops out.
- **Arranging** (`HomeViewModel.startEditing` / `arrange` / `finishEditing`; `Reorder.kt`): a long press on a card's title (haptic) turns the Dashboard into its cards' titles with drag handles and "Done" in the toolbar, the pressed card kept where it was on screen. The handle drags at once, a long press on the title picks a card up; it lifts (card colour, shadow, accent edge), follows the finger as a layer translation, the others make room with `animateItem`, a tick on pick-up, each move and the drop, and the list scrolls when it's held near an edge (`ReorderState`: moves when the middles cross, waits for the next layout after each move). Move up / down are accessibility actions. Each drop saves the whole order in the settings key (`arranged`: hidden cards stay after the card of their kind they followed); Done, Back or leaving the Dashboard writes the widgets' new order, only if it changed, into the whole `dashboardConfig` read from `auth/me` (flags and the web's own widgets as the server has them now, in the app scope). If that fails, the order is put back and a snackbar says so.
- **Shelves:** Continue Reading, Want to Read, Up Next in Series, Discover Something New, Smart Scope shelves and Recently Added (no listening shelf), 1..3 rows (two at most below 640dp, the web's `sm`), books per row, at most 8; kept on the device (`DataStoreShelfPrefs`, settings DataStore key `home.shelves.<account>`), the web's defaults (Recently Added, Discover, Continue Reading on). Scope shelves of deleted scopes are dropped when first loaded. The Currently Reading list and every shelf (`rememberDashboardListState`, `DashboardListStateTest`) go back to their first item when a load brings other books (a lazy list otherwise keeps its place on the old first one, hiding new ones in front), unless the user dragged it and left it away from the start; shelves are keyed by id, so each keeps its own.
- **Loading (`HomeViewModel`):** one `dashboard/widgets/batch` for the enabled widgets and one `dashboard/scrollers/batch` for the enabled shelves, with the Nexus freshness rules (`ReadingChanges`, settle, `dashboard/refresh` only after a change here) except for staleness: a return reloads only when something changed here or five minutes after the last complete load (the server keeps its quick widgets two minutes and the others five; the Nexus reloaded after one). A widget or shelf the server failed shows its own tap-to-retry card. Charts are Canvas (`WidgetCharts.kt`, one accent series each, drawWithCache). The layout follows the cached user: a pull, or a return after those five minutes, reads `auth/me` again, and a widget newly enabled (or another library scope) loads at once, after a load under way if there is one.
- **Customise** (toolbar, `CustomiseSheet`, its own `HomeViewModel.customise` flow): Shelves and Widgets tabs (each list dragged by its handles, the same `ReorderState`; the shelves in the Dashboard's order), library scope, Reset / Cancel / Save. Opening reads `auth/me`, and a part not yet changed in the sheet takes the server's. Save keeps where widgets and shelves sit among each other, each list in the sheet's new order (`cardOrder(..., shelvesFollowList = true)`); an added shelf goes at the end. Save writes the shelves on the device and, for each of the widgets and the scope that changed from what the sheet opened on, reads the whole `dashboardConfig` from `auth/me` and writes it back whole with `PATCH users/me/settings`: readingGoal, unknown keys, widgets of types this app doesn't know (in their slots) and a part left alone are kept as the server has them. Then it updates the cached user and reloads. The scope counts every library (a podcast library saved on the web is kept) but offers only book libraries; if they can't be read, the scope stays as it is and a tap retries. The sheet can't be dismissed while a Save is under way.

## Reader annotations (`feature.reader.annotations`)

The web reader's highlights, notes, bookmarks and search (client/src/features/reader/epub: SelectionPopup, useAnnotations, useFoliateAnnotations, useFoliateSelection, useBookmarks, useSearch, NoteDialog) in the Nexus reader.
- **Selection:** `SelectionWebView` empties the WebView's floating selection menu (the handles stay). reader.js reports a selection 350 ms after it settles (`onSelection`: text, the CFI foliate makes with `view.getCFI`, the chapter, the rect in page px = dp) and `onSelectionCleared` while it changes. `SelectionPopupHost` places the popup above it (below, clear of the handles, when there's no room) in the page's cutout-padded area. The popup: the web's ten colours (a tap highlights with the chosen style, or recolours), the four styles, Note (`NoteDialog`), Look up (see "Looking words up"), Copy, Share (quote + title + authors) and, on a highlight, Delete (confirmed; with Look up too the popup widens by one button, the colours spread out; on a page too narrow for that row, under about 364dp: a larger Display size, split screen, the buttons get a row of their own under the styles, so Delete is never squeezed off: `SelectionPopupLayoutTest`). Selecting exactly an existing highlight's span (`Cfi.sameRange`) or tapping it (foliate's `show-annotation`; the tap doesn't turn the page) opens it for editing; the tap that clears a selection doesn't turn the page either.
- **Drawing:** the app hands reader.js the whole list (`readerSetAnnotations`: cfi, hex colour, style); each section draws its own through foliate's overlayer when its overlayer is created, with the web's draw functions (a plain 32% wash instead of multiply on dark pages). Colour names (`yellow`, KOReader's) become the app's hex (`displayHex`).
- **Data:** `ApiNotesRemote` over `Api.send` (bodies exactly the DTOs'; annotations in one unpaged `GET books/:id/annotations`, as the web reader loads them: only that route runs the server's KOReader-to-CFI backfill, a bounded batch per call). `ReaderNotes` (one per open book) sends every write through the device queue (`ReaderNotesStore`: `files/reader-notes/<account>/<bookId>.json` holds the server's lists as last fetched plus the waiting `NoteOp`s), so local ones show at once (negative ids) and the stored lists show offline. `NotesSync` sends oldest first: offline, 5xx, 401, 408, 429 stop the pass; another 4xx drops that write (snackbar); a 404 on delete is done. An annotation create is marked attempted before its send and, if attempted, looked for (same cfi and text, an id this device didn't have) before it is sent again; bookmarks are retry-safe on the server. Sent at once when online, when the connection comes back while reading, and by `ReaderNotesWorker` (unique work `reader-notes-flush`, CONNECTED, backoff; a write schedules it only when offline, or when the send at once stops or throws: if the process dies during that send, the write goes when the app next shows a screen, at a sign-in, or when that book opens again, instead of about 15 s later; `NotesSync.flushAll` lists the waiting books again after each pass, and the worker retries while any queue is left, unless its account has signed out). `ReaderNotesScheduler.start` (at the first activity's creation, `AppContainer.start`'s `onActivityCreated`: finding them reads every book file of the account, which a worker's or boot's process needn't do) schedules it at the first screen and after every sign-in when that account has writes waiting; sign-out cancels it (`ReaderNotesScheduler.cancel`), and `NotesSync` asks `AppContainer.stillSignedInAs(account)` (the credentials' generation and the account key unchanged since the pass began) before every request, so a pass stops at its next request once its account signs out, and keeps no lists fetched after that (see "Security and privacy" for a request already on its way). Local-to-server ids stay in the book's file while the item is on the server's lists or a waiting write names it, so a dialog or popup still holding a local id reaches the server's. Every pass that changed something calls `readingChanges.changed()`.
- **Bookmarks:** the top bar's toggle marks the page on screen (a bookmark whose start lies in the page's CFI range counts, `Cfi.contains`); title = the chapter, else the percentage. A bookmarked page shows a ribbon at the top.
- **Sheet** (top bar, next to Contents): Highlights (grouped by chapter in reading order, colour filter, tap to jump, notes and "waiting to sync"), Bookmarks (tap to jump, swipe or long press to delete), Search (foliate's search.js over the whole book, results in batches with their context, foliate outlines the matches in the page until cleared).

## Looking words up (`feature.reader.lookup`)

The selection popup's Look up (every foliate format: EPUB, KEPUB, MOBI, AZW3, AZW, FB2) opens a bottom sheet for the selected word or phrase, like the web reader's DictionaryPopover but on Wiktionary and Wikipedia and with the phone's own text apps. `LookupViewModel` (its own ViewModel on the reader's navigation entry, made in `ReaderScreen`) holds the open sheet (`LookupUiState`, null while closed) and a `LookupRepository`, whose answers are kept in memory for the reading session. Strings: `strings_reader_lookup.xml` (`reader_lookup_*`).
- **The selection** (`LookupText`, pure): whitespace collapsed, invisible characters dropped, composed (NFC); the term is that without the punctuation and quotes around it (trimmed by code point; the marks on the last letter, a Hindi or Thai vowel sign or tone mark, stay). Up to five words: Dictionary and Wikipedia tabs (Dictionary first); six to twelve: Wikipedia only; more: nothing is sent (the apps still get the passage). In scripts written without spaces (Chinese, Japanese, Thai, Lao, Khmer, Myanmar) a run of letters counts as several words (a Han character one, two kana, three of the others), so a sentence there isn't one word sent whole.
- **The book's language:** the downloaded book page (`Downloads.detail`), else `GET books/:id` to the user's own server (5 s), once per session, one read shared by both parts of a lookup; normalised like the web's `normalizeLang` (`eng`, `English`, `en-GB`, `fre`, `Français`, `Bokmål`...), unknown is English (asked again at a lookup a minute later, or once the connection comes back).
- **Dictionary** (`en.wiktionary.org/api/rest_v1/page/definition/{term}`: every language's entries, English glosses, keyed by language code or `other`): the term, else lower case, else simple English inflections of a single word (-s, -es, -ies, 's, -ed, -ied, -ing, -er, -est, doubled consonants, a silent e; tried together). A page counts when it has the book's language or English (`WikimediaParsing.preferred`; Translingual left out; the book's language also by Wiktionary's own code or name for it: Croatian, Serbian and Bosnian as Serbo-Croatian, Norwegian as Norwegian Bokmål or Nynorsk, which the answer may file under `other`), shown in that order; a capitalised word that is only a proper noun there (`Running`, a surname) also gets the lower case word, shown first; a page with neither is the last resort (its first two languages). A part of speech appears once (Wiktionary splits them by etymology), four definitions each with one example (and its translation) and "N more on Wiktionary"; a definition that is only a form of another word (Wiktionary's `form-of-definition-link`: "simple past of walk") brings that word's senses under its own headword, at most two such words, as the web does. Definitions are Wikimedia HTML shown as text with bold and italic only (`HtmlText`: links become their text, `<style>`/`<script>` dropped, entities decoded), never in a WebView. "Open in Wiktionary" goes to the page at the first language's section.
- **Wikipedia** (`{lang}.wikipedia.org/api/rest_v1/page/summary/{title}`): the book language's Wikipedia when it has a sizeable one, then English; a title not found is searched (`w/rest.php/v1/search/title`) and taken only when the hit's title is the same but for case (the search offers near misses: Lestrade finds Inspector Lestrade). Title, description, the extract (bold and italic kept), a thumbnail (Wikimedia hosts only), a note on disambiguation pages, "Open in Wikipedia" (the answer's link only when it is https on a Wikimedia host).
- **Apps** (`TextApps`, the manifest's `<queries>`): one button per `ACTION_PROCESS_TEXT` activity (icon and label from the package manager; the text sent with `EXTRA_PROCESS_TEXT_READONLY`), then `ACTION_TRANSLATE` handlers of apps without one; this app itself never; none installed, no row. They work whatever the tabs show.
- **States:** loading (skeleton), not found (Try Wikipedia, Search Wiktionary / Search Wikipedia), offline or failed (Retry; the apps still work; a part that failed offline is asked again when the connection comes back), a passage too long to send. Only failures are asked again; answers, found or not, are remembered. "Not found" is only a 404 or 410 (no such page) or a 400 or 414 (a title the API won't take); a 403 (Wikimedia turning the app away), a 429 or a 5xx is a failure (Retry), never a remembered "no entry". A word asked for that failed while another found only a proper noun or another language fails the lookup; an answer shown while a request it could have used failed (the proper noun beside it, a word it is a form of) isn't remembered.
- **Network (`LookupHttp`):** its own `OkHttpClient` from a bare builder, never `Api.client` or a `newBuilder()` of it, so the bearer token, the refresh authenticator and anything else of the account can't reach it; no cookie jar, no cache. `WikimediaGuard` runs as an application and a network interceptor (so on every redirect hop): https only, hosts `wikipedia.org`, `wiktionary.org`, `wikimedia.org` and their subdomains compared on the parsed host (no user info; the BookOrbit server and look-alikes are refused before anything is sent), `Authorization`/`Cookie` headers removed, and the User-Agent Wikimedia's API policy asks for (`BookOrbitModern/<versionName> (Android; personal reading app) okhttp/<version>`). Timeouts 6 s connect, 8 s read, 12 s a call. The lookups run on `Dispatchers.Default` (`LookupViewModel.work`), so the answers (large pages for common words) are parsed off the main thread. Thumbnails load through `LookupImages`, a Coil loader over that client (no service-loaded fetchers, 8 MB memory cache, no disk cache), never the app's image loader. Tests: `LookupTextTest`, `WikimediaParsingTest` and `LookupRepositoryTest` (answers captured with curl in `src/test/resources/lookup`), `LookupHttpTest`, `LookupViewModelTest`, `TextAppsTest`, `LookupScreenshotTest` (`reader/lookup_*`: the sheet's states and the popup, light and dark).

## Quotes (`feature.quotes`)

A quote typed in, or read off a photographed page, saved as a BookOrbit annotation (so it shows in Notes, on the book page, on the web and in exports). Entry points: the book page's "Add quote" / "Scan a page" buttons under the Highlights card (`QuoteButtons`, the book fixed), and the Notes toolbar's quote action (`AddQuoteAction`: the book the feed is filtered to, else the user picks one: Currently Reading first, or the library searched with `books/query`).
- **Form:** the book, the quote (multi-line, serif), page from/to (stored in `chapterTitle` as "p. 45" or "p. 45-47"), the user's thought (the annotation `note`), one of the web's ten colours. Save posts `POST books/:bookId/annotations {cfi, text, color, note?, chapterTitle?}` (exactly `CreateAnnotationDto`; no `bookFileId`, style left to the server's default). A save whose answer never came is remembered (`LostSave`: its book and body), and the retry first looks for its CFI among that book's ten newest annotations (`RetryStep`): not there, it is sent again; there as the form is now, done; only the thought or colour changed since, `PATCH {note, color}`; the text, page or book changed (none editable), the arrived one goes to the web's trash and the form is sent with a new CFI. A 4xx on the create starts afresh. Then `NoteChanges.changed(bookId)` and back. Back is blocked while saving. The form, the picked book, the work photo and a lost save are kept in the `SavedStateHandle`, so they survive the process being killed (in the Photo Picker, or away). The text can't be edited afterwards (server rule); note, colour and style can, on the Highlights screen.
- **Scan:** "Scan a page" asks for CAMERA only then, after a rationale dialog (Continue, or Choose a photo instead); a denial offers the Photo Picker or the app's settings. CameraX (`camera-compose` `CameraXViewfinder`, `ImageCapture` to `cacheDir/quotes/`, bound with a `SessionConfig` with auto rotation, so the capture follows how the phone is held), or the Photo Picker (copied into the same folder). A capture or failure that lands after the camera closed doesn't reopen the scan. Work photos go when Retake, another photo or closing replaces them, when the screen closes, and (older than a day) when it opens. `PageReader` decodes it upright (ImageDecoder applies EXIF) at most 2400 px, and Tesseract 5 finds the lines (Tesseract4Android 4.9.0, the standard single-threaded build, all native code in the APK: the photo and its text stay on the phone). Its model is the English tessdata_fast `eng.traineddata` in the assets (4.1 MB, 2.0 MB compressed in the APK), which `Tessdata` copies once to `noBackupFilesDir/tessdata/` (Tesseract reads a file path), again when it is missing or its size isn't `Tessdata.SIZE` (`OcrLayoutTest` checks the constant against the asset), through a `.part` file renamed when whole. Each page gets its own engine on Dispatchers.Default, recycled once the page is read (loading the model is a fraction of the reading, and its native memory isn't kept while the user picks): `PSM_AUTO` (the wrapper's default is one block), LSTM only, Sauvola thresholding (`thresholding_method` 2: a shadow across the page or the gutter doesn't swallow its lines), no inverted retries; the recognition is `getHOCRText`, the one call Tesseract can stop, and cancelling the coroutine (the screen closed) stops it from another thread, the engine recycled only after. The result iterator's text lines, with `RIL_PARA` starts, become `OcrLine`s through `OcrLayout.lines`: each paragraph is a block, lines numbered in it, text trimmed, lines with no letter or digit (specks, the gutter) dropped; boxes are in the bitmap's pixels. The picker shows every line boxed over the photo (its gestures keyed on the scale, so a rotation keeps taps on the right line): tap, or drag across lines, to keep them; All/None; "Use N lines" appends `QuoteCleanup.join` to the quote (words broken at a line end joined, the hyphen kept before a capital; line breaks become spaces; a new block after a sentence end starts a new line; ligatures, spacing).
- **Photos:** kept only when the user turns on "Keep the photo on this phone": moved to `files/quote-photos/<account>/<annotationId>.jpg` and linked in the settings key (`AppSettings.quotePhotos`, annotation id -> file name, at most 400; the photos of links dropped past that are deleted). The key lives on the server, so a link is read or deleted only when its name is exactly `<annotationId>.jpg` and the file lies directly in that folder (`QuotePhotos.linkedFile`, canonical path checked): a crafted `../` name can't reach the rest of the app's storage. Otherwise deleted after the save, and when the screen closes unsaved. The photo dialog's Remove (asked first) deletes one from the phone. A kept photo opens full screen (`QuotePhotoDialog`) from the quote's card on the Highlights screen and in Notes (the ScanLine action, `HighlightActions.onViewPhoto`); the card offers it only when the file is on this phone (`QuotePhotos.onThisPhone`: the link travels with the account, the photo doesn't). Deleting a quote leaves its photo (the server keeps the annotation in the web's trash, so it can come back).
- **The placeholder position (`QuotePosition`).** The server requires exactly one of `cfi` or `pdf` and doesn't validate the CFI; it has no position-less status (`positionStatus` is exact/repaired/failed/pending, and a created CFI row is always `exact`). A quote gets `epubcfi(!/4/2[bookorbit-quote-<8 hex>]/1:0)`: no step before the `!`, a unique marker. Checked against the BookOrbit source:
  - *Web reader* (`public/assets/foliate`, `useFoliateAnnotations.ts`, `ReaderView.vue`): `addAnnotations` calls `view.addAnnotation` without awaiting; `resolveNavigation` catches the TypeError `resolveCFI` throws on the empty spine part and returns undefined, so nothing is drawn (a rejected promise in the console, nothing else). A jump from the sidebar, the Highlights tab or a hub link (`goTo`) fails inside foliate's own try and the reader stays where it was. The sidebar's labels and ordering (`utils.ts`, regex-based) and `getLocationContext` (try/catch) cope; the chapter shown is the `chapterTitle` ("p. 45"). A spec-valid CFI pointing outside the spine (e.g. `/6/99998!...`) was rejected: it resolves to section -1, and `View.goTo`'s "skipped unreadable section" fallback then opens section 0, moving the reader, and the position it saves, to the start of the book.
  - *Server conversions*: `cfiToXpointer` (KOReader) and `canonicalToKoboSpan` (Kobo) stop at `missing_spine_step` before opening the EPUB; the exchange stores a failed xpointer / kobo_span row and skips the quote (`skippedNoPosition`), not retried until a converter upgrade. The hub's "needs review" counts only CFI rows that aren't `exact`, so quotes aren't counted. `ensureCfiPositionsForBook` only converts device rows. Position sorting is a plain string sort (quotes come first).
  - *KOReader plugin*: it only receives entries with a usable xpointer, so it never sees a quote. Limit: KOReader add candidates are paged 100 at a time by id and unpushable ones stay candidates, so a book with 100 or more quotes (older than its newer highlights) would stall pushing newer web highlights to KOReader.
  - *This app*: the reader leaves quotes out (`ReaderViewModel.onNotes` filters `QuotePosition.isQuote`), so they are neither listed in its sheet nor handed to reader.js (were one to get through, `Cfi.parse` returns null and reader.js' draw attempt fails silently); the Highlights screen and cards hide "Open in reader" for quotes (`QuotePosition.isQuote`, in `BookHighlightsUiState.readerFile` and `canOpenInReader`).

## Next in series (`feature.seriesnext`)

When the user finishes a series book, the next one the user hasn't read is offered: on the finish celebration, on the book page, on the timer's result and on the foliate reader's last page. It only reads: opening the next book is the user's choice, and the reader's own rule applies (opening without moving records nothing). The comics reader keeps its own next issue (see "The comics reader").
- **The choice (`SeriesNext.pick`, pure, `SeriesNextTest`).** Worked out on the phone, because the server's `series/:id/books/:bookId/next` (the web comics reader's handoff) returns the immediate neighbour with a readable file even when the user has read it, and counts another copy of the same number as next. Numbers compare as the server orders them (`SeriesNumber`: series-index.ts `compareSeriesIndices`, so `2.10` follows `2.9`; leading zeros and an all-zero fraction don't count). Books on one number are copies of one volume (formats, editions, two libraries): a volume is read when any copy is, the finished book's copies share its volume. Next = the volume with the smallest number above this book's that the user hasn't read (read or skimmed; gaps stepped over); of its copies, one a reader here opens, then one the user is reading, then series order. Books without a number are never offered and a book without one has no next. Nothing after it and every volume read (2 or more): "You've read all 6 in The Oz Books"; otherwise nothing shows. The finished book counts as read whatever its status says (the reader's last page may come before the server's finish threshold).
- **Data (`SeriesNextLoader`, `SeriesNextLoaderTest`).** `GET series/:id/books?sort=seriesIndex&order=asc`, 100 a page, at most 20 pages (the book's `seriesId`; each card's contextual `seriesIndex`, the user's status as the list says it, and for the timer's result the book's own from `GET books/:id`: always the fetched one, never `ReadingChanges`' overrides, which only hold what a screen last saw and miss a timed session or another device; its files: `BookFormats.pickFile` is what Read opens, none means Details only). A book whose files are missing from the server's disk (`BookCard.status` not `present`; the server's own next-book query leaves those out) isn't readable: a present copy of the volume wins, and as its only copy it gets Details only. Read opens a CBR or CB7 comic's kept CBZ instead (`BookFormats.readsKeptCopyInstead`, the copy looked up with `Downloads.get` off the main thread), as the book page and the Dashboard do. The series' name is the book page's, else `seriesInfo.name`.
- **The user's switch:** Settings > Reading > "Suggest the next in a series" (`AppSettings.nextInSeries`, on the account, on by default). Off, `SeriesNextViewModel` shows nothing on every screen below and asks the server nothing; switched back on while a screen is up, it asks what that screen last wanted (from nothing shown, not the answer from before). It also hides the comics reader's next-issue card (`ComicsUiState.suggestNext`), except in paged mode with auto-advance on, where a turn past the last page opens that issue and the card says so. A field an older app's key lacked counts as its default in the settings merge (`AppSettingsRepository.flush`), so another device's untouched default can't switch it back on.
- **Where** (`SeriesNextViewModel` per screen, keyed by book; nothing while offline; asked again when the screen shows again, at most every 5 s):
  - *Book page* (`rememberSeriesNext`): when the user's status is read or skimmed, or the finish flow is on, `NextInSeriesRow` under the Read/status/download buttons (`BookDetailContent(seriesNext = { modifier -> ... })`): a DashCard with the cover, "Next in series", title, "Book 4 of The Oz Books", Read and Details (pushed), or the "all read" line.
  - *Finish celebration*: `FinishCelebrationContent(next = { NextInSeriesCard(...) })` on the first step, between the message and Continue. Read or Details ends the flow first (`celebrationLeave`: the achievement claim still runs, and what it brings goes to the shell's celebration, `handOverClaim`, not to the page left under the next book; `FinishClaims`, `FinishClaimsTest`).
  - *Timer result* (`rememberSeriesNextAfterSession`): once the session was sent and `GET books/:id` says read (a timed session that reaches the library's finish threshold sets it on the server, synchronously), the same card above Done; Read and Details replace the result.
  - *Reader* (`EndOfBookCard` in `ReaderContent`'s overlay): looked up from 90% through the book (or at its end, if that comes sooner), shown at the end of the book as reader.js reports it (`ReaderUiState.bookEnd`, see "The foliate reader's layout": the last page, the bottom of the last section, a fixed-layout book's last page on screen; never a fraction threshold, which showed the card over the last pages of a long book and never at the bottom of a short scrolled one), over the bottom of the page (above the bottom bar when it shows), with a close button (hidden until the user comes back to the end). Read and Details replace the reader (the next book's reader through `ReaderRouter`, or its page), so no reader is left under another; the reader leaving during the crossfade doesn't bring the system bars back over the next one (`ui.components.ImmersiveSystemBars`, used by all three readers).
- Screenshots: `seriesnext/celebration`, `celebration_all_read`, `book_page`, `book_page_all_read`, `book_page_details_only`, `timer_result`, `reader_end`, `reader_end_all_read` (`SeriesNextScreenshotTest`).

## Editing a book's details (`feature.bookedit`)

A light editor for the four things the user fixes most, not the web's full EditMetadataTab: the title,
the authors, the series and its number, and the cover. Strings: `strings_bookedit.xml`.
- **Who:** the book page's toolbar pencil (`BookDetailUiState.canEdit`) shows only for an account
  with `library_edit_metadata` (`canEditDetails`: `AuthUser.can`, superusers too), the permission the
  server checks on `PATCH books/:id/metadata` and every cover route (the web's
  `hasPermission('library_edit_metadata')`). It pushes BookEdit(bookId), which loads the book fresh
  (values and locks as the server has them now; offline: a retry).
- **Fields:** the title (one line that wraps, trimmed, 1000 at most, can't be emptied); authors as
  chips in order (the first stays first; removable; added from the box below, which suggests the
  library's authors as the user types: `GET authors?q=&size=8&sort=relevance&order=desc`, best match
  first, an exact name moved up, with each one's book count; "Add <name>" last for a name the library
  doesn't have; a pasted "A, B; C" becomes chips, as the web's ChipInput; a name left in the box is
  saved too); the series (the library's series suggested from `GET series?q=&sort=relevance`, which
  matches series names and their authors, with size and authors; a name it doesn't know says Save adds
  a new series; X takes the book out of the series, which clears the number); the number, only with a
  series, checked as the server does (`^\d+(\.\d+)?$`, 20 at most; a decimal comma is read as a
  point). The web's editor suggests from `metadata/authors` and `metadata/series` instead, which are
  alphabetical, without counts; the list endpoints rank by relevance and give counts.
- **Suggestions** (`SuggestionSearch`): 250 ms after the last keystroke, one request at a time (a
  newer query cancels the older coroutine, and with it its OkHttp call), and an answer is taken only
  while the box still says its query; the last answer stays up while the next is coming; blank asks
  nothing; a failure shows nothing (typing on asks again). Lists show under their box while it has
  the focus, brought into view above the keyboard.
- **Save:** one `PATCH books/:id/metadata` with only what changed (`MetadataChanges.body()`, exactly
  UpdateBookMetadataDto's fields): `title`, `authors` (names; the server finds or creates each, in
  order), and `seriesName` + `seriesIndex` (a string), both whenever either changed; null for both
  takes the book out of its primary series and keeps any other series it is in (the server's
  `syncPrimaryFromMetadata`). No `syncFileWrite`: the server answers with the book and writes the
  file later. The web's editor sends `metadata-and-locks` with the whole lock list on every save,
  because it edits locks in the same form; this one doesn't (see locks), so the plain route is used,
  which also refuses (409) a field someone locked meanwhile rather than overwriting the lock. Back
  with unsaved changes asks to discard; Back waits while saving. A failure keeps the form with a line:
  offline, 403 ("your account isn't allowed"), the server's own words for a 4xx (validation, "Metadata
  fields are locked: title": the locks are read again, the locked field shows locked with its value,
  and the rest can be saved). The form, its baseline and the author box survive process death
  (SavedStateHandle); a photo picked while the process was killed comes back to a screen that is
  loading the book again, and waits for it (then shows to confirm, unless the cover is locked). A line under Save says changes are for everyone who uses the library.
- **Locks:** as on the web, editing doesn't lock anything. A field the server has locked
  (`BookDetail.lockedFields`: `title`, `authors`, `seriesName`/`seriesIndex` together, `cover`) shows
  locked and disabled with its value and isn't sent; Unlock (asked first; the web offers the same
  toggle) reads the lock list fresh and writes it back without those fields at once
  (`PATCH books/:id/metadata-locks {lockedFields}`), so a lock added meanwhile stays. Locking isn't
  offered.
- **The cover** changes at once, each its own call, after a confirmation showing it: a photo from the
  Photo Picker (no storage permission), a photo taken with CameraX (CAMERA asked for with a rationale;
  the shot is cut to the 2:3 guide frame when the photo and the viewfinder face the same way,
  `CameraCrop`), or a cover found online (`GET books/cover/search?title=&author=&isAudiobook=&provider=`,
  SearchCoversQueryDto; the user's default source from `user-preferences/cover-search`, chips for DuckDuckGo,
  iTunes and All; previews only through the server's `books/cover/proxy`; then
  `POST books/:id/cover/from-url {url}`). Photos are decoded upright (ImageDecoder, EXIF, HEIC), at
  most 1600 px on the long side, JPEG 90 on white (`CoverImage`), in `cacheDir/bookedit/` (deleted
  after use, older than a day at the next open), and sent as `POST books/:id/cover` multipart with one
  file part `file` (`image/jpeg`; the server takes the first file, image types, 20 MB at most). "Use
  the file's cover": for a cover the user added, `DELETE books/:id/cover` (asked first: the added cover goes for
  good, and the book shows the cover from its file if the server has one; when it answers
  `coverSource: null` the book has none now, and the line says so); for a book without a cover, `POST books/:id/re-extract-cover` (says so when the file has
  none). A locked cover offers only Unlock (the server refuses cover changes with 409). A cover
  change refused because someone locked the cover meanwhile closes the confirmation (and the
  search), discards the photo and shows the server's reason in the cover card beside Unlock
  (`bookedit/cover_locked_meanwhile`); unlocking clears it.
- **Everything follows at once** (`BookEditPublisher`, after a save or a cover change, with the book
  as the server has it then, `GET books/:id` after a cover call):
  - `ReadingChanges.bookEdited(book)`: the book page shows it (a new thumbnail and cover at the new
    version); the library grids and lists, author and series pages show it in the cards loaded
    before the edit, and in a page that was on its way (`feature.library.BookListEdits`, through
    `Pager.update`), while a page asked for later is the server's word, so someone else's change
    since shows. A series' page (`series/:id/books`) gives each card its name and number in that
    series, not its main series', and an edited card keeps them (`BookDetail.seriesMemberships`,
    `withDetails(book, inSeries)`). An author's or series' list that the book joined or left (for
    a series: any of the book's series, not only the main one) loads again; the
    Dashboard's shelves and Currently Reading show it at once (`HomeViewModel`, and a shelf answer
    asked for before the edit), and the Dashboard asks the server again (`changes`, with
    `dashboard/refresh`).
  - `BookPreview`'s card and hint for the book.
  - `Downloads.updateKept(book, artwork)`: a downloaded copy's page, the title and authors in
    `meta.json` (Downloaded) and, after a cover change, its cover and thumbnail files.
  - Covers: a cover change moves the book's `updatedAt` (so does a save of the title or series; one
    of the authors alone doesn't), which versions its thumbnail and cover URLs, so edited cards ask
    for the new ones. After a cover change `CoverCache` also forgets the book's
    images in Coil's memory cache (every key under `.../books/<id>/`, and the book page's `tint:`
    copies) and on disk (the unversioned thumbnail the series cards use, the Dashboard's day keys
    `<url>#<day>` for today and yesterday, the old versions), so screens holding an old card fetch
    again and get the new cover.
- Screenshots (`BookEditScreenshotTest`, light and dark): `bookedit/form`, `long_author` and
  `long_author_large_text` (a name too long for its chip ends in "…" and keeps its X:
  `AuthorChipTest`), `author_suggestions`,
  `series_suggestions`, `new_series`, `cover_actions`, `cover_busy`, `cover_search`,
  `cover_search_loading`, `cover_search_empty`, `cover_confirm`, `cover_confirm_failed`,
  `cover_restore`, `cover_none_left`, `cover_locked_meanwhile`, `locked`, `error`, `error_server`,
  `load_failed`, `cant_edit`, `camera`,
  `book_page` (the pencil).

## Scanning ISBNs (`feature.scan`)

Paper books are tracked against the library's copy, so the scanner's job is to find that copy from the barcode on the back.
- **Ways in:** the root toolbar's scan action beside Search over the Dashboard and the book grids (`RootFrame(onScan)`, set in `NavGraph.Root` for Home and root BookLists; `AppIcons.ScanBarcode`), which opens Scan(); and "Scan a book" beside the Currently Reading card's title (`HomeContent(onScanForTimer)`, `ScanForTimerAction`), where the user picks the book to time with each row's timer button, which opens Scan(forTimer = true). The timer has no other book picker. And the Calendar's book picker ("Scan a book"), which opens Scan(pick = true): one copy (or the one the user chooses) goes back to the picker through `ScanPicks` and the scanner closes (`ScanNav.Picked`; the sheets' texts say what the scan is for, `ScanPurpose`; `ScanPickTest`).
- **Camera:** CAMERA is asked for on the screen after a rationale panel (Allow camera, or Type the ISBN instead); a refusal shows Open settings (the app's details page) and typing; it's checked again on resume. `BarcodeCamera`: CameraX Preview in a `camera-compose` viewfinder (tap to focus through its coordinate transformer) and an `ImageAnalysis` at 1280x720, keep-only-latest, fed on one worker thread to zxing-cpp (`io.github.zxing-cpp:android` 3.1.1, Apache-2.0, native code in the APK: frames stay on the phone; `newScanner`), which reads the frame's luma plane turned by its rotation, restricted to `Format.EAN_13` (UPC-A, UPC-E and EAN-8 aren't looked for; a UPC-A price code still comes back, as its 13-digit EAN form starting with 0), with `tryHarder` (more rows scanned), `tryRotate` (a barcode held upright) and `tryDownscale` (lower resolutions tried too, which helps a blurred one), and add-ons ignored (the code before one is still read). There is no automatic zoom: ML Kit's zoom suggestion, used before, has no zxing-cpp equivalent, so a barcode too far away needs the phone brought closer. `Isbn.firstIn` takes the frame's first code that `Isbn.fromBarcode` accepts, so an ISBN beside a UPC-A or ISSN is the one read; `fromBarcode` keeps only 978/979 codes with a valid check digit (a 2- or 5-digit add-on dropped, should one be reported) and derives a 978 code's ISBN-10. While a sheet shows, frames are closed unread (`ScanUiState.analysing`); an accepted detection ticks (`HapticFeedbackType.Confirm`); the ISBN just dismissed is ignored for 3 s so the book still in view doesn't reopen it. A torch toggle when the camera has a flash; light status bar icons over the camera.
- **Typing:** "Type the ISBN" opens a sheet: the number keyboard, an X key once nine digits are in (an ISBN-10's check digit; typed at the cursor, which then follows it: the sheet keeps the field's `TextFieldValue`, `ManualSheetTest`), hyphens, dashes, spaces and an "ISBN-13:" label allowed (`Isbn.parse`). What's wrong shows while typing only when more typing can't fix it (`IsbnInput.showWhileTyping`: ten digits starting 978/979 may still become an ISBN-13), and everything once the user presses Find; a valid ISBN-10 shows its ISBN-13.
- **Lookup:** `POST books/query` with `filter {join: OR, rules: [isbn eq <13>, isbn eq <10>, isbn eq <10 with a lower-case x>]}` (`isbnQuery`). The server's `isbn` rule (operators isEmpty, isNotEmpty, eq) matches `isbn13 = v OR isbn10 = v` exactly, without normalising, and a book stores only the form its file had (the OPF parser keeps a lone ISBN-10 in `isbn10`, with the x's case as written; MOBI puts EXTH 104 into `isbn13` as written), so every spelling is asked. A MOBI whose EXTH ISBN was stored with hyphens can't be matched this way.
- **Results:** one copy replaces the scanner with its book page (BookDetail, the card put in `BookPreview`), or with Timer(bookId) for the timer. Several: a chooser sheet (cover, title, authors, `FormatChip`s, year, the user's status). None: "Not in your library" with what the providers know (`metadata-fetch/stream?isbn=&mediaKind=ebook` through `Api.searchMetadata(isbn = ...)`, candidates streamed in, `bestCandidate` preferring one that carries this ISBN), other editions already in the library (the quick search `q` = the main title, `relevance` sort, kept by `sameWork`: the same title or main title as WorkKey tokens, and an author in common by name or surname), and Request it when the user's account has `book_request_access` (as the drawer decides): RequestBook(isbn, title, author) replaces the scanner with Book requests, the fields filled and the search run with the ISBN (each provider searches it first, falling back to the title and author; editing either drops it); nothing is requested until the user taps Request there. When no provider knows the ISBN, Book requests opens to search by title. A failed library search offers Retry. The ISBN on show and a half-typed one are kept in the `SavedStateHandle`. Once the user leaves the scanner (Back or its X) nothing it finds opens, though it stays up for the crossfade: its navigation is acted on only while it is resumed (`CollectWhileResumed`; NavDisplay holds an entry that left the back stack at CREATED as it fades out, and a lookup answering while the app is away opens when the user comes back), frames are closed unread, and the X tells the ViewModel (`leave`: nothing more is looked up or opened; `ScanLeavingTest`, `ScanViewModelTest`).
- Screenshots (`ScanScreenshotTest`): `scan/permission`, `permission_denied`, `camera_failed`, `camera` (the overlay over a stand-in picture), `manual_problem`, `manual_x`, `manual_valid`, `looking`, `chooser`, `not_in_library`, `not_in_library_looking`, `not_in_library_unknown`, `failed`, `dashboard` (both ways in).

## Dependency injection

`AppContainer` (in `App.kt`) creates each app-wide object lazily, once. Features never construct
`Api`, `Session`, `ProgressStore` or `Downloads` themselves.

- **Screens** get objects through their ViewModel: `appViewModel { container -> MyViewModel(container) }`.
- **Workers and other Android components** use `context.appContainer`.
- **Screenshot tests and previews** don't use the container. They render stateless `...Content`
  composables with fake state.
- **Starting up.** `OttershelfApp.onCreate` runs `AppContainer.start()` in every process, including
  those started for a worker, a timer alarm, boot or an app update, where nothing is shown. So
  `start()` holds only what those need: the session tokens' decrypt (on an IO thread, see
  "`Session`"), the notification channels, the tracker, app settings and timer, the connection
  watch and the periodic sync (KEEP, see "Sync").
  What only screens use starts once, when the first activity is created
  (`onActivityCreated`, dispatched from `MainActivity`'s `super.onCreate`): the theme (its device
  copy, which the splash waits for, and the account's appearance from the server), the search for
  reader note writes still waiting (it reads every notes file of the account; each write schedules
  the notes worker anyway, see "Reader annotations"), the Lucide catalogue and the download
  folder's clean-up. Keep new start-up work there unless a worker or a receiver needs it.
  Before `onCreate`, the manifest's content providers run on the main thread in every process
  too, so libraries start on demand where they can: WorkManager at its first use
  (`OttershelfApp` is its `Configuration.Provider`; the manifest removes its androidx.startup
  initializer). The barcode and page readers (zxing-cpp, Tesseract) have no provider: their
  native libraries load when the user first scans an ISBN or reads a page. EmojiCompat, which Compose brings, doesn't start at all (its initializer is removed too): it would
  fetch Play services' emoji font after every first resume (where that is a system app, as on most
  phones), keep its metadata in memory and lay out every text again, for emoji the phone's own font
  already draws; only an emoji newer than the system font shows as a box. `StartupManifestTest`
  checks the merged manifest.
- **Sign-out** (`signOut`, or the server rejecting the refresh token: `stopAccountWork`) cancels the
  account's downloads, the progress, tracking and reader-notes jobs and the in-process sync, resets
  `ReadingChanges` and clears the image caches (see "Coil 3"). Its queues stay on the device.

## Screens: ViewModel + UiState + StateFlow

Every screen has three parts:

```kotlin
// feature/book/BookDetailViewModel.kt
data class BookDetailUiState(val loading: Boolean = true, val book: BookDetail? = null, ...)

class BookDetailViewModel(private val container: AppContainer, private val bookId: Long) : ViewModel() {
    private val _state = MutableStateFlow(BookDetailUiState())
    val state: StateFlow<BookDetailUiState> = _state.asStateFlow()
    fun download() { ... }                      // events are plain functions
}

// feature/book/BookDetailScreen.kt
@Composable
fun BookDetailScreen(route: Route.BookDetail, navigator: AppNavigator) {       // stateful, signature fixed by NavGraph
    val viewModel = appViewModel { BookDetailViewModel(it, route.bookId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    BookDetailContent(state, onBack = { navigator.back() }, onDownload = viewModel::download)
}

@Composable
fun BookDetailContent(state: BookDetailUiState, onBack: () -> Unit = {}, onDownload: () -> Unit = {}) { ... }  // stateless, screenshot-tested
```

- **One way to create a ViewModel:** `ui.nav.appViewModel` (in `ViewModels.kt`).
  - It is scoped to the navigation entry: cleared when the screen is popped (or, for a root list,
    replaced by a drawer pick), kept while a screen is pushed over it and across rotation.
  - Everything is cleared on sign-out.
  - The lambda has `CreationExtras` as receiver, so `createSavedStateHandle()` works for state that
    must survive process death.
- **UI state** is one immutable `data class` exposed as a `StateFlow`. Collect it with
  `collectAsStateWithLifecycle()`.
- **Events** from the UI are ViewModel functions. One-off effects (snackbars, navigation after an
  action) are either a field in the state that the screen clears, or a `Channel`/`SharedFlow`
  collected in a `LaunchedEffect`.
- **Screens use only theme colours, typography and shapes** (`MaterialTheme.*` and
  `OttershelfTheme.colors` / `.radii`), never hard-coded colours, so the user's accent, radius and
  brightness apply everywhere. See "Theme, icons and shared components".
- **Loading:** start in `init` or with `stateIn(..., WhileSubscribed(5_000), ...)`, in parallel.
  There are no Nexus-style ordering hacks.

## Theme, icons and shared components

The look is the web client's token system, computed on the device for
the user's settings, with the Nexus app's card styles on top. Screens follow their Nexus layout and
draw with these pieces.

### Theme (`ui.theme`)

```kotlin
val c = OttershelfTheme.colors            // every web token: background, card, muted, mutedForeground (dim
                                         // text), surface1..4, border, primary/onPrimary, success/warning/
                                         // info/destructive, formatPill("epub"), readStatus(status),
                                         // dashCard/dashCardBorder, cardRow, coverSurface, shellSurface...
val r = OttershelfTheme.radii             // sm/md/lg/xl/xl2/xl3/shell from the radius option (+ ...Shape)
MaterialTheme.colorScheme.primary        // Material roles are mapped from the same tokens
MaterialTheme.typography.titleMedium     // 15sp semibold card titles; the table is in Typography.kt
```

- **`OttershelfTheme(prefs, darkTheme = prefs.theme.isDark(isSystemInDarkTheme()))`**. MainActivity
  passes `container.themePrefs`; tests and previews get the web defaults (blue, default radius). It
  also sets the status and navigation bar icon colours to match light or dark.
- **Colours** (`ottershelfColors(prefs, dark)`) are built with `oklch()` (Compose `ColorSpaces.Oklab`
  to sRGB, clipped per channel like a browser), so they match the web's hex values within one step
  (`OttershelfColorsTest`, `OklchTest`). Neutrals take the accent's hue (none for white and grey),
  dark surfaces lift with brightness, and the shell surface takes the opacity setting.
- **Material mapping:** surface = card, background = background, surfaceContainerLow..Highest =
  surface1..4, outline = input, outlineVariant = border, error = destructive, tertiary = info.
  secondaryContainer is the accent tint with primary text, so selected drawer rows, filter chips
  and tonal buttons get the web's `bg-primary/12 text-primary`. surfaceTint = surface: no tonal
  tints. So a `NavigationDrawerItem`, `TopAppBar` (card colour, as the Nexus toolbar) or `Switch`
  already looks right. Material buttons are pills: use `AccentButton` / `SecondaryButton`, or pass
  `shape = RoundedCornerShape(radii.md)`.
- **Shapes:** extraSmall = sm, small = md (buttons, fields), medium = lg (cards), large = xl,
  extraLarge = 2xl (dialogs, sheets, dashboard cards). Covers use sm (the web's `rounded-sm`).
- **Fonts:** the web's Inter and Fraunces are OFL, but their files aren't in the repo, so
  `OttershelfFonts` uses the system sans and serif. Adding the TTFs to `res/font` and setting
  `OttershelfFonts.Sans` / `.Serif` is the only change needed.
- **Background patterns:** `PatternBackground { }` (or `Modifier.backgroundPattern(pattern, colors)`)
  draws the page colour and the user's pattern; the shell puts it behind its content area.
  All 20 web patterns are implemented: `none` and `dots` in `BackgroundPattern.kt`, the other 18
  in `ui/theme/patterns/AppearancePatterns.kt`, one `BackgroundPainter` map entry each. Each is
  built once per size and colour set (a repeating bitmap tile, a repeating gradient shader, or a
  few gradient brushes), so drawing it is one or a few calls. The web's viewport-"fixed" patterns
  cover the element they're drawn behind. Reference image: `settings/patterns_{light,dark}.png`
  (`SettingsScreenshotTest`).
- **Changing a setting** (the Appearance screen): `container.theme.update { it.copy(accent = ...) }`
  and `container.theme.setStorageMode(sync)`. Ids, labels and the picker's rows are on the enums
  (`Accent.rows`, `Accent.pairs`, `ThemeBackground.entries`, `Radius.entries`).

### Icons (`ui.icons`)

- `LucideIcon(name = library.icon, contentDescription = null, tint = ..., size = 20.dp, fallback = "Library")`
  draws any of the 1838 Lucide icons by the PascalCase name the server stores. An unknown name shows
  `fallback` (default `CircleDashed`). While the catalogue is still loading, a name that isn't
  built in leaves an empty space rather than flashing the fallback.
- `Lucide.get(name)` returns the `ImageVector` (stroked, 24 viewport, width 2, round caps and joins;
  solid dots filled and stroked), cached. `lucide.txt` is read off the main thread
  (`Lucide.preload`) when the first activity is created (see "Dependency injection"); a ViewModel
  can build vectors ahead with `Lucide.prefetch(names)`.
- The icons the app's own UI uses are built in (`LucideBuiltIn.kt`), so they never wait: the shell, the Dashboard widgets and Customise, Statistics, Achievements, Rewind and the highlights and notes screens. To add one,
  edit the names in `tools/gen-lucide-builtin.sh` and run `bash tools/gen-lucide-builtin.sh`.
- `AppIcons` has ready-made vectors for the shell (Home, Library, Downloads, Requests, Settings,
  Back, Close, Orbit, Menu, Search, SignOut, Dashboard, Retry, ScanBarcode).
- Renamed icons the web still accepts are aliased (`BookMarked` is `BookBookmark`).

### Shared components (`ui.components`)

| Component | What it is (Nexus / web source) |
|---|---|
| `BookCover(model, title, modifier, authors, seed, shape, requestWidth, overlay = { })` | Coil cover in a 2:3 box, radius sm, cover surface while loading; drawn by `FittedCoverImage` (below), so a cover of another shape is never cropped; the web's generated placeholder when there is no cover or it fails. `model` = `api.coverModel(book)` (versioned thumbnail URL, or null) or a downloaded book's File. `requestWidth` (optional): asked for at that size at once, any bigger cached copy serving and shown while a bigger one loads (`sizedCoverRequest`). |
| `BookCoverPlaceholder(title, authors, seed)` | The web's gradient cover (book-cover.ts palette, lattice, frame, title, author); initials below 64dp wide. The title shrinks so its widest word fits (`widestWord`: all the words in one unwrapped layout, a word a line; `PlaceholderMeasureTest`). |
| `BookGridItem(book, cover, onClick, status, showFormat, onLongClick, coverWidth)` | A grid cell (item_book.xml): 6dp padding, series top right, status disc bottom right (30% of the width), progress on the bottom edge, title 13sp medium, authors 12sp dim. `BOOK_GRID_MIN_CELL` is 120dp (the book grids' default size: three columns on a phone; see "Grid, list and the quick view"), `BOOK_GRID_SPACING` 6dp. Pass the ReadingChanges override as `status`; `onLongClick` (optional) for the quick view; `coverWidth` (optional) is passed on as `BookCover(requestWidth)`. |
| `ShelfCover(book, cover, onClick, width = 120.dp, onLongClick)` | A shelf cover (item_shelf_book.xml): status top left, series top right, format bottom right, progress; `onLongClick` (optional) for the quick view. |
| `StatusBadge(status, size)`, `StatusIcon(status, size)` | The status icon in its colour on the dark disc (nothing for unread); the plain coloured icon for menus. |
| `FormatBadge(format)`, `FormatChip(format)`, `primaryOpenableFormat(files)` | White caps on the format colour at 90% (covers); a text chip in the per-theme pill colour; which format the web badges. |
| `SeriesBadge(index, small)`, `CountPill(count)` | "#4" on a dark pill; a count beside a title. |
| `PillProgressBar(progress)`, `CoverProgressBar(progress, read)` | Rounded track and fill, 6dp (progress is 0..1); the 3dp bottom-edge bar on covers (primary 70%, green once read). |
| `DashCard(onClick, containerColor, borderColor, shape, contentPadding) { }` | Dashboard card: card at 30% over the page, 1dp primary/40 edge, 2xl radius. A plain card: `containerColor = colors.card, borderColor = colors.border, shape = radii.lgShape`. |
| `SectionHeader(title, icon, count, trailing)`, `CardTitle(title, icon)`, `CardRow(onClick) { }` | Shelf header (28dp framed icon, 15sp bold, count pill); widget title (16dp accent icon); a row inside a card (muted 20%, 40% pressed). |
| `EmptyState(message, icon, compact, action)`, `ErrorState(onRetry, message, detail, compact)`, `LoadingState()`, `FullScreenLoading()`, `SkeletonBox()` | Muted disc and dim text; dim text and an accent Retry, the whole area retries when tapped; accent spinner; pulsing placeholder block. Static in previews and screenshot tests. |
| `AccentButton(text, onClick, icon)`, `SecondaryButton(text, onClick, icon)` | Accent fill, or card fill with a border; md radius; optional Lucide icon. |
| `DetailTopBar(title, onBack, subtitle, actions)` | A pushed screen's Nexus toolbar: Back, 19sp title with an optional dim subtitle, 56dp on the shell surface, a 1dp divider; takes the status bar inset (put it in a Scaffold's `topBar`). |
| `ImmersiveSystemBars(barsVisible)` | A reader's immersive mode: the system bars hide while `barsVisible` is false (swipe shows them for a moment) and come back when the last immersive screen leaves; the newest on the window decides, so a reader replaced by another (Read next) doesn't show them over the new one as it fades out (`ImmersiveOwners`, `ImmersiveSystemBarsTest`). |
| `KeepScreenOn(on)`, `KeepScreenOnWhileReading(vararg activity)` | The screen stays on while `on` (the timer while it runs, if the user wants it). The readers': for 10 minutes (`READING_SCREEN_ON_MS`) after the last change in `activity` (the page or position, the bars shown or hidden) or the reader's last return on screen (its `ON_START` event, counted as an event rather than read from the lifecycle's state: back from the lock screen or another app, before any page is turned); then the phone's own timeout applies. Each screen holds the window view's keepScreenOn for itself (`ScreenOnHolders`), so the one leaving during the crossfade (Read next, the timer opened over a reader from its notification, Back from it) never turns it off for the one arriving (`ReadingScreenOnTest`). |
| `FittedCoverImage(model, contentDescription, modifier, onSuccess, onError)` | A cover image filling its box as the web shows covers by default ("blurred fit", BookCoverArtwork.vue): the whole cover, fitted, over the same image enlarged 10%, blurred 12dp and dimmed to 90% when its shape differs from the box's by more than 3% (a tall paperback, a square audiobook cover); within 3% it fills the box as a crop did. The backdrop reuses the decoded image (nothing more is fetched). Used by `BookCover`, the book page's cover, the cover search and the collection fans; author portraits still crop to their circle. `CoverFitDeviceTest`, `FittedCoverTest`. |
| `Modifier.belowStatusBar()` | Every `ModalBottomSheet`'s `modifier` (pass it to new sheets too): keeps the sheet below the status bar. Without it a sheet tall enough to reach the bar pads its content by however much of the bar it covers, so its height follows its own offset, and a hard fling to the end of a long list (the cover search) left the sheet shaking up and down until the next touch. |

Components read `OttershelfTheme` only, never the container, so screenshot tests render them
directly. Their strings are in `strings_components.xml` (`components_*`).

## Navigation (Navigation 3, `ui.nav`)

The layout is the Nexus app's (MainActivity, activity_main.xml): a navigation drawer holding every
list, a toolbar over the list it shows, and full screens pushed on top. There is no bottom bar.

**Routes.** Every screen is a `@Serializable` `Route : NavKey` in `Routes.kt`: Login, Home (the
Dashboard), BookList(sourceKey, title, query?), Authors(query?), Series(query?), Downloads(query?),
BookDetail(bookId), BookEdit(bookId) (feature.bookedit), Reader(bookId, fileId, title, cfi?, format?), Comics(bookId, fileId, title), Pdf(bookId, fileId, title, page?) (always built by `ReaderRouter`, see "Readers"), Requests, Settings and Appearance; for the
tracker, Calendar (root, drawer key `calendar`), History (root, drawer key `history`), Day(date), ReadingGoals, Timer(bookId) and
TimerResult(bookId, sessionId); Statistics and Achievements (roots, drawer keys `statistics`, `achievements`) and Rewind(year?); Notes(query?) (root, drawer key `notes`, searched with the shell's search), BookHighlights(bookId, title) and Memorize(bookId?, liked); AddQuote(bookId?, title?, bookFixed, scan) (feature.quotes); Scan(forTimer, pick) and RequestBook(isbn, title?, author?) (feature.scan, see "Scanning ISBNs"). Reader takes an optional `cfi`: it opens at that highlight and, as after a fresh start, saves nothing until a page is turned (the user's own move: reader.js marks a relocate `turned` for a turn, swipe or scroll, and the TOC, slider and sheet jumps count; foliate's settling relocates don't).
- Routes hold ids and short strings only. They are saved across process death.
- `sourceKey` is `BookSource.key`: `all`, `library:3`, `scope:7`, `collection:2`, `author:5`,
  `series:9`. `Route.sourceKey` gives a root route's drawer key (`dashboard`, `downloaded`,
  `authors`, `series` included) and `Route.query` its search.

**Chrome.** Each route's `chrome` tells the shell how to frame it:
- `Root` (Home, Downloads, Authors, Series, and BookList for `all` / a library / scope /
  collection): a list picked from the drawer. The shell's `RootFrame` draws the toolbar (drawer
  toggle, the list's title, the screen's own `TopBarActions { ... }`, Scan on the Dashboard and
  the book grids, Search) with a 1dp divider,
  and the screen gets `contentPadding` (toolbar and system bars) and must apply it.
- `Detail` (BookDetail, Requests, Settings, Appearance, and BookList for `author:` / `series:`,
  the Nexus BooksActivity): pushed full screens that draw their own top bar with Back, usually
  `ui.components.DetailScaffold`. Its padding includes the system bars (edge to edge).
- `Immersive` (Login, Reader): no shell. The screen handles every inset itself.

**The back stack** (`AppNavigatorState`) is one Navigation 3 stack: the bottom entry is the root
list (`root`), the entries above it are pushed screens (`current` is the top).
- A drawer pick REPLACES the root, as the Nexus fragment replace did, rather than stacking; Back
  from a root list leaves the app. Picking the list already showing does nothing.
- Root lists cross-fade into each other; pushed screens use NavDisplay's transitions, with
  predictive back (`enableOnBackInvokedCallback` is on, targetSdk 36).
- The app starts on the Dashboard, or on Downloaded when the device is offline.
- Each entry has its own saveable state and ViewModel store: a root list's ViewModels are cleared
  when a pick replaces it, a pushed screen's when it's popped.

**The drawer** (`AppDrawer.kt`, a Material 3 `ModalNavigationDrawer`, 300dp wide like the Nexus
drawer, at most screen width - 56dp; swipe or the toggle opens it, the scrim (`DrawerScrimColor`, the Nexus DrawerLayout's 60% black) or Back closes it,
with predictive back). It only opens over a root list.
- Header: the Ottershelf badge, the "Ottershelf" wordmark ("shelf" in the accent),
  username and server. Footer: Settings (pushed) and Sign out.
- Rows are `ShellViewModel`'s port of `MainActivity.loadNavigation`: Dashboard, All books,
  Downloaded (count from `Downloads.count`), Book requests (open count, only with
  `book_request_access`; pushes Requests), the Tracking section (Calendar, History, Statistics, Achievements, Notes), a separator, Authors
  and Series (browse counts), then
  Libraries, Smart scopes and Collections with their server icons (`custom:` uploads and unknown
  names fall back to the section icon) and book counts, podcasts filtered out.
- Failure: the header says why and the rows become Dashboard, Downloaded, Tracking > Calendar (its
  months are cached on the device) and Retry. The load runs again when the device comes back
  online.
- Row look (item_nav.xml): 44dp, inset 8dp, 20dp icon, 15sp label, count pill (`compactCount`:
  1.2K); selected = accent tint, a short accent bar, accent icon/label/pill.

**Search** (the toolbar's Search action expands into a field; the Nexus `beforeSearch`):
- Submitting searches what was showing before any search: the root is replaced by the same list
  with `query` set (`BookList.query`, `Authors.query`, ...). The Dashboard has nothing of its own
  to search, so it searches All books.
- Closing the search (its arrow, or Back) returns to what was showing, without a query.
- A drawer pick while results show goes there without the search (even the same list) and closes
  the field.

**`AppNavigator`** is what screens get: `navigate(route)`, `replace(route)`, `back()` and
`dashboardLoaded(hasContent)`.
- `navigate` to a root route replaces the root (closing pushed screens), like a pick; anything
  else is pushed, or returned to if it is already on the stack.
- `dashboardLoaded`: the Dashboard reports each load (the Nexus `onDashboardFailed`). If its first
  load brought nothing and the drawer's lists failed too, the shell swaps the Dashboard for
  Downloaded, leaving any pushed screen in place.
- Screens never touch the back stack. Tests pass `AppNavigator.None`.

**Snackbars.** The shell shows download results (in the background they are notifications),
`ThemeSyncEvent.SaveFailed` and `TrackingEvent.SessionRejected` (a queued reading session the
server refused). Earned achievements show as a toast at the top (`AchievementCelebrationHost`).

**Opened from a notification** (the timer's): `PendingRoute` holds the route MainActivity read from
the intent; the signed-in shell navigates there (a TimerResult over its own Timer replaces it) and
consumes it. While signed out it waits for the next sign-in. MainActivity is exported (the
launcher), so any app can send those extras: a session id is taken only as TimerEngine makes it
(a UUID), else the book's Timer opens, so a huge one can't overflow the saved back stack
(`PendingRouteTest`).

**Sign-in and sign-out.** `AppRoot` shows the Login route while `AuthState.status` is `SignedOut`.
Otherwise it shows `AppScaffold`, keyed by account. So:
- signing out (the drawer or Settings), or the server rejecting the refresh token, sends the app
  to Login;
- after signing in again, the back stack and every ViewModel start fresh;
- Login needs no navigation call on success.

**Adding a route** is a shared change: the `Route` in `Routes.kt` and one line in `NavGraph.kt`.
The `when` there is exhaustive, so a route without a screen doesn't compile. A new root list also
needs a `sourceKey`/`query` case, a title in `rootTitle` and a drawer row.

## Strings and resources

- Each feature owns `res/values/strings_<feature>.xml`: login, home, library, book, reader, comics, pdf,
  downloads, requests, settings, timer, calendar, history, stats, achievements, notes, quotes, seriesnext, scan, bookedit. Every name is prefixed `<feature>_`, because resource names are
  global.
- `strings.xml` holds the app name and the common strings (`common_*`). `strings_nav.xml` holds
  the shell's (`nav_*`: drawer, toolbar, search, Back).
  `strings_core.xml` holds data-layer texts (`core_*`, used by notifications).
- `strings_components.xml` holds the shared components' texts (`components_*`); the theme and
  icon layers use it too.
- The debug and dev builds' app name ("Ottershelf (dev)") is in `src/debug/res/values/strings.xml`
  and `src/dev/res/values/strings.xml` (a build type reads only its own folder: keep both).
- Drawables and other resources a feature adds are prefixed the same way (`book_...`,
  `reader_...`).
- No user-visible text is hard-coded in Kotlin. The exceptions are error messages that come from
  the server or the network.

## Screenshot tests (Roborazzi + Robolectric)

There is no emulator. Check every screen as PNGs, in light and dark, at phone size.

```kotlin
// app/src/test/java/io/github/ottershelf/feature/book/BookDetailScreenshotTest.kt
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class BookDetailScreenshotTest {
    @Test
    fun loaded() = captureLightAndDark("book/detail_loaded") {
        BookDetailContent(BookDetailUiState(loading = false, book = sampleBook))
    }
}
```

- **Helpers** are in `app/src/test/java/io/github/ottershelf/testing/Screenshots.kt`:
  - `captureLightAndDark(name) { }` renders at 411x891 dp, xxhdpi (1233x2673 px), inside
    `OttershelfTheme` on the page background. It switches system night mode, so
    `isSystemInDarkTheme()` follows. Pass `prefs = ThemePrefs(accent = ..., radius = ...)` to try
    other settings, and `heightDp` for a taller image.
  - `WithFakeCovers { }` (`testing/FakeCovers.kt`) makes Coil draw made-up covers for
    `fakeCover(n)` models and fail for `BROKEN_COVER`, and reads the full Lucide catalogue, so book
    grids and server-chosen icons render without a network.
  - `captureLandscape(name) { }` renders at 891x411 dp.
- **Output:** `app/build/outputs/roborazzi/<name>_light.png` and `<name>_dark.png`. Name them
  `<feature>/<screen>` so each feature writes to its own folder.
- **Record:** `./gradlew recordRoborazziDebug`. A plain `testDebugUnitTest` skips the captures.
  Then open the PNGs and look at them.
- **Test what to render:** screenshot the stateless `...Content` composables with fake state. Don't
  build the `AppContainer` in a test. Robolectric runs a plain `Application`
  (`src/test/resources/robolectric.properties`) because the real one needs the Keystore and
  WorkManager.
- **Samples:** `ui/screenshots/ShellScreenshotTest.kt`; `ThemeGalleryScreenshotTest` (tokens, type
  and Material components per accent, radius and brightness, in `theme/`) and
  `ComponentsScreenshotTest` (every shared component, in `components/`).
- **Store screenshots:** `ui/screenshots/StoreScreenshotTest.kt` renders the six Google Play phone
  screenshots (360x640 dp at xxhdpi, so 1080x1920 px; light, the default accent, a drawn status bar
  and gesture handle, public-domain books) into `store/1_dashboard.png` .. `store/6_notes.png`. The
  store wants them as 24-bit PNGs without alpha, so convert the recordings before uploading them.
- **Endless animations** (an indeterminate spinner) keep a capture from settling: show a still
  frame when `LocalInspectionMode.current` is true, as `LoadingState` does, and wrap the content in
  `WithFakeCovers` (which sets it).
- **Other tests:**
  - Data-layer tests use Robolectric only for a Context (`ProgressStoreTest`, `SessionAndApiTest`),
    or plain JUnit where possible (`LocalEpubTest`, `IsoTimeTest`, `AppNavigatorTest`).
  - Fakes go behind small interfaces like `ProgressRemote` and `TokenCipher`.

## Device tests (`app/src/androidTest`, on a phone)

What Robolectric can't run is checked on a real phone (`io.github.ottershelf.devicetest`): the
foliate reader in the phone's WebView, the native barcode and page readers (zxing-cpp,
Tesseract), Look up's client on the phone's network, and the library grid's pinch and flings under
real touches.

- **The installed app's data is left alone.** The tests run in the app's process with its sign-in,
  downloads and settings present, so they never read or write the app's preferences, DataStores,
  databases, downloads or tokens and never call its server: fixtures go into `cacheDir/device-tests/`
  (deleted afterwards), screenshots and `report.txt` into
  `/sdcard/Android/data/<app>/files/device-tests/` (pulled and deleted by the run script); the
  reader's `ReaderRequests` gets an `Api` over a separately named, empty session
  (`offlineApi()`), and the library's covers come from a test `ImageLoader` set as Coil's singleton.
- **Never `connectedAndroidTest`:** Gradle uninstalls the app when it finishes, which would wipe its
  sign-in and downloads. Build `assembleDebug assembleDebugAndroidTest assembleDev`, then
  `RESTORE_APK=app/build/outputs/apk/dev/app-dev.apk bash tools/device-tests/run.sh <out-dir> [class[#method]]...`:
  it reads the app and test packages from the APKs (`aapt2 dump badging`), picks the device
  (`SERIAL=<serial>`, else the one connected device new enough for the app), installs the debug
  app with `-r` (keeps its data) and the test APK, runs `am instrument`,
  pulls the outputs, uninstalls the test package only, puts the dev build back (`RESTORE_APK`,
  with its baseline profile `.dm`, or without it if pm refuses it) and turns the screen off; a
  Ctrl-C, TERM or closed terminal once it has started still does the last three. The tests need
  the debug build (the test APK is built against it, `testBuildType`, and uses ui-test-manifest's
  `ComponentActivity`), which shares the dev build's package.
- **Over the lock screen, never unlocking it.** `LockScreenHost` shows ui-test-manifest's
  `ComponentActivity` like an alarm clock (`setShowWhenLocked`/`setTurnScreenOn` before it is
  created, a task of its own, keep screen on) with only what a test sets; every injected gesture
  first checks the host is resumed, focused and the top window (`requireOnTop`), and gestures keep
  clear of the screen's edges.
- **Reader** (`ReaderSession`, `ReaderProbe`, `FixtureEpub`): the reader page hosted as
  `ReaderScreen` hosts it (the app's `SelectionWebView` with ReaderWebView's settings, the asset
  loader, `ReaderRequests` over a downloaded-copy fixture, `ReaderContent`), with a bridge that has
  `ReaderViewModel.Bridge`'s ten methods and follows the user's reading and moves as the ViewModel
  does (`ReadingSigns`; `ReadingVisit.markMoved` on a `turned` relocate). `ReaderDeviceTest`:
  layout and text in both flows (and ink in the screenshot: never a blank page), relocates with
  times, a real swipe/fling, a restored place and flow switches that aren't the user's move, typography in
  the computed styles, contents picks and a turn back into the previous chapter in pages, swipes
  past either end, and the end of the book as reader.js reports it (`bookEnd`: on the last page but
  not the one before it, and at the bottom of the last scrolled section but not its top).
  `ReaderHostileBookDeviceTest` (`FixtureEpub.pack` writes its book): a book's scripts inline, in a
  handler, a javascript: link, SVG and srcdoc, the reader's page and script and its own `/api/`
  files framed, loaded or refreshed to, never reach the bridge (only one onReady, one onOpened,
  relocates, locations); its links out open nothing; an animated chapter sends four relocates a
  second at most, at its top and part-way down (where they differ), the last one where the page
  stopped; its files come with the sandbox headers, `/favicon.ico` is a 403; and it still reads.
- **Barcodes** (`Barcodes` draws EAN-13 and EAN-5 from the standard's tables): the scanner's own
  zxing-cpp reader (`newScanner`) and `Isbn.firstIn` on clean, small/blurred/turned, 979, ISSN, UPC-A
  and add-on codes, each read's time in `report.txt`.
- **Page reading** (`OcrDeviceTest`): `PageReader.recognise` (Tesseract, the bundled model copied to
  `noBackupFilesDir/tessdata/` as the app does) on two paragraphs drawn in a serif face: nine words
  in ten read, every box on the page and in reading order, a box over every drawn line; a blank
  page has no lines; a read cancelled part-way stops, and the next one works. Times, lines and
  texts go to `report.txt`.
- **Look up:** `LookupHttp.client` with a recorder and a careless caller added (User-Agent,
  no `Authorization`/`Cookie` on the wire, other hosts refused before a lookup), the phone's text
  apps, and the real `LookupSheet`.
- **Library:** `BookGridContent` with 500 made-up books: UiAutomator pinches (columns, the book
  under the fingers step by step), flings timed with FrameMetrics (janky-frame percentage in
  `report.txt`; again with pages of 60 arriving as the grid nears its end, loading first and the
  page 150 ms later as the Pager sends them), the quick view on a long press (its actions are never
  tapped).
- The app's only test hooks: `SelectionWebView` and `newScanner` are internal instead of private.

## Release build (R8)

### Build types

Two build types minify with R8 (`proguard-android-optimize.txt` + `app/proguard-rules.pro`) and
shrink resources; the third is for tests:

| Build type | APK | Package, name | Signed with | Used for |
|---|---|---|---|---|
| `dev` | `apk/dev/app-dev.apk` | `devApplicationId` (default `io.github.ottershelf.dev`), "Ottershelf (dev)" | the debug key, always | day-to-day testing on a phone, and test builds (pre-releases) |
| `release` | `apk/release/app-release.apk` (`app-release-unsigned.apk` without the key) | `io.github.ottershelf` | the release key, once it exists | releases |
| `debug` | `apk/debug/app-debug.apk` | as dev | the debug key | unit, Roborazzi and device tests (`testBuildType`), devtools |

- **The dev package** (`devApplicationId` in `app/build.gradle.kts`) is the same for dev and debug,
  so either replaces the other with `install -r`. It is `io.github.ottershelf.dev` unless the
  property `ottershelf.devApplicationId` names another one: in `local.properties` (not committed),
  else as a Gradle property. A device that already runs a dev build under another package sets it
  there, so new builds keep updating that install (and its data) in place. The device tests' APK is
  that package plus `.test`.
- **dev** is `initWith(release)`: R8, resource shrinking, not debuggable, the baseline profile;
  plus the dev package and `signingConfigs.debug`, so it updates the "Ottershelf (dev)" already
  installed in place (its sign-in and downloads kept) and is never signed with the release
  keystore. `matchingFallbacks` is `release` (for any library module added later).
  Its app name is in `src/dev/res` (a copy of `src/debug/res`'s). The debug-only libraries
  (ui-tooling, ui-test-manifest) aren't in it, so `src/debug/AndroidManifest.xml` has nothing to do
  there. `BuildConfig.DEBUG` is false: the reader's WebView can't be inspected and logs only
  console errors, and Settings shows "Version x" without "(debug build)". Nothing else reads it.
  Checked on 0.1.9: R8 reports no warnings, `mapping/dev/seeds.txt` keeps the bridge methods and
  the serializers below, the manifest has the dev package and no `debuggable`, and the APK
  is 28.0 MB. With zxing-cpp and Tesseract in place of ML Kit (after 0.1.12) it is 20.7 MB, down
  from 28.7: ML Kit's `libbarhopper_v3.so` (4.9 MB) and `libmlkit_google_ocr_pipeline.so`
  (11.1 MB) and its model assets out, `libzxingcpp_android.so` (1.7 MB), `libtesseract.so`
  (4.2 MB), `libleptonica.so` (2.9 MB), `libjpeg.so` and `libpngx.so` (0.5 MB together) and the
  English model (2.0 MB compressed) in. Every native library in it has 16 KB-aligned LOAD
  segments (Android 15+ phones with 16 KB pages, and Google Play, need them) and the APK passes
  `zipalign -c -P 16`; check that again when a native library is added or updated. Its code is
  obfuscated (exception names aside, below), so a crash trace from a published build can only be
  read with that build's `mapping/dev/mapping.txt` (or `mapping/release/`): keep each published
  build's map outside the repository, and retrace with the SDK's `retrace` tool.
- **release** makes an unsigned APK unless a keystore properties file exists outside the
  repository (the Gradle property `ottershelf.keystore`, else `~/.ottershelf/keystore.properties`:
  `storeFile`, relative to that file or absolute, `storePassword`, `keyAlias`, `keyPassword`); then
  `signingConfigs.release` signs it. A file missing one of those stays unsigned too, with a warning
  naming it on every build (the debug build and tests still run). The key and its passwords never
  enter the repository (`.gitignore` covers `*.jks`, `*.keystore`, `*.p12` and
  `keystore.properties`). Choose the key with care before the first release install: changing keys
  later means uninstalling, which loses the downloads.
- **Baseline profile** (dev and release; a debuggable build isn't compiled from profiles, and the
  debug build doesn't carry one). AGP merges the libraries' ART rules (Compose, Material 3,
  lifecycle, coroutines and the rest: about 5,200) with `src/main/baseline-prof.txt`, the app's own
  cold-start path only: `OttershelfApp`, `AppContainer`, `App.kt`'s functions, `MainActivity`,
  `ui.nav`, `ui.theme`, `core.theme` (the saved appearance the splash waits for), `core.session`,
  `core.network` and `feature.home`, as wildcard class and method rules that AGP expands and R8
  renames. `BaselineProfileTest` checks every rule still names a class or package (AGP drops one
  that matches nothing without a word) and that none covers the whole app or every feature: the
  rest is left to ART's JIT profile, which the idle-and-charging dexopt compiles within a day, and
  rules over the whole app would compile all of its roughly 1.5 MB of bytecode into the install.
  On 0.1.9 the app rules add about 450 KB of bytecode (about 1,550 methods after R8) to the roughly
  1.1 MB the library rules already compile, and `assets/dexopt/baseline.prof` grows from 13.0 to
  14.4 KB; the dex itself is unchanged. The APK carries the profile for profileinstaller, which
  hands it to ART at the first launch (compiled at the next idle dexopt); that is the only path for
  an APK downloaded from a release page, since the phone's installer takes the APK alone.
  AGP also writes `apk/<type>/baselineProfiles/0/<apk name>.dm` (API 31+; `1/` is for 28 to 30),
  and installed with the APK (`adb install-multiple -r app-dev.apk app-dev.dm`) ART compiles it
  with speed-profile at install. Every update throws away the compiled code, so without the `.dm`
  the first day after each update runs the start path JIT-only.
  All of this is stock ART, as on most phones. GrapheneOS turns ART's JIT off and compiles every
  app in full at install, so there the profile and the `.dm` change nothing
  (check with `adb shell dumpsys package <package> | grep -iE 'status=|filter'`: a full compile
  such as `speed`, where stock ART shows `speed-profile`), and a cold start timed on it can't tell
  the rules' worth.
  Keep the app rules only while a cold start measures faster with them on a stock-ART phone
  (`am start -S -W` TotalTime, median of 10, the day of a `.dm` install). To drop them, delete
  `src/main/baseline-prof.txt` and `BaselineProfileTest`, and the test input naming the profile in
  `app/build.gradle.kts` (an `inputs.files`, which allows a missing file, so the tests still run
  if it is left behind); the libraries' rules stay.

The app's own rules keep what is reached by reflection:
- the kotlinx.serialization serializers of every `@Serializable` class under `io.github.ottershelf`
  (API models, reader settings, `DownloadedBook`, the comics' next-in-series reply, the saved
  Navigation 3 routes, `Route.Comics` and `Route.Pdf` included);
- `ReaderViewModel.Bridge`'s `@JavascriptInterface` methods (`window.Android`, the only JS bridge;
  reader.js calls exactly its ten methods);
- the names (only) of every exception class, the libraries' too: an error with no message shows
  `e.javaClass.simpleName` (the screens' error lines, `DownloadWorker`), and logcat traces name
  the real exceptions, as in the debug build;
- Tesseract4Android's two packages whole (`com.googlecode.tesseract.android`,
  `com.googlecode.leptonica.android`): it ships no consumer rules, and its native code calls back
  into them by name (`TessBaseAPI.onProgressValues`, used only from native code). Checked in
  `mapping/dev/seeds.txt`.
The comics and PDF readers need no rules of their own (Coil, Telephoto, WorkManager, CameraX and
zxing-cpp bring consumer rules; `PdfRenderer` is the platform's), nor does the ISBN scanner (checked:
the dev APK carries `libzxingcpp_android.so`, `zxingcpp.BarcodeReader` keeps its name, and the
`Route.Scan` / `Route.RequestBook` and `ScanQueryBody` serializers are kept). Checked:
R8 reports no warnings and `mapping/release/seeds.txt` keeps the bridge methods and those
serializers (and `mapping/dev/seeds.txt`, the same rules). Add a rule here when new code is reached
by reflection (a new JS bridge, a class serialized by name), and check it in the dev build, the
build used for day-to-day testing.

### Names that are stored

Some names outlive a build: they are stored on the device, on the server, or by Android itself, so
renaming them in the code would lose data. Keep them as they are:

- **Serialized class names.** kotlinx.serialization writes a sealed class's subclass under its
  fully qualified name unless it has a `@SerialName`. Every sealed hierarchy that is saved to a
  file, a store or a queue needs short `@SerialName`s that never change (`NoteOp`'s `createAnnotation`,
  `deleteBookmark`, ...: the reader notes' queue in `files/reader-notes/`; `AnnotationModelsTest`
  reads a queue as older builds wrote it). The routes (`Route`) keep their class names: they are
  only in the activity's saved state, which Android discards when the app is updated.
- **Worker class names.** WorkManager stores each work item's worker class. Until 0.1.12 the code
  was in the package `net.redmund.bookorbit` (a legacy identifier); `LegacyWorkers`, the app's
  `WorkerFactory`, maps those names to today's workers so work queued by an older build still
  runs, and `SyncScheduler`'s spec 3 registers the periodic sync under its new class once
  (`LegacyWorkersTest`, `SyncSchedulerTest`).
- **Other stored names:** the Keystore alias of the token key (`bookorbit.session`), the
  SharedPreferences (`session`, `progress`, `sync`), the DataStore (`settings`, with its keys), the
  files under `files/` and `cacheDir/`, the users.settings key `bookorbitAndroid`, the quotes'
  placeholder CFI mark (`bookorbit-quote`), the notification channels (`downloads`, `timer`,
  `timer_alerts`) and the unique work names. Some of them still say BookOrbit: they are older than
  the Ottershelf name.
- **Intents** (the timer's actions and extras) are not stored: a notification or alarm made by an
  older build names components that no longer exist, and the app posts its notifications and sets
  its alarm again when it is updated (`TimerRestoreReceiver` on `MY_PACKAGE_REPLACED`).

## Ownership: features don't edit each other's files

Features are built in parallel branches. To merge cleanly:

1. **A feature changes only its own package and its own resources:**
   - `feature/<name>/**`
   - `res/values/strings_<name>.xml` and `<name>_`-prefixed resources
   - `src/test/.../feature/<name>/**`
2. **Keep screen signatures stable** (the ones `NavGraph.kt` calls), such as
   `HomeScreen(navigator, contentPadding)` and `BookDetailScreen(route, navigator)`. Replace the
   placeholder bodies, not the signatures.
3. **Shared files** belong to someone else. Change them only when your change needs it, keep the
   change small and additive, and name each one in the pull request:

| Shared file | Who changes it, and how |
|---|---|
| `gradle/libs.versions.toml`, `app/build.gradle.kts`, `settings.gradle.kts` | Add a library (version in the catalog). Never bump existing versions casually. Libraries come from Google's Maven and Maven Central; the one exception is JitPack, allowed for Tesseract4Android's group only (see "Security and privacy") |
| `app/src/main/AndroidManifest.xml`, `app/src/debug/AndroidManifest.xml` | Permissions or components a feature really needs (e.g. `configChanges` for the reader); the removals there (the startup initializers, the debug tooling activities) are deliberate: see "Security and privacy" |
| `App.kt` (`AppContainer`), `core/**` | The data-layer owner. Features call these APIs; small additive methods are OK when needed. |
| `ui/nav/Routes.kt`, `ui/nav/NavGraph.kt` | Only to add a route or change a route's parameters |
| `ui/nav/*` (other files), `MainActivity.kt` | Shell owner |
| `ui/theme/**`, `core/theme/**`, `res/values/colors.xml`, `themes.xml` | Theme work |
| `ui/theme/patterns/AppearancePatterns.kt` | The appearance work: add the remaining background patterns here |
| `ui/icons/**`, `tools/gen-lucide-builtin.sh` | Icon work (others may add a built-in icon: a name in the script, then run it; or a vector in `AppIcons`) |
| `ui/components/**`, `strings_components.xml` | Add a new file for a truly shared component; don't change existing ones' behaviour |
| `res/values/strings.xml`, `strings_nav.xml`, `strings_core.xml` | Shell and data layer only. Features use their own strings file. |
| `assets/**` | Reader work (foliate, reader); icon work (lucide.txt) |
| `app/proguard-rules.pro` | Add keep rules for your own code if R8 needs them |
| `ARCHITECTURE.md` | Update the part your work changes |

## Security and privacy

- **HTTPS only:** cleartext is not permitted and no roots are bundled
  (`res/xml/network_security_config.xml`).
- **Tokens:** encrypted at rest with a Keystore key, sent only to the server itself
  (`Api.isOwnServer`), and paired only with a server where a sign-in worked (see "Data layer").
- **No backups:** `allowBackup=false`, and `data_extraction_rules.xml` excludes everything.
- **Passwords:** the user types their own on the device. The app never stores it. Login, refresh
  and logout don't follow redirects, so the password and refresh token go only to the address the user
  typed.
- **Server answers:** what `Api` reads into memory is bounded (see "Data layer").
- **Reader proxy:** `Api.proxy` forwards only the epub routes, and the reader (`ReaderRequests`)
  answers only the open book's `info` and `file/...` GETs (or, for a format read whole, only
  that file's `books/files/<fileId>/serve`); everything else the page asks for gets a 403, and
  so does any other site, and anything on the page's own host outside the APK's `/assets/` or on
  another port (it would go to the network). Every answer carries `no-store`,
  `Content-Security-Policy: sandbox; default-src 'none'` and `nosniff`: foliate fetches the files
  and shows them from blob: URLs, which the policy never reaches, but a book's file loaded as a
  page itself gets no scripts and an origin of its own. `reader/index.html` has a CSP like the web
  reader's, so a book's own scripts don't run: scripts only from `/assets/` (never the book's files
  on the same host), frames only foliate's blob: chapters (a chapter can't frame the reader's page
  or a book's file); the chapters' blob: documents inherit it. `window.Android` is a bridge per
  page (`ReaderViewModel.attach`) that hears one `onReady`, from the page on screen (`PageReady`),
  and reader.js keeps a copy of itself loaded inside a chapter quiet; links out of a book open
  nothing (foliate's `external-link` is cancelled); DOM storage is off.
  `ReaderHostileBookDeviceTest` and `tools/reader-js-test` (`hostile.html`) try all of this with a
  crafted book. WebView debugging is enabled only when `BuildConfig.DEBUG` (the debug build, not
  the dev build).
- **Links in a book's description** (the book file's or a provider's HTML, shown with
  `AnnotatedString.fromHtml`): a tap goes to `openDescriptionLink`, never Compose's default
  handler; only http, https and mailto open, with CATEGORY_BROWSABLE, and a missing or guarded app
  is ignored (`DescriptionLinkTest`).
- **Licensing:** BookOrbit is AGPL with additional terms, and the models and reader mirror its
  code, so the app is a modified version of BookOrbit under the AGPL v3 only with BookOrbit's
  additional terms, and its complete source is published (`BuildConfig.SOURCE_URL`, this version's
  tag of https://github.com/redmundmcmund/ottershelf): `LICENSE` and `ADDITIONAL_TERMS.md` are BookOrbit's files, unchanged; `NOTICE`
  (the app's notice, with BookOrbit's quoted in full) and `THIRD_PARTY_NOTICES.md` list the rest.
- **The About screen is required, not decoration** (`feature.settings.AboutScreen`, Settings >
  About Ottershelf, and "Powered by BookOrbit" under Settings): ADDITIONAL_TERMS.md section 1 wants
  BookOrbit's attribution, word for word, with "Powered by BookOrbit" linking to
  https://github.com/bookorbit/bookorbit, in an About feature every user can reach and that isn't
  hidden or made less prominent; section 3 wants the sentence "This is a modified version of
  BookOrbit and is not an official BookOrbit release." and a modification date (`BuildConfig.
  MODIFIED_DATE`, the last commit's date). The `about_*` notice strings are marked untranslatable;
  never reword or remove them (`AboutNoticeTest` checks them against ADDITIONAL_TERMS.md). Section
  4 bars "BookOrbit" or its logo as the app's name or branding: the app is **Ottershelf**, and
  BookOrbit is named only as the server it talks to and in the attribution.
- **Branding:** the name is Ottershelf ("Ottershelf (dev)" for the debug and dev builds). The icon,
  badge and Play icon come from `docs/brand/ottershelf-artwork.webp` through
  `tools/brand/make-icons.ps1` (Windows PowerShell): the launcher icon is adaptive
  (`mipmap-anydpi/ic_launcher.xml`: the water scene over `ic_launcher_background`), the splash
  shows the badge (`drawable/ic_splash.xml`), and `drawable-nodpi/ottershelf_badge.png` is the
  logo on the login screen, the drawer, Settings, About and shared quote cards.
- **No telemetry, no Google libraries:** the barcode and page readers are open source and entirely
  on the phone (zxing-cpp; Tesseract with Leptonica, libpng and libjpeg), and nothing in the app
  comes from Google Play services, ML Kit, datatransport or Firebase:
  `./gradlew :app:dependencies --configuration devRuntimeClasspath` lists none of
  `com.google.mlkit`, `com.google.android.gms`, `com.google.android.datatransport` or
  `com.google.firebase`, and `StartupManifestTest` checks that the merged manifest declares no
  component of theirs. (Up to 0.1.12 the app used ML Kit, whose datatransport telemetry the
  manifest cut off; a phone that had those builds may keep datatransport's small event database in
  the app's storage, never sent, and nothing left in the app reads it.) The WebView's usage
  metrics are off too (`android.webkit.WebView.MetricsOptOut`). There is no analytics or crash
  reporting.
- **Where the libraries come from:** Google's Maven and Maven Central, except Tesseract4Android,
  which is published only on JitPack: `settings.gradle.kts` adds JitPack with `exclusiveContent`
  for the group `cz.adaptech.tesseract4android`, so that group resolves only from JitPack and
  nothing else can resolve from it. JitPack builds from the project's GitHub tag (4.9.0), so
  check a version bump's release there first.
- **Signing out** leaves nothing running for the account and clears what is cached by URL (covers,
  comic pages, served PDFs; see "Dependency injection"). Its queued writes stay for its next
  sign-in: the reading sessions (`ProgressStore`) and reader notes (`NotesSync`) ask
  `AppContainer.stillSignedInAs` before each send, and the tracker compares the account
  (`TrackingRepository`), so a pass that outlives the sign-in stops at its next send. What that
  can't stop is a request already on its way: if the server answers it with a 401 after another
  account has signed in on the phone, the Api's authenticator sends it again with the new token. It
  takes a sign-out and a new sign-in during that one request.
- **Debug build:** a phone has it only during the device tests (day to day it runs the dev
  build, which has neither library), so `src/debug/AndroidManifest.xml` removes ui-tooling's
  exported `PreviewActivity` (it renders any composable an intent names) and makes
  ui-test-manifest's `ComponentActivity` (used by Robolectric tests and the device tests'
  `LockScreenHost`, both from inside the app) not exported.
- **Camera (quotes):** CAMERA is asked for only when the user taps Scan a page, after a rationale;
  photos are read on the phone (Tesseract and its bundled English model) and neither they nor
  their text are uploaded; a kept photo stays in app storage.
- **Look up (reader):** only the selected text is sent, and only when the user taps Look up, to
  Wiktionary and Wikipedia over a client of its own that can't carry the account's token or cookies
  and talks to Wikimedia hosts only (`LookupHttp`); a passage of more than twelve words isn't sent
  at all. The text apps get the selection read-only.
- **Camera and photos (covers):** CAMERA is asked for only when the user taps Take a photo, after a
  rationale; a photo (taken, or picked with the Photo Picker: no storage permission) is cut and made
  smaller on the phone and sent only to the user's server as the book's cover, after the user confirms it; the
  work files in `cacheDir/bookedit/` are deleted afterwards. Online cover previews come through the
  server's proxy, never straight from the image hosts.
- **Camera (ISBN scan):** CAMERA is asked for on the scanner, after a rationale; frames are read
  on the phone by zxing-cpp and never stored; only the ISBN goes to the
  server (the books query, and the metadata search when it isn't in the library).
