# Data safety form: draft answers

A draft of the Play Console's **App content > Data safety** form for Ottershelf
(`io.github.ottershelf`), question by question. Check it against the app version being submitted
and against [PRIVACY.md](../../PRIVACY.md) (the privacy policy URL on the listing) before entering
it: the form, the policy and the app must agree.

The definitions below are Google's, as used in the form:

- **Collected**: data transmitted off the user's device by the app (including by its libraries).
  Data processed only on the device is not collected.
- **Shared**: data transferred to a third party. Not counted as sharing: a transfer the user
  initiates and reasonably expects (for example sharing through the share sheet or opening a link),
  transfers to service providers acting for the developer, legal requests, and fully anonymised
  data.
- **Processed ephemerally**: held only in memory and only for as long as needed to serve the
  request.

## What the app actually transmits

| Destination | What | When |
|---|---|---|
| The BookOrbit server whose address the user types | Username and password (sign-in only; the password is not stored), tokens, reading positions and sessions, statuses, ratings, private reviews, reading dates, goals, highlights, notes, bookmarks, quotes, likes, account settings used by the app, library searches, scanned or typed ISBNs, book requests, metadata edits, cover photos (only after the user confirms) | As the user uses the app; progress and sessions also by background sync |
| Wiktionary and Wikipedia (Wikimedia Foundation) | The word or phrase the user selected (at most twelve words), and requests for thumbnails | Only when the user taps Look up |
| Other apps the user picks | Shared highlights, quote cards, Markdown exports, selected text, links | Only when the user shares or opens them |

Nothing is sent to the developer or the project; the project runs no server. There is no analytics,
advertising, crash-reporting or telemetry SDK, no advertising ID, and no Google Play services or
Firebase library. Barcode scanning (zxing-cpp) and text recognition (Tesseract) run on the device;
page photos for quotes never leave it.

## The main decision: data sent to the user's own server

Google's definition of "collected" does not distinguish between a developer's server and a server
that the user chooses and controls. Two positions are defensible; pick one and keep the privacy
policy consistent with it.

### Option A: "No data collected"

Reasoning: the developer never receives, stores or has access to any user data. The only server
the app talks to for account data is one the user specifies by typing its address and that the
user (or an operator the user chose) runs, as with a self-hosted mail, file-sync or media client.
The data never passes through infrastructure controlled by the developer, so from the developer's
point of view nothing is collected or shared. Before relying on it, compare how
comparable self-hosted clients on Google Play declare their data.

Weaknesses: the literal definition ("transmitted off the device") covers the user's server too; a
reviewer may read it that way and treat the declaration as incomplete. The Look up requests to
Wikimedia go to a third party that neither the user nor the developer controls, which fits
Option A less comfortably (they are user-initiated and contain no account information).

### Option B (recommended): declare what is transmitted, as collected and not shared

Reasoning: this matches the literal definition, cannot be read as an under-declaration, and still
shows users the reassuring parts of the form (no sharing, encrypted in transit, optional data, no
advertising or analytics purposes). The privacy policy explains that "collected" here means "sent
to your own BookOrbit server", which the developer never sees.

The rest of this document gives the answers for Option B, with the Option A differences noted.

## Section 1: Data collection and security

| Question | Answer | Notes |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** (Option B) / **No** (Option A) | Option A ends the form here, apart from the security questions below. |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | HTTPS only; cleartext is disabled in the network security config, and Wikimedia requests are HTTPS only. |
| Which methods of account creation does your app support? | **My app does not allow users to create an account** | Accounts are created on the BookOrbit server by its operator or through BookOrbit itself. The app only signs in to an existing account. This also means the account-deletion URL requirement does not apply. If the form asks separately whether users can log in, state that the app signs in with accounts created outside it (username and password on the user's server). |
| Do you provide a way for users to request that their data is deleted? | **Yes** (recommended), see note | The app itself can delete the user's sessions, highlights, notes, bookmarks, quotes and downloaded books, and can remove its tokens (sign out) and all local data (uninstall). All other account data lives on the user's BookOrbit server and is deleted there or by its operator; PRIVACY.md, "Deleting your information", says so. If the review treats "request deletion" as a request to the developer, answer **No**: the developer holds no data to delete. |
| Has your app undergone an independent security review? | **No** | Unless one is commissioned (MASA). |
| Is your app designed for children / does it follow the Families policy? | Not applicable | See content-rating.md for the target audience. |

## Section 2: Data types (Option B)

Declare only the types below. For every one: **Collected: Yes. Shared: No.**

| Category | Data type | What it is in Ottershelf | Processed ephemerally | Required or optional | Purposes |
|---|---|---|---|---|---|
| Personal info | **User IDs** | The username typed at sign-in, sent to the user's server | No | Required | App functionality; Account management |
| Photos and videos | **Photos** | A cover photo the user takes or picks, uploaded to the user's server only after confirming it | No | Optional | App functionality |
| App activity | **In-app search history** | Library searches and scanned or typed ISBNs (to the user's server); text the user looks up (to Wikimedia, on tap) | No | Optional | App functionality |
| App activity | **Other user-generated content** | Highlights, notes, bookmarks, quotes, private reviews, ratings, book requests, metadata edits | No | Optional | App functionality |
| App activity | **Other actions** | Reading positions, reading sessions and timer sessions, read statuses and dates, reading goals, likes, Dashboard and appearance settings saved to the account | No | Required (progress sync is part of reading) | App functionality |

Notes on the choices:

- **Wikimedia and "shared".** Looking up a word sends it to Wikimedia only when the user taps Look
  up, in a feature labelled as such, which is a user-initiated transfer the user expects; it is
  therefore not declared as sharing. The data type (the looked-up text) is still declared as
  collected under In-app search history.
- **The user's server and "shared".** Under Option B the user's own server is where the data is
  collected, not a third party it is shared with.
- **Password.** Passwords are not a data type in the form. The password is sent only to the
  user's server to sign in and is never stored.
- **Account details received from the server** (display name, email, permissions) are received,
  not sent, and are not collected by the app.
- **Photos for quotes and ISBN camera frames** are processed only on the device: not collected.
  Only the ISBN (a search) and the saved quote text (user-generated content) are sent.
- **Book files** are downloaded from the server, never uploaded: Files and docs is not collected.
- **Installed apps**: the app lists the dictionary and translation apps that accept selected text
  (`PROCESS_TEXT` queries) to show them as buttons, but this list never leaves the device: not
  collected.
- **IP address**: the user's server and Wikimedia necessarily see the connection's IP address. The
  app does not use it or derive a location from it, so Location is not declared.
- **Not collected at all**: Location, Financial info, Health and fitness, Messages, Audio, Files and
  docs, Calendar, Contacts, App info and performance (no crash logs or diagnostics), Device or
  other IDs, Web browsing, Name, Email address, Address, Phone number, other personal info.

## Section 3: Preview

Expected listing summary under Option B:

- "No data shared with third parties"
- "This app may collect these data types: Personal info, Photos and videos, App activity"
- "Data is encrypted in transit"
- "You can request that data be deleted" (if answered Yes)

Under Option A: "No data collected", "No data shared with third parties", "Data is encrypted in
transit".

## Keep in sync

Any change to what the app sends, or to whom, needs this form, PRIVACY.md and the listing's
privacy line updated in the same release.
