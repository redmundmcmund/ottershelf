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

## Instructions to enter

**Instruction name:** Demo BookOrbit server

**Username:** [demo username]

**Password:** [demo password]

**Any other information required to access your app:**

```
Ottershelf is a client for self-hosted BookOrbit servers. On the sign-in screen enter:

Server:   [demo server URL, e.g. https://demo.example.org]
Username: [demo username]
Password: [demo password]

The account is on a demo server run for app review and holds only public-domain books.
No other setup is needed.

What you can test:
- Dashboard: open the app after signing in.
- Library: menu > All books, Authors, Series; sort, filter, grid or list; long-press a cover for
  a quick view.
- Reading: open a book and tap Read. EPUB: "[EPUB title]". PDF: "[PDF title]".
  Comic: "[CBZ title]". Select text in the EPUB to highlight, add a note or tap Look up.
- Offline reading and the foreground service: on a book page tap Download. The download continues
  with a progress notification (with Cancel) when you leave the app.
- Reading timer: on a book page tap Start timer; the notification offers Pause and Stop.
- Tracking: menu > Tracking > Calendar, History, Statistics, Achievements, Notes.
- ISBN scanning: tap the barcode icon in the toolbar, then "Type the ISBN" and enter
  [demo ISBN], or scan that ISBN's barcode. The camera is requested only for scanning, page
  photos for quotes and cover photos.
- Quotes: on a book page tap "Scan a page" to photograph printed English text, or "Add quote" to
  type one.
- [Book requests: menu > Book requests. Metadata editing: the pencil on a book page.]
  [or: Book requests and metadata editing are disabled for this account.]

Please do not change the account's password.
```

## After review

Keep the server and account available, with the same credentials, while the app is on Google
Play: reviewers may return for updates or policy checks. If the credentials change, update this
section before the next submission.
