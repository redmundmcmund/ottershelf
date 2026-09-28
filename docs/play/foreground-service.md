# Foreground service declaration: dataSync

Play Console: **App content > Foreground service permissions**. Ottershelf targets Android 14+ and
declares `FOREGROUND_SERVICE_DATA_SYNC`, so Play asks what the service is for, why it cannot be
deferred, and for a video showing it.

## What uses it

- One use only: **downloading a book for offline reading**, started by the user.
- The download runs in a WorkManager worker (`DownloadWorker`) that promotes itself to a foreground
  service through `setForeground()`, using WorkManager's `SystemForegroundService`, declared in the
  manifest with `android:foregroundServiceType="dataSync"`.
- While it runs, an ongoing notification shows the book's title, the progress and a **Cancel**
  action. The service stops as soon as the download finishes, fails or is cancelled; a result
  notification follows.
- Background progress sync, reading-notes sync and the reading timer do **not** use a foreground
  service.

## Declaration text

**Foreground service type:** Data sync

**Task:** Other (user-initiated file download) / Download of files, if offered as a choice

**Description (for the "describe the functionality" field):**

```
Ottershelf is a reader for books stored on the user's own BookOrbit server. When the user taps
Download on a book's page (or in the quick view), the app downloads that book's file (EPUB, PDF,
comic archive and other ebook formats, often tens or hundreds of megabytes) from the user's server
so it can be read without a connection. The download runs as a dataSync foreground service with
an ongoing notification that shows its progress and a Cancel button, so it continues when the user
switches to another app or turns off the screen. The service runs only while a download the user
started is in progress and stops as soon as it completes, fails or is cancelled. It is not used for
periodic or automatic sync.
```

**Impact if the task were deferred or interrupted (for the "user impact" field):**

```
Users download books right before losing connectivity (a flight, a commute, travel abroad). If the
system deferred the download or stopped it when the user left the app, the book would not be on
the device when the user expects to read it offline, and a large download would have to start
again. The user explicitly asked for the download and can see and cancel it in the notification.
```

## Demo video

Record a screen capture (about 30 to 60 seconds) on a phone running Android 15 or later, with the
review server's demo account and a public-domain book. Upload it (for example as an unlisted
YouTube video) and paste the link: [video URL]

1. Open Ottershelf, signed in to the demo server, and open a book's page (a large PDF or comic makes
   the download long enough to follow).
2. Tap **Download**. If Android asks for the notification permission, allow it.
3. Show the download progress on the book page.
4. Press Home to leave the app, pull down the notification shade and show the ongoing
   **Downloading** notification with its progress bar and **Cancel** action advancing while the
   app is in the background.
5. Wait for it to finish, or show a second download being cancelled from the notification.
6. Return to the app: the book page shows **Downloaded**; open the menu's **Downloaded** list.
7. Optionally turn on airplane mode and open the book to show it reads offline.

No personal data, real server addresses or copyrighted books should appear in the video.
