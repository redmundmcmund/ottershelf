# Ottershelf privacy policy

Effective date: 2026-09-28

This policy describes how the Ottershelf Android app (package `io.github.ottershelf`) handles
information. Ottershelf is an open-source reading app for self-hosted BookOrbit servers, published
by the Ottershelf project. Its source code is available at
<https://github.com/redmundmcmund/ottershelf>, so every statement below can be checked against it.

## Summary

- Ottershelf does not send any information to the Ottershelf project. The project operates no
  servers for the app and receives no data from it.
- The app connects only to the BookOrbit server whose address you enter, and, only when you tap
  **Look up** on text selected in a book, to Wiktionary and Wikipedia.
- There are no analytics, advertising, crash reporting, tracking or profiling, and no third-party
  SDK in the app collects data.
- The camera is used only when you choose to, and images are processed on your phone.

## Your BookOrbit server

Ottershelf is a client for a BookOrbit server that you or someone else operates. Everything your
library and reading history consist of lives on that server, and the app exchanges it with that
server only. The server's operator, not the Ottershelf project, controls that data and decides how
it is stored, logged, retained and deleted, under that operator's own privacy practices. Like any
web server, the server may record connection details such as your IP address, the time of each
request and the app's user agent.

## Information the app handles

### Sign-in

- **Server address, username and password.** You type these on the sign-in screen. The password
  is sent only to the server address you typed, only to sign in, and is never stored by the app.
  Sign-in requests do not follow redirects, so the password cannot be sent on to another address.
- **Access and refresh tokens.** The server returns these at sign-in. The app stores them on the
  phone encrypted with a key held in the Android Keystore, and sends them only to that server.
- Only `https://` servers are accepted; the app refuses unencrypted `http://` connections and
  trusts only the certificate authorities in Android's system store.

### Sent to your server

The app sends your server what it needs to work, as a result of what you do in the app:

- Reading positions, reading sessions (start, end, duration, progress), read statuses and their
  dates, ratings, private reviews, rereads and past reads, and reading goals.
- Highlights, notes, bookmarks and quotes, and your likes and review counts for highlights.
- App preferences that BookOrbit stores in your account's settings (for example the Dashboard's
  layout, appearance settings if you choose to sync them, timer defaults and page totals).
- Searches and filters you run in your library, book requests, and ISBNs you scan or type (to
  find the book in your library and, if it is not there, to ask the server's metadata providers
  about it).
- Changes you make to a book's details (title, authors, series, number) and a new cover, if your
  account is allowed to edit metadata. A cover photo you take or pick is reduced in size on the
  phone and uploaded only after you confirm it.

Your server may in turn contact metadata or cover providers that its operator has configured (for
example when you search for a book request or a cover). The app does not contact those providers;
cover previews are loaded through your server.

### Received from your server

Book details, covers and book files, your library's lists, statistics, achievements and the other
information the server provides for your account are downloaded to display them and, when you
choose Download, to keep books on the phone for offline reading.

### Look up: Wiktionary and Wikipedia

