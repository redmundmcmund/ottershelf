# Contributing to Ottershelf

Thank you for your interest in improving Ottershelf. This document explains how to report problems,
propose changes and submit code.

By taking part you agree to follow the [code of conduct](CODE_OF_CONDUCT.md).

## Issues

- **Bugs:** open an issue with the app version (Settings > About Ottershelf), the Android version
  and device, the BookOrbit server version, the steps that reproduce it, and what you expected.
  Screenshots help. Never include passwords, tokens, your server's address or personal data;
  remove them from logs before attaching.
- **Feature requests:** describe the problem you want solved. Ottershelf follows what the BookOrbit
  server supports, so a feature that needs new server behaviour belongs with the
  [BookOrbit project](https://github.com/bookorbit/bookorbit) first.
- **Security vulnerabilities:** do not open a public issue; follow [SECURITY.md](SECURITY.md).

## Pull requests

1. For anything larger than a small fix, open an issue first so the approach can be agreed before
   you spend time on it.
2. Fork the repository and create a branch from `main`.
3. Keep each pull request to one change, with small commits and descriptive messages.
4. Make sure the build and tests pass (see below), and add or update tests for what you change.
5. Describe what the pull request changes and how you tested it, including the Android version
   and device for changes that affect behaviour on the phone. For visible changes, attach before
   and after screenshots in light and dark themes.

## Code style and structure

Read [ARCHITECTURE.md](ARCHITECTURE.md) before making changes. In short:

- Kotlin with the official Kotlin code style, Jetpack Compose and stable Material 3.
- One Gradle module (`:app`) and one activity. Dependencies are wired by hand in `AppContainer`;
  do not add Hilt, KSP or other code generators.
- Screens follow the ViewModel + immutable UiState + StateFlow pattern, with stateless
  `...Content` composables that tests and previews can render with fake state.
- Each feature lives in its own package under `feature/` with its own string file
  (`res/values/strings_<feature>.xml`). All user-facing text belongs in string resources.
- Use the app's theme tokens (`BookOrbitTheme.colors` and `.radii`), the Lucide icons and the shared
  components in `ui.components` rather than hard-coded colours or new one-off widgets.
- Request bodies must match the BookOrbit server's DTOs exactly: the server rejects unknown fields.
- Library versions are declared in `gradle/libs.versions.toml`. New dependencies must be open source
  under a licence compatible with the AGPL v3, must not collect data, and need an entry in
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- Do not add analytics, crash reporting, advertising, trackers or any network destination other
  than the user's server; changes to what the app sends, or to whom, must update
  [PRIVACY.md](PRIVACY.md).
- The About screen's notice strings (`about_*`) carry the attribution BookOrbit's additional terms
  require. Do not reword, hide or remove them.

## Tests

Run these before submitting; the first line must pass for every pull request:

```bash
./gradlew assembleDebug testDebugUnitTest   # build and every JVM/Robolectric test
./gradlew verifyRoborazziDebug              # screenshot tests against the recorded images
```

- **Screenshot tests** (Roborazzi on Robolectric, no emulator needed): if you change the UI on
  purpose, record the images again with `./gradlew recordRoborazziDebug` (or
  `--tests "*YourScreenshotTest"`), look at the new images in both light and dark themes under
  `app/build/outputs/roborazzi/`, and describe the change in the pull request.
- **On-device tests** (`app/src/androidTest`) run with `tools/device-tests/run.sh` on a phone
  connected through adb. Do not use `connectedAndroidTest`: Gradle uninstalls the app when it
  finishes, which deletes its sign-in and downloaded books.
- Never put real credentials, tokens or server addresses in tests or fixtures.

## Licence of contributions

Ottershelf is licensed under the GNU Affero General Public License, version 3 only
(`AGPL-3.0-only`), together with BookOrbit's additional terms ([ADDITIONAL_TERMS.md](ADDITIONAL_TERMS.md)).
By submitting a contribution, you confirm that you have the right to submit it and you agree that
it is licensed under the AGPL v3 only and that BookOrbit's additional terms apply to it, in the same
way as the rest of the project.

Do not submit code copied from sources whose licences are incompatible with this, and say so in the
pull request when you include or adapt third-party code, naming its source and licence.

### Sign-off (optional)

You may add a `Signed-off-by` line to your commits (`git commit -s`) to certify the
[Developer Certificate of Origin](https://developercertificate.org/). It is welcome but not
required.
