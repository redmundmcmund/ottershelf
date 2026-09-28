# Security policy

## Reporting a vulnerability

Please report security vulnerabilities privately, through GitHub's private vulnerability
reporting: open <https://github.com/redmundmcmund/ottershelf/security/advisories/new>, or go to the
repository's **Security** tab and choose **Report a vulnerability**. Do not open a public issue,
pull request or discussion for a vulnerability until a fix has been released.

A useful report includes:

- the Ottershelf version (Settings > About Ottershelf) and where it was installed from;
- the Android version and device;
- the BookOrbit server version, if the issue involves the server's responses;
- steps to reproduce, or a proof of concept, and the impact you expect.

The project aims to acknowledge a report within seven days and to keep you informed while a fix
is prepared. Once a fixed version is released, the advisory is published, crediting you unless you
prefer otherwise.

## Scope

In scope: the Ottershelf app and the code in this repository, for example the handling of tokens
and passwords, the network client, the ebook reader's isolation of book content, file handling,
and the Look up connection to Wikimedia.

Out of scope: vulnerabilities in the BookOrbit server itself, which should be reported to the
[BookOrbit project](https://github.com/bookorbit/bookorbit); vulnerabilities in Android or in
third-party components, unless Ottershelf's use of them makes the app exploitable; and issues that
require an already compromised or rooted device.

## Supported versions

Only the latest release receives security fixes. Please check that the issue still exists in the
latest release before reporting it.

| Version | Supported |
|---|---|
| Latest release | Yes |
| Older releases | No |