When you select text in a book and tap **Look up**, the app sends the selected word or phrase (at
most twelve words; longer selections are not sent) to Wiktionary (`en.wiktionary.org`) and
Wikipedia (in the book's language or English), which are operated by the Wikimedia Foundation. It
may also load a thumbnail image from Wikimedia's servers. These requests:

- happen only when you tap Look up, never in the background;
- use a separate network connection that never carries your BookOrbit token, cookies or account
  information, and that connects only to Wikimedia hosts over HTTPS;
- reveal to Wikimedia the text you looked up, your IP address and a user agent naming the app and
  its version, but nothing that identifies your account.

Wikimedia's handling of these requests is described in the
[Wikimedia Foundation privacy policy](https://foundation.wikimedia.org/wiki/Policy:Privacy_policy).
The book's language, used to choose the Wikipedia edition, is read from your own server.

### Other apps you choose

The app hands information to another app only when you ask it to: sharing a highlight, a quote card
image or exported highlights through Android's share sheet; sending selected text to a dictionary
or translation app installed on your phone; or opening a link in your browser. What those apps do
with it is governed by their own policies.

### Camera and photos

The camera is used only after you grant the permission, and only for:

- **Scanning an ISBN barcode.** Camera frames are analysed on the phone and not stored; only the
  resulting ISBN is sent to your server.
- **Photographing a page for a quote.** Text recognition runs on the phone. The photo and its text
  are not uploaded; only the quote you save is sent to your server, as a highlight. The photo is
  deleted after saving unless you turn on "Keep the photo on this phone", in which case it stays in
  the app's private storage.
- **Taking a cover photo.** See "Sent to your server" above.

Instead of the camera you can pick an existing image with Android's Photo Picker, which gives the
app access only to the image you pick.

## Information kept on your phone

The app stores on the phone, in its private storage unless noted:

- the server address, your account's basic details and the encrypted tokens;
- downloaded books, covers and cached images;
- reading positions, sessions, highlights and other changes waiting to be sent while you are
  offline;
- app settings, per-list sorting and view choices, and appearance settings kept on the device;
- quote photos you chose to keep;
- calendar images and highlight cards you chose to save, which are written to the shared
  `Pictures/Ottershelf` folder and remain there, like any other picture, until you delete them.

The app's data is excluded from Android backups and device-to-device transfers, so it does not
leave the phone through them.

## Permissions

| Permission | Why the app requests it |
|---|---|
| `INTERNET` | To connect to your BookOrbit server and, on Look up, to Wiktionary and Wikipedia. |
| `ACCESS_NETWORK_STATE` | To notice when the connection returns and send progress saved offline. |
| `CAMERA` | ISBN scanning, page photos for quotes and cover photos; requested only when you start one of these. The camera is optional. |
| `POST_NOTIFICATIONS` | Download progress (with Cancel) and results, and the reading timer's notification and countdown alert; requested when first needed. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | To keep a book download you started running while the app is in the background. |
| `RECEIVE_BOOT_COMPLETED` | To restore a running reading timer's notification and countdown alarm after the phone restarts or the app is updated. |
| `WAKE_LOCK` | Added by Android's WorkManager library, to keep background work such as downloads and sync from being interrupted while it runs. |

The app also declares a permission restricted to itself
(`io.github.ottershelf.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, added by AndroidX), which other
apps cannot use. It does not request location, contacts, microphone, storage, phone, calendar or
account permissions.

## Third-party services and SDKs

Ottershelf contains no analytics, advertising, attribution, crash-reporting or telemetry SDKs, and
no Google Play services or Firebase libraries. Barcode scanning (zxing-cpp) and text recognition
(Tesseract, with its English model bundled in the app) run entirely on the phone. The ebook
reader's WebView is set to opt out of sending usage metrics. The only third-party service the app
contacts is Wikimedia, as described above.

## Children

Ottershelf is not directed at children and does not knowingly collect information from anyone. It
shows only content from the BookOrbit server you sign in to; which content that server holds is
up to its operator.

## Security

- All connections use HTTPS; unencrypted connections are refused.
- Tokens are encrypted at rest with an Android Keystore key and sent only to your server.
- Your password is never stored.
- Books are displayed in a restricted reader that blocks a book's own scripts and prevents it
  from loading anything except its own files.

No system is perfectly secure. You can report a vulnerability as described in
[SECURITY.md](https://github.com/redmundmcmund/ottershelf/blob/main/SECURITY.md).

## Deleting your information

- **Sign out** (Settings > Sign out) to stop the account's downloads in progress and its
  background work, remove its tokens and clear cached images and PDFs. Downloaded books and
  changes still waiting to be sent stay on the phone for that account's next sign-in.
- **Uninstall** the app, or clear its storage in Android's settings, to remove everything the app
  stored in its private storage. Images you saved to `Pictures/Ottershelf` remain until you
  delete them.
- **Data on your BookOrbit server** (your account, reading history, highlights, uploaded covers
  and so on) is not controlled by the app or the Ottershelf project. Delete it in BookOrbit itself
  or ask the server's operator.

The app does not create accounts; accounts are created on the BookOrbit server.

## Changes to this policy

Changes are published in this file in the project's repository, with a new effective date, and
its history is visible there. A change that affects what the app sends or to whom is also noted in
the release notes of the version that introduces it.

## Contact

Questions about this policy can be asked by opening an issue at
<https://github.com/redmundmcmund/ottershelf/issues>. Please report security problems privately as
described in SECURITY.md rather than in a public issue.
