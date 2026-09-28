# App access: instructions for Google's reviewers

Play Console: **App content > App access**. Choose **All or some functionality in my app is
restricted**, then **Add instructions** with the values below. The app is unusable without a
BookOrbit server, so reviewers need a working demo server and account.

## Preparing the demo server

Before submitting, set up a BookOrbit server dedicated to review:

- Reachable from the internet over **HTTPS**, with a certificate from a public certificate
  authority (for example Let's Encrypt). The app refuses `http://` and self-signed or
  user-installed certificates.
- Online and reachable throughout the review, and for every later update's review. No IP
  allow-lists, VPN, geographic restrictions or two-factor authentication.
- One account for reviewers, with a password that does not expire. Change BookOrbit's initial
  password for it first: the app refuses to sign in to an account that still has its default
  password.
- Grant the account request access and the permission to edit metadata if reviewers should test
  book requests, ratings and metadata editing; otherwise say in the instructions that those
  features are hidden for this account. (A BookOrbit demo-restricted account also cannot rate
  books or change where appearance settings are stored.)
- A small library of **public-domain or openly licensed** content only, covering the formats: at
  least one EPUB, one PDF and one CBZ comic, a series with two or more volumes, and books with
  ISBNs. Do not use copyrighted books or any real user's data.
- Nothing personal on the server: no real names, reading history or addresses.

## The demo server

`https://bookdemo.redmund.net`, a BookOrbit 3.1.0 instance run only for app review. It shares
nothing with any real library: its own database, and a library of nine public-domain or CC0 files.

| Title | Format | Notes |
|---|---|---|
| A Study in Scarlet | EPUB | Sherlock Holmes #1 (Standard Ebooks) |
| The Sign of the Four | EPUB | Sherlock Holmes #2 |
| The Adventures of Sherlock Holmes | EPUB | Sherlock Holmes #3 |
| Pride and Prejudice | EPUB | |
| Frankenstein | EPUB | |
| Alice's Adventures in Wonderland | PDF, 2.2 MB | Internet Archive scan, via Wikimedia Commons |
| Alice's adventures in Wonderland | PDF, 33 MB | Illustrated scan. Large enough to film the download for the foreground-service video |
| Little Nemo in Slumberland #1, #2 | CBZ | Winsor McCay's 1905 Sunday pages, a two-issue series |

The review account is `reviewer`. It has download, metadata-editing, book-request and
notification access, and BookOrbit's **demo restricted** flag, so a reviewer cannot change its
password and lock later reviews out; the same flag hides ratings and the appearance-storage
switch. The books carry no ISBNs, so an ISBN lookup always goes to the server's metadata
providers (Open Library, Goodreads and iTunes answered without API keys when this was set up). Requests can be submitted but are never
fulfilled: the server has no download sources.

**The password is not in this repository.** It is kept with the server's other credentials;
paste it into Play Console directly.

## Instructions to enter

**Instruction name:** Demo BookOrbit server

**Username:** reviewer

**Password:** (paste from the demo server's credentials; never commit it)

**Any other information required to access your app:**

```
Ottershelf is a client for self-hosted BookOrbit servers. On the sign-in screen enter:

Server:   https://bookdemo.redmund.net
Username: reviewer
Password: (the password above)

The account is on a demo server run for app review and holds only public-domain books.
No other setup is needed.

What you can test:
- Dashboard: open the app after signing in.
- Library: menu > All books, Authors, Series; sort, filter, grid or list; long-press a cover for
  a quick view.
- Reading: open a book and tap Read. EPUB: "A Study in Scarlet". PDF: "Alice's Adventures in
  Wonderland". Comic: "Little Nemo in Slumberland #1". Select text in the EPUB to highlight, add
  a note or tap Look up.
- Series: "Sherlock Holmes" has three books; finishing "Little Nemo in Slumberland #1" offers #2.
- Offline reading and the foreground service: on a book page tap Download. The download continues
  with a progress notification (with Cancel) when you leave the app. The 33 MB illustrated
  "Alice's adventures in Wonderland" PDF takes long enough to watch.
- Reading timer: on a book page tap Start timer; the notification offers Pause and Stop.
- Tracking: menu > Tracking > Calendar, History, Statistics, Achievements, Notes.
- ISBN scanning: tap the barcode icon in the toolbar, then "Type the ISBN" and enter
  9780142437247 (Moby-Dick), or scan that ISBN's barcode. It is not in the demo library, so the app shows
  what metadata providers know about it and offers a book request. The camera is requested only
  for scanning, page photos for quotes and cover photos.
- Quotes: on a book page tap "Scan a page" to photograph printed English text, or "Add quote" to
  type one.
- Book requests: menu > Book requests (requests are accepted but not fulfilled on this server).
  Metadata editing: the pencil on a book page.
- Ratings are not available to this demo account.

Please do not change the account's password.
```

## After review

Keep the server and account available, with the same credentials, while the app is on Google
Play: reviewers may return for updates or policy checks. If the credentials change, update this
section before the next submission.
